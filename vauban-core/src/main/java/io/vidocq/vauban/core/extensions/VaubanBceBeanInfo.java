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

import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.DisposerDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanFieldInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanMethodInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.DisposerInfo;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapts Vauban's {@link BeanDescriptor} to the BCE {@link BeanInfo} interface.
 */
public final class VaubanBceBeanInfo implements BeanInfo {

    private static final String DISPOSES_NAME = jakarta.enterprise.inject.Disposes.class.getName();

    private final BeanDescriptor descriptor;
    /** Whether the bean is a built-in bean of the container, whose class no index holds. */
    private final boolean builtIn;
    private final IndexLookup lookup;

    public VaubanBceBeanInfo(BeanDescriptor descriptor, IndexLookup lookup) {
        this(descriptor, lookup, false);
    }

    private VaubanBceBeanInfo(BeanDescriptor descriptor, IndexLookup lookup, boolean builtIn) {
        this.descriptor = descriptor;
        this.lookup = lookup;
        this.builtIn = builtIn;
    }

    /**
     * The {@code BeanInfo} of a built-in bean ({@link BuiltInBeans}): its declaring class is its interface, described
     * from the class file the deployment's loader finds, as no index holds it.
     */
    static VaubanBceBeanInfo builtIn(BeanDescriptor descriptor, IndexLookup lookup) {
        return new VaubanBceBeanInfo(descriptor, lookup, true);
    }

    @Override
    public jakarta.enterprise.inject.build.compatible.spi.ScopeInfo scope() {
        return new VaubanBceScopeInfo(descriptor.scope(), lookup);
    }

    @Override
    public Collection<Type> types() {
        return descriptor.types().stream()
                .map(t -> TypeMapper.map(t, lookup))
                .toList();
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        return descriptor.qualifiers().stream()
                .map(q -> (AnnotationInfo) new SimpleAnnotationInfo(q.annotationName().value(), java.util.Map.of()))
                .toList();
    }

    @Override
    public ClassInfo declaringClass() {
        var indexClass = lookup.getClass(descriptor.beanClass()).orElse(null);
        if (indexClass != null) {
            return new VaubanClassInfo(indexClass, lookup);
        }
        return builtIn ? VaubanClassInfo.of(descriptor.beanClass(), lookup) : null;
    }

    @Override
    public boolean isClassBean() {
        return descriptor.kind() == BeanDescriptor.BeanKind.MANAGED;
    }

    @Override
    public boolean isProducerMethod() {
        return descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD;
    }

    @Override
    public boolean isProducerField() {
        return descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD;
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }

    /**
     * The producer method of a producer method bean, found in the index by the name its id carries and, for one of
     * overloaded methods, by its erased parameter types (BUG-20261116-11); {@code null} for any other bean. It was
     * {@code null} for every bean (BUG-20261117-13).
     */
    @Override
    public MethodInfo producerMethod() {
        var declaring = isProducerMethod() ? lookup.getClass(descriptor.beanClass()).orElse(null) : null;
        if (declaring == null) {
            return null;
        }
        var member = memberName();
        int open = member.indexOf('(');
        var name = open < 0 ? member : member.substring(0, open);
        return declaring.methods().stream()
                .filter(method -> method.name().equals(name) && !method.isConstructor()
                        && (open < 0 || member.substring(open).equals(erasedParameters(method))))
                .findFirst()
                .map(method -> (MethodInfo) new VaubanMethodInfo(method, new VaubanClassInfo(declaring, lookup),
                        lookup))
                .orElse(null);
    }

    /** The producer field of a producer field bean, found in the index by its name; {@code null} for any other bean. */
    @Override
    public FieldInfo producerField() {
        var declaring = isProducerField() ? lookup.getClass(descriptor.beanClass()).orElse(null) : null;
        if (declaring == null) {
            return null;
        }
        var name = memberName();
        return declaring.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .map(field -> (FieldInfo) new VaubanFieldInfo(field, new VaubanClassInfo(declaring, lookup), lookup))
                .orElse(null);
    }

    /** What the id of a producer bean names after its declaring class: {@code method(T1,T2)}, or a field name. */
    private String memberName() {
        return descriptor.id().value().substring(descriptor.beanClass().value().length() + 1);
    }

