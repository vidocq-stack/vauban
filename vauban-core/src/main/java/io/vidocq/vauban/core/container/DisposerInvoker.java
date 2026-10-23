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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.DisposerDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.resolution.QualifierMatcher;
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.event.EventImpl;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class DisposerInvoker {

    private static final String DISPOSER_METHOD = "Disposer method ";

    /** Parameter types a disposer receives from the container itself, so they are never resolved as beans. */
    private static final Set<String> BUILT_IN_PARAMETER_TYPES = Set.of(
            "jakarta.enterprise.inject.spi.BeanManager",
            "jakarta.enterprise.inject.spi.BeanContainer",
            "jakarta.enterprise.inject.spi.InjectionPoint",
            "jakarta.enterprise.inject.Instance",
            "jakarta.inject.Provider",
            "jakarta.enterprise.event.Event");

    private final VaubanContainer container;
    private final VaubanLookup vaubanLookup;

    DisposerInvoker(VaubanContainer container, VaubanLookup vaubanLookup) {
        this.container = container;
        this.vaubanLookup = vaubanLookup;
    }

    /** The disposer wired to each producer, by the producer's id. Read by {@link CodegenCoverage}. */
    private final java.util.Map<io.vidocq.vauban.core.bean.model.BeanId, DisposerDescriptor> wired =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The disposer wired to {@code producer}, or {@code null} when it has none. */
    DisposerDescriptor disposerOf(io.vidocq.vauban.core.bean.model.BeanId producer) {
        return wired.get(producer);
    }

    static void validateDisposerParameters(
            List<DisposerDescriptor> disposers,
            List<BeanDescriptor> descriptors,
            VaubanIndex index,
            QualifierMatcher qualifierMatcher) {
        var assignability = new AssignabilityRules(index);
        var tempResolver = new BeanResolver(
                descriptors, List.of(), assignability, qualifierMatcher);

        for (var disposer : disposers) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = Class.forName(disposer.declaringClass().value(), false, cl);
                var method = firstMethodNamed(clazz, disposer.methodName());
                if (method != null) {
                    validateDisposerMethodParameters(disposer, method, tempResolver);
                }
            } catch (jakarta.enterprise.inject.spi.DeploymentException
                    | jakarta.enterprise.inject.spi.DefinitionException e) {
                throw e;
            } catch (Exception _) {
                // A disposer whose class or parameters cannot be read here is not validated.
            }
        }
    }

    private static Method firstMethodNamed(Class<?> clazz, String name) {
        for (var method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static void validateDisposerMethodParameters(DisposerDescriptor disposer, Method method,
            BeanResolver resolver) {
        for (int pi = 0; pi < method.getParameterCount(); pi++) {
            if (!method.getParameters()[pi].isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                validateDisposerParameter(disposer, method, pi, resolver);
            }
        }
    }

    /** Checks parameter {@code pi}, other than the disposed one, of a disposer method. */
    private static void validateDisposerParameter(DisposerDescriptor disposer, Method method, int pi,
            BeanResolver resolver) {
        var param = method.getParameters()[pi];
        var paramType = param.getType();
        var paramTypeName = paramType.getName();
        if (paramTypeName.equals("jakarta.enterprise.inject.spi.Bean")) {
            validateBeanParameter(disposer, method.getGenericParameterTypes()[pi]);
            return;
        }
        if (BUILT_IN_PARAMETER_TYPES.contains(paramTypeName)) {
            return;
        }
        // What the parameter declares, not @Default assumed for it: a qualified
        // parameter used to read as unsatisfied against beans that carry its
        // qualifier (vauban#89).
        var described = describedParameter(disposer, pi);
        var ip = described != null ? described : new InjectionPointInfo(
                new TypeInfo.ClassType(DotName.of(paramType.getName())),
                java.util.Set.of(new QualifierInstance(
                        DotName.of("jakarta.enterprise.inject.Default"),
                        java.util.Map.of()),
                        new QualifierInstance(
                        DotName.of("jakarta.enterprise.inject.Any"),
                        java.util.Map.of())),
                InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                "disposer parameter " + param.getName());
        var result = resolver.resolveInjectionPoint(ip);
        if (result.status() == BeanResolver.ResolutionResult.Status.UNSATISFIED) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                    DISPOSER_METHOD + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                            + "(): unsatisfied dependency for parameter of type " + paramType.getName());
        } else if (result.status() == BeanResolver.ResolutionResult.Status.AMBIGUOUS) {
            throw new jakarta.enterprise.inject.spi.DeploymentException(
                    DISPOSER_METHOD + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                            + "(): ambiguous dependency for parameter of type " + paramType.getName());
        }
    }

    /** A {@code Bean} parameter of a disposer must be {@code Bean<?>}, never a concrete parameterization. */
    private static void validateBeanParameter(DisposerDescriptor disposer, java.lang.reflect.Type genericType) {
        if (genericType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length > 0
                && !(pt.getActualTypeArguments()[0] instanceof java.lang.reflect.WildcardType)) {
            throw new jakarta.enterprise.inject.spi.DefinitionException(
                DISPOSER_METHOD + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                + "(): Bean parameter must use wildcard type (Bean<?>), not concrete type");
        }
    }

    static void validateDisposerMethodDefinitions(List<DisposerDescriptor> disposers) {
        var disposersByKey = new java.util.HashMap<String, List<DisposerDescriptor>>();
        for (var disposer : disposers) {
            var key = disposer.declaringClass().value() + "#" + disposer.disposedType();
            disposersByKey.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(disposer);
        }
        for (var entry : disposersByKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new jakarta.enterprise.inject.spi.DefinitionException(
                        "Multiple disposer methods for the same producer type: " + entry.getKey());
            }
        }
    }

    void wireDisposers(List<BeanDescriptor> descriptors, List<DisposerDescriptor> disposers) {
        for (var descriptor : descriptors) {
            if (descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                    || descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                var bean = container.beans.get(descriptor.id());
                if (bean != null) {
                    wireDisposersOf(descriptor, bean, disposers);
                }
            }
        }
    }

    /** Sets the destroyer of the producer {@code bean} to the disposer that disposes it, failing on a second one. */
    private void wireDisposersOf(BeanDescriptor descriptor, ManagedBean<?> bean, List<DisposerDescriptor> disposers) {
        for (var disposer : disposers) {
            if (disposes(descriptor, disposer)) {
                if (bean.getDestroyer() != null) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                        "Multiple disposer methods for producer " + descriptor.id() + " in " + descriptor.beanClass());
                }
                wired.put(descriptor.id(), disposer);
                bean.setDestroyer((instance, _) -> callDisposer(instance, disposer));
            }
        }
    }

    /** Whether {@code disposer} disposes the producer {@code descriptor}: same class, matching type and qualifiers. */
    private boolean disposes(BeanDescriptor descriptor, DisposerDescriptor disposer) {
        return disposer.declaringClass().equals(descriptor.beanClass())
                && matchesDisposerType(descriptor, disposer)
                && qualifiersMatch(descriptor, disposer);
    }

    private boolean qualifiersMatch(BeanDescriptor descriptor, DisposerDescriptor disposer) {
        if (hasExplicitAnyOnDisposerParam(disposer)) {
            return true;
        }
        var disposerQualNames = explicitQualifierNames(disposer.qualifiers());
        var producerQualNames = explicitQualifierNames(descriptor.qualifiers());
        if (disposerQualNames.isEmpty()) {
            return producerQualNames.isEmpty();
        }
        return producerQualNames.containsAll(disposerQualNames)
                && verifyDisposerQualifiersViaReflection(descriptor, disposer);
    }

    /** The names of {@code qualifiers} other than {@code @Default} and {@code @Any}. */
    private static Set<DotName> explicitQualifierNames(Set<QualifierInstance> qualifiers) {
        return qualifiers.stream()
                .filter(q -> !q.isDefault() && !q.isAny())
                .map(QualifierInstance::annotationName)
                .collect(Collectors.toSet());
    }

    boolean matchesDisposerType(BeanDescriptor descriptor, DisposerDescriptor disposer) {
        var disposedType = disposer.disposedType();
        for (var bt : descriptor.types()) {
            if (bt.equals(disposedType) || sameClassName(bt, disposedType)) return true;
        }
        return disposedType instanceof TypeInfo.ClassType(var disposedName)
                && hasTypeAssignableTo(descriptor, disposedName);
    }

    private static boolean sameClassName(TypeInfo type, TypeInfo other) {
        return type instanceof TypeInfo.ClassType(var name)
                && other instanceof TypeInfo.ClassType(var otherName)
                && name.equals(otherName);
    }

    /** Whether a class or parameterized bean type of {@code descriptor} is assignable to the class {@code target}. */
    private static boolean hasTypeAssignableTo(BeanDescriptor descriptor, DotName target) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var disposedClass = Class.forName(target.value(), false, cl);
            for (var bt : descriptor.types()) {
                String btName = rawClassName(bt);
                if (btName != null && disposedClass.isAssignableFrom(Class.forName(btName, false, cl))) {
                    return true;
                }
            }
        } catch (ClassNotFoundException _) {
            // A type that cannot be loaded here does not match.
        }
        return false;
    }

    private static String rawClassName(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType(var name) -> name.value();
            case TypeInfo.ParameterizedType(var rawType, _) -> rawType.value();
            case null, default -> null;
        };
    }

    boolean hasExplicitAnyOnDisposerParam(DisposerDescriptor disposer) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var declaringClass = Class.forName(disposer.declaringClass().value(), false, cl);
            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(disposer.methodName())
                        && method.getParameterCount() > disposer.parameterIndex()) {
                    for (var ann : method.getParameters()[disposer.parameterIndex()].getAnnotations()) {
                        if (ann.annotationType() == jakarta.enterprise.inject.Any.class) return true;
                    }
                    return false;
                }
            }
        } catch (Exception _) {
            // A disposer that cannot be read here is taken as declaring no @Any.
        }
        return false;
    }

    boolean verifyDisposerQualifiersViaReflection(BeanDescriptor producer, DisposerDescriptor disposer) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var declaringClass = Class.forName(producer.beanClass().value(), false, cl);

            var producerAnnotations = producerAnnotations(declaringClass, producer);
            if (producerAnnotations.isEmpty()) return true;

            var disposerParamAnnotations = disposedParameterAnnotations(declaringClass, disposer);
            if (disposerParamAnnotations.isEmpty()) return true;

            for (var dAnn : disposerParamAnnotations.get()) {
                if (isExplicitQualifier(dAnn.annotationType())
                        && !containsEqual(producerAnnotations.get(), dAnn)) {
                    return false;
                }
            }
            return true;
        } catch (Exception _) {
            return true;
        }
    }

    /** The annotations of the producer method or field {@code producer} names, when it can be found. */
    private static Optional<Annotation[]> producerAnnotations(Class<?> declaringClass, BeanDescriptor producer) {
        var producerId = producer.id().value();
        if (producer.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD && producerId.contains("#")) {
            var methodName = producerId.substring(producerId.indexOf('#') + 1);
            for (var m : declaringClass.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    return Optional.of(m.getAnnotations());
                }
            }
        } else if (producer.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD && producerId.contains(".")) {
            var fieldName = producerId.substring(producerId.lastIndexOf('.') + 1);
            try {
                return Optional.of(declaringClass.getDeclaredField(fieldName).getAnnotations());
            } catch (NoSuchFieldException _) {
                // No such field: nothing to compare with.
            }
        }
        return Optional.empty();
    }

    /** The annotations of the disposed parameter of {@code disposer}, when its method can be found. */
    private static Optional<Annotation[]> disposedParameterAnnotations(Class<?> declaringClass,
            DisposerDescriptor disposer) {
        for (var m : declaringClass.getDeclaredMethods()) {
            if (m.getName().equals(disposer.methodName()) && m.getParameterCount() > disposer.parameterIndex()) {
                return Optional.of(m.getParameterAnnotations()[disposer.parameterIndex()]);
            }
        }
        return Optional.empty();
    }

    /**
     * A qualifier the disposed parameter declares explicitly: neither {@code @Default}, {@code @Any} nor
     * {@code @Disposes}.
     */
    private static boolean isExplicitQualifier(Class<? extends Annotation> type) {
        return type.isAnnotationPresent(jakarta.inject.Qualifier.class)
                && type != jakarta.enterprise.inject.Default.class
                && type != jakarta.enterprise.inject.Any.class
                && type != jakarta.enterprise.inject.Disposes.class;
    }

    private static boolean containsEqual(Annotation[] annotations, Annotation wanted) {
        for (var annotation : annotations) {
            if (annotation.annotationType() == wanted.annotationType() && annotation.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The injection point the descriptor records for parameter {@code index}, or {@code null}. */
    private static InjectionPointInfo describedParameter(DisposerDescriptor disposer, int index) {
        var wanted = InjectionPointInfo.parameterDescription(
                disposer.declaringClass().simpleName(), disposer.methodName(), index);
        InjectionPointInfo found = null;
        for (var point : disposer.injectionPoints()) {
            if (!point.description().equals(wanted) && !point.description().startsWith(wanted + " ")) continue;
            if (found != null) return null;
            found = point;
        }
        return found;
    }

    /**
     * Calls the disposer method on {@code producedInstance}. The call gets its own creational context, so the
     * produced instance's context is not needed.
     */
    void callDisposer(Object producedInstance, DisposerDescriptor disposer) {
        try {
            var declaringClass = container.loadClass(disposer.declaringClass().value());
            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(disposer.methodName())
                        && method.getParameterCount() > disposer.parameterIndex()) {
                    invokeDisposer(producedInstance, disposer, declaringClass, method);
                    return;
                }
            }
        } catch (Exception _) {
            // CDI spec: exceptions in disposer methods are suppressed
        }
    }

    private void invokeDisposer(Object producedInstance, DisposerDescriptor disposer, Class<?> declaringClass,
            Method method) {
        var bm = container.getBeanManager();
        // The call gets its own context: the @Dependent objects created to receive it (the
        // declaring instance, the other parameters) are destroyed when it completes (CDI 4.1
        // §6.4.2). Releasing the produced instance's context instead destroyed the produced
        // instance a second time, running this disposer twice (BUG-20260914-18).
        var ctx = new CreationalContextImpl<>();
        try {
            var declBeans = bm.getBeans(declaringClass);
            var declBean = declBeans.isEmpty() ? null : bm.resolve(declBeans);
            var isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
            Object declaringInstance = null;
            if (!isStatic) {
                declaringInstance = declBean != null
                        ? bm.getReference(declBean, declaringClass, ctx)
                        : container.selectByBeanClass(declaringClass);
            }
            var args = disposerArguments(producedInstance, disposer, method, bm, ctx);
            if (isStatic) {
                vaubanLookup.invokeStaticMethod(method, args);
            } else {
                vaubanLookup.invokeMethod(declaringInstance, method, args);
            }
        } finally {
            ctx.release();
        }
    }

    private Object[] disposerArguments(Object producedInstance, DisposerDescriptor disposer, Method method,
            BeanManager bm, CreationalContext<?> ctx) {
        var paramTypes = method.getParameterTypes();
        var args = new Object[method.getParameterCount()];
        args[disposer.parameterIndex()] = producedInstance;
        for (int i = 0; i < paramTypes.length; i++) {
            if (i != disposer.parameterIndex()) {
                args[i] = disposerArgument(disposer, method, i, paramTypes[i], bm, ctx);
            }
        }
        return args;
    }

    /** The value of parameter {@code i}, of type {@code paramType}, of a disposer method: not the disposed one. */
    private Object disposerArgument(DisposerDescriptor disposer, Method method, int i, Class<?> paramType,
            BeanManager bm, CreationalContext<?> ctx) {
        // What the parameter declares. Resolving on its type alone handed a
        // qualified parameter the @Default bean (vauban#89); a parameter the
        // descriptor does not describe still falls back on reading the method.
        var described = describedParameter(disposer, i);
        var qualifiers = described != null
                ? QualifierUtils.toAnnotations(described.declaredQualifiers(), null, null,
                        container.qualifierMatcher().types()).toArray(new Annotation[0])
                : QualifierHelper.extractParamQualifiers(method.getParameters()[i]);
        if (paramType == BeanManager.class) {
            return container.getBeanManager();
        }
        if (paramType == Event.class) {
            var eventIp = new VaubanInjectionPoint(method.getGenericParameterTypes()[i],
                    java.util.Set.of(qualifiers), null, method);
            return new EventImpl<>(container.eventDispatcher(),
                    QualifierHelper.eventQualifiers(qualifiers), eventIp);
        }
        if (paramType == Instance.class) {
            return instanceArgument(method, i, qualifiers);
        }
        var beans = bm.getBeans(paramType, qualifiers);
        var bean = beans.isEmpty() ? null : bm.resolve(beans);
        return bean != null ? bm.getReference(bean, paramType, ctx) : container.select(paramType);
    }

    private Object instanceArgument(Method method, int i, Annotation[] qualifiers) {
        Class<?> instanceType = Object.class;
        var genericType = method.getGenericParameterTypes()[i];
        if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            instanceType = c;
        }
        var ip = new VaubanInjectionPoint(genericType, java.util.Set.of(qualifiers), null, method);
        return new InstanceImpl<>(container, instanceType, ip);
    }

    static void validateDisposerDefinitions(
            List<DisposerDescriptor> disposers,
            List<BeanDescriptor> descriptors) {
        var producerBeans = descriptors.stream()
                .filter(d -> d.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                        || d.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD)
                .toList();

        for (var disposer : disposers) {
            boolean found = producerBeans.stream()
                    .anyMatch(producer -> (producer.beanClass().equals(disposer.declaringClass())
                            || producerDeclaredIn(producer, disposer.declaringClass()))
                            && disposerMatchesProducerType(disposer, producer));
            if (!found && declaredInManagedBean(disposer, descriptors)) {
                throw new jakarta.enterprise.inject.spi.DefinitionException(
                        DISPOSER_METHOD + disposer.declaringClass().value() + "." + disposer.methodName()
                                + "(): no matching producer found for disposed type " + disposer.disposedType());
            }
        }
    }

    private static boolean declaredInManagedBean(DisposerDescriptor disposer, List<BeanDescriptor> descriptors) {
        return descriptors.stream()
                .anyMatch(d -> d.beanClass().equals(disposer.declaringClass())
                        && d.kind() == BeanDescriptor.BeanKind.MANAGED);
    }

    private static boolean producerDeclaredIn(BeanDescriptor producer, DotName declaringClass) {
        return producer.id().value().startsWith(declaringClass.value());
    }

    static boolean disposerMatchesProducerType(DisposerDescriptor disposer, BeanDescriptor producer) {
        for (var producerType : producer.types()) {
            if (sameClassName(producerType, disposer.disposedType()) || producerType.equals(disposer.disposedType())) {
                return true;
            }
        }
        return false;
    }
}
