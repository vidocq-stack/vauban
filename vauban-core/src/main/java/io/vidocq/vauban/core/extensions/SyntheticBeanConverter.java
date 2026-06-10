/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Converts the in-memory result of a BCE {@code @Synthesis} method
 * ({@link VaubanSyntheticBeanBuilder}) into a {@link BeanDescriptor}.
 * Extracted from {@link BceProcessor} (which stays the facade and delegates).
 */
final class SyntheticBeanConverter {

    private SyntheticBeanConverter() {
    }

    /**
     * Converts a {@link VaubanSyntheticBeanBuilder} (the in-memory result of a BCE {@code @Synthesis}
     * method) into a {@link BeanDescriptor} that the resolver and validator can reason about.
     *
     * <p>Used both by the runtime container (to register the bean in the live bean manager) and by
     * the APT processor (to feed the deployment validator so {@code @Inject MyBceBean} compiles
     * cleanly without an {@code Instance<>} workaround).
     *
     * <p>The {@code slot} parameter is folded into the synthetic bean's unique key — it must be
     * unique within the surrounding bean list (callers typically pass {@code descriptors.size()}).
     * No factory or creator is materialized here; that is a runtime-only concern handled by the
     * container.
     */
    static BeanDescriptor toBeanDescriptor(VaubanSyntheticBeanBuilder<?> synBean, int slot) {
        var beanClass = synBean.getBeanClass();
        var beanName = DotName.of(beanClass.getName());

        var beanTypes = new LinkedHashSet<TypeInfo>();
        for (var type : synBean.getTypes()) {
            if (type instanceof Class<?> cls) {
                beanTypes.add(new TypeInfo.ClassType(DotName.of(cls.getName())));
            }
        }
        // Bean types added via the lang-model API (type(jakarta...Type)) are stored
        // separately and already converted to TypeInfo. Cf. VAU-BCE-001.
        beanTypes.addAll(synBean.getIndexTypes());
        if (beanTypes.isEmpty()) {
            beanTypes.add(new TypeInfo.ClassType(beanName));
            beanTypes.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
        }

        var scope = ScopeInfo.DEPENDENT;
        if (synBean.getScopeAnnotation() != null) {
            var scopeAnn = synBean.getScopeAnnotation();
            if (scopeAnn == jakarta.enterprise.context.ApplicationScoped.class) {
                scope = ScopeInfo.APPLICATION;
            } else if (scopeAnn == jakarta.enterprise.context.RequestScoped.class) {
                scope = ScopeInfo.REQUEST;
            } else if (scopeAnn == jakarta.inject.Singleton.class) {
                scope = ScopeInfo.SINGLETON;
            } else if (scopeAnn == jakarta.enterprise.context.SessionScoped.class) {
                scope = ScopeInfo.SESSION;
            } else if (scopeAnn == jakarta.enterprise.context.ConversationScoped.class) {
                scope = ScopeInfo.CONVERSATION;
            }
        }

        var qualifiers = new LinkedHashSet<QualifierInstance>();
        boolean hasExplicitQualifier = false;
        for (var q : synBean.getQualifiers()) {
            var qName = DotName.of(q.annotationType().getName());
            if (!qName.equals(QualifierInstance.DEFAULT_NAME) && !qName.equals(QualifierInstance.ANY_NAME)) {
                hasExplicitQualifier = true;
            }
            // Preserve qualifier members so the resolver can match @Tagged("scalar")
            // against an injection point whose qualifier has the same value (and
            // reject one with a different value). Cf. VAU-BCE-001.
            qualifiers.add(new QualifierInstance(qName, extractAnnotationMembers(q)));
        }
        if (!hasExplicitQualifier) {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
        qualifiers.add(QualifierInstance.ANY);

        var syntheticKey = DotName.of(beanName.value() + "#synthetic#" + slot);
        return new BeanDescriptor(
                new BeanId(syntheticKey.value()),
                beanName,
                BeanDescriptor.BeanKind.SYNTHETIC,
                beanTypes,
                qualifiers,
                scope,
                synBean.isAlternative(),
                synBean.getPriority(),
                List.of(),
                synBean.getName()
        );
    }

    /**
     * Extract the member values of an annotation instance via reflection so the
     * resolver can compare qualifier members at runtime. Annotation members
     * with non-trivial types (Class, Enum, nested annotation, arrays) are
     * mapped to their {@link AnnotationValue} counterpart; unsupported shapes
     * fall through to a string representation. Cf. VAU-BCE-001.
     */
    @SuppressWarnings("java:S3011")
    private static Map<String, AnnotationValue> extractAnnotationMembers(Annotation annotation) {
        var annType = annotation.annotationType();
        var out = new LinkedHashMap<String, AnnotationValue>();
        for (var m : annType.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            try {
                m.setAccessible(true);
                var value = m.invoke(annotation);
                var converted = toAnnotationValue(value);
                if (converted != null) out.put(m.getName(), converted);
            } catch (ReflectiveOperationException ignored) {
                // member not readable — skip silently, the resolver simply won't have
                // a value to compare against, which is the same as before this fix.
            }
        }
        return out;
    }

    private static AnnotationValue toAnnotationValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return new AnnotationValue.StringVal(s);
        if (value instanceof Boolean b) return new AnnotationValue.BooleanVal(b);
        if (value instanceof Byte b) return new AnnotationValue.ByteVal(b);
        if (value instanceof Character c) return new AnnotationValue.CharVal(c);
        if (value instanceof Short s) return new AnnotationValue.ShortVal(s);
        if (value instanceof Integer i) return new AnnotationValue.IntVal(i);
        if (value instanceof Long l) return new AnnotationValue.LongVal(l);
        if (value instanceof Float f) return new AnnotationValue.FloatVal(f);
        if (value instanceof Double d) return new AnnotationValue.DoubleVal(d);
        if (value instanceof Class<?> c) return new AnnotationValue.ClassVal(DotName.of(c.getName()));
        if (value instanceof Enum<?> e) return new AnnotationValue.EnumVal(
                DotName.of(e.getDeclaringClass().getName()), e.name());
        if (value instanceof Annotation a) return new AnnotationValue.AnnotationVal(
                new io.vidocq.vauban.indexer.model.AnnotationInfo(
                        DotName.of(a.annotationType().getName()),
                        extractAnnotationMembers(a)));
        if (value.getClass().isArray()) {
            var len = java.lang.reflect.Array.getLength(value);
            var elements = new ArrayList<AnnotationValue>(len);
            for (int i = 0; i < len; i++) {
                var converted = toAnnotationValue(java.lang.reflect.Array.get(value, i));
                if (converted != null) elements.add(converted);
            }
            return new AnnotationValue.ArrayVal(elements);
        }
        return new AnnotationValue.StringVal(value.toString());
    }
}
