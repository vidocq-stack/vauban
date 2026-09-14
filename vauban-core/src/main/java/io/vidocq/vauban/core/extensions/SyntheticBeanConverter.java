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
    /**
     * Maps a runtime {@code Class} bean type to the index model. Array classes
     * (whose {@code getName()} is the JVM binary form {@code [Ljava.lang.Boolean;}
     * / {@code [Z}) become {@link TypeInfo.ArrayType} so they can match injection
     * points, which the discovery index always models as {@code ArrayType}.
     * Primitive components keep the {@code ClassType("boolean")} form the
     * discovery index uses for injection-point types. Cf. VAU-BCE-004.
     */
    private static TypeInfo runtimeClassToTypeInfo(Class<?> cls) {
        if (cls.isArray()) {
            int dimensions = 0;
            Class<?> component = cls;
            while (component.isArray()) {
                dimensions++;
                component = component.getComponentType();
            }
            return new TypeInfo.ArrayType(runtimeClassToTypeInfo(component), dimensions);
        }
        return new TypeInfo.ClassType(DotName.of(cls.getName()));
    }

    static BeanDescriptor toBeanDescriptor(VaubanSyntheticBeanBuilder<?> synBean, int slot) {
        var beanClass = synBean.getBeanClass();
        var beanName = DotName.of(beanClass.getName());

        var beanTypes = new LinkedHashSet<TypeInfo>();
        for (var type : synBean.getTypes()) {
            if (type instanceof Class<?> cls) {
                beanTypes.add(runtimeClassToTypeInfo(cls));
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
     * The member values of a qualifier the extension built, so the resolver can compare them (cf.
     * VAU-BCE-001). An instance the container built carries them; any other is read member by member,
     * every kind kept (BUG-20260914-15).
     */
    private static Map<String, AnnotationValue> extractAnnotationMembers(Annotation annotation) {
        return io.vidocq.vauban.core.annotation.AnnotationValues.infoOf(annotation).members();
    }

}