    private static String erasedParameters(io.vidocq.vauban.indexer.model.MethodInfo method) {
        return method.parameters().stream().map(parameter -> BeanDiscovery.erasedName(parameter.type()))
                .map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
    }

    @Override
    public boolean isAlternative() {
        return descriptor.isAlternative();
    }

    @Override
    public Integer priority() {
        return descriptor.priorityDeclared() ? descriptor.priority() : null;
    }

    @Override
    public String name() {
        return descriptor.name();
    }

    /**
     * The disposer method of a producer bean: a method of its declaring class whose {@code @Disposes} parameter has
     * the erasure of one of the bean's types and qualifiers the bean has, {@code @Default} for none (CDI 4.1, 3.4.2);
     * {@code null} when it has none, and for any other bean (BUG-20261117-13).
     */
    @Override
    public DisposerInfo disposer() {
        if (!isProducerMethod() && !isProducerField()) {
            return null;
        }
        var indexClass = lookup.getClass(descriptor.beanClass()).orElse(null);
        if (indexClass == null) {
            return null;
        }
        var declaringClass = new VaubanClassInfo(indexClass, lookup);
        var qualifiers = descriptor.qualifiers().stream().map(q -> q.annotationName().value())
                .collect(Collectors.toSet());
        for (var indexMethod : indexClass.methods()) {
            int position = indexMethod.isConstructor() ? -1 : disposedPosition(indexMethod);
            if (position < 0
                    || !DisposerDescriptor.disposes(indexMethod.parameters().get(position).type(), descriptor)) {
                continue;
            }
            var method = new VaubanMethodInfo(indexMethod, declaringClass, lookup);
            var parameter = method.parameters().get(position);
            if (qualifiers.containsAll(qualifiersOf(parameter))) {
                return new VaubanBceDisposerInfo(method, parameter);
            }
        }
        return null;
    }

    /** The position of the {@code @Disposes} parameter of {@code method}; -1 for none. */
    private static int disposedPosition(io.vidocq.vauban.indexer.model.MethodInfo method) {
        var parameters = method.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).annotations().stream()
                    .anyMatch(annotation -> annotation.name().value().equals(DISPOSES_NAME))) {
                return i;
            }
        }
        return -1;
    }

    /** The qualifiers of a disposed parameter, {@code @Default} for none. */
    private static Set<String> qualifiersOf(jakarta.enterprise.lang.model.declarations.ParameterInfo parameter) {
        var names = parameter.annotations(annotation -> annotation.declaration().hasAnnotation(jakarta.inject.Qualifier
                .class) || annotation.name().equals(jakarta.inject.Named.class.getName())).stream()
                .map(AnnotationInfo::name).collect(Collectors.toSet());
        return names.isEmpty() ? Set.of(jakarta.enterprise.inject.Default.class.getName()) : names;
    }

    /**
     * The stereotypes the bean declares: the annotations of its class, or of its producer method or field, whose type
     * is a {@code @Stereotype}. It gave none (BUG-20261117-13).
     */
    @Override
    public Collection<StereotypeInfo> stereotypes() {
        var declaration = declaration();
        if (declaration == null) {
            return List.of();
        }
        var stereotypes = new ArrayList<StereotypeInfo>();
        for (var annotation : declaration.annotations()) {
            if (annotation.declaration().hasAnnotation(jakarta.enterprise.inject.Stereotype.class)) {
                stereotypes.add(new VaubanBceStereotypeInfo(annotation.declaration(), lookup));
            }
        }
        return stereotypes;
    }

    /** What declares the bean: its producer method or field, or its class. */
    private jakarta.enterprise.lang.model.AnnotationTarget declaration() {
        if (isProducerMethod()) {
            return producerMethod();
        }
        return isProducerField() ? producerField() : declaringClass();
    }

    @Override
    public Collection<jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo> injectionPoints() {
        return descriptor.injectionPoints().stream()
                .map(ip -> (jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo)
                        new VaubanBceInjectionPointInfo(ip, descriptor.beanClass(), lookup))
                .toList();
    }

    public BeanDescriptor descriptor() {
        return descriptor;
    }
}
