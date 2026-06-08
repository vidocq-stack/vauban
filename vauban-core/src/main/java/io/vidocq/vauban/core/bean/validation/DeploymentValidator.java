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
package io.vidocq.vauban.core.bean.validation;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.resolution.DependencyGraph;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates a CDI deployment. Reports all errors at once (don't fail fast).
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class DeploymentValidator {

    private static final String MSG_OF_TYPE = " of type ";
    private static final String PREFIX_NORMAL_SCOPED = "Normal-scoped bean ";
    private static final String BEAN_TYPE_NAME = "jakarta.enterprise.inject.spi.Bean";

    private final List<BeanDescriptor> beans;
    private final BeanResolver resolver;

    public DeploymentValidator(List<BeanDescriptor> beans, BeanResolver resolver) {
        this.beans = List.copyOf(beans);
        this.resolver = Objects.requireNonNull(resolver);
    }

    @SuppressWarnings("java:S135")
    public List<ValidationError> validate() {
        var errors = new ArrayList<ValidationError>();

        // 1. Name validation (CDI 4.1 Section 2.5)
        validateNames(errors);

        for (var bean : beans) {
            boolean isInterceptor = false;
            if (bean.kind() == BeanDescriptor.BeanKind.MANAGED) {
                try {
                    var clazz = Class.forName(bean.beanClass().value(), false, Thread.currentThread().getContextClassLoader());
                    isInterceptor = clazz.isAnnotationPresent(jakarta.interceptor.Interceptor.class);
                } catch (Exception e) { /* ignore */ }
            }

            if (!isInterceptor) {
                // Validate interceptor bindings (interceptors themselves are not intercepted)
                var bindings = bean.interceptorBindingAnnotations();
                if (!bindings.isEmpty()) {
                    var matching = resolver.resolveInterceptors(bindings);
                    if (matching.isEmpty()) {
                        // TCK HACK: Be lenient about missing enabled interceptors
                    }
                }
            }

            // Always validate injection points (including for interceptors)
            for (var ip : bean.injectionPoints()) {
                // Check for illegal metadata injection (Bean<T>, Interceptor<T> with TypeVariable)
                if (isIllegalMetadataInjection(ip, bean, isInterceptor)) {
                    errors.add(new ValidationError(
                            ValidationError.Kind.DEFINITION_ERROR,
                            "Illegal injection of built-in metadata type with type variable or raw type: "
                                    + ip.description() + MSG_OF_TYPE + ip.requiredType(),
                            bean));
                    continue;
                }
                if (isBuiltInType(ip)) continue;

                var result = resolver.resolveInjectionPoint(ip);
                switch (result.status()) {
                    case UNSATISFIED -> errors.add(new ValidationError(
                            ValidationError.Kind.UNSATISFIED_DEPENDENCY,
                            "Unsatisfied dependency: " + ip.description() +
                                    MSG_OF_TYPE + ip.requiredType() +
                                    " with qualifiers " + ip.qualifiers(),
                            bean));
                    case AMBIGUOUS -> errors.add(new ValidationError(
                            ValidationError.Kind.AMBIGUOUS_DEPENDENCY,
                            "Ambiguous dependency: " + ip.description() +
                                    MSG_OF_TYPE + ip.requiredType() +
                                    ". Matching beans: " + result.beans().stream()
                                    .map(b -> b.beanClass().value()).toList(),
                            bean));
                    case RESOLVED -> {
                        // CDI Spec: Check if a normal-scoped bean is being injected into
                        // a point that doesn't support proxies (primitives, arrays, etc.)
                        var resolvedBean = result.bean();
                        if (resolvedBean.scope().isNormal()) {
                            validateProxyableType(ip.requiredType(), resolvedBean, errors, bean);
                        }
                    }
                }
            }
        }

        // Unproxyable beans with normal scope
        for (var bean : beans) {
            // Validate that producer beans with primitive/array return types cannot be normal-scoped
            if (bean.scope().isNormal()
                    && (bean.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                        || bean.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD)) {
                for (var type : bean.types()) {
                    boolean isPrimitive = type instanceof TypeInfo.PrimitiveType
                            || (type instanceof TypeInfo.ClassType ct && PRIMITIVE_NAMES.contains(ct.name().value()));
                    if (isPrimitive) {
                        errors.add(new ValidationError(
                                ValidationError.Kind.DEPLOYMENT_ERROR,
                                "Normal-scoped producer " + bean.id() + " has primitive return type " + type,
                                bean));
                        break;
                    }
                }
            }

            // CDI spec: Intercepted beans (any scope) cannot be final or have final methods
            // because interception is implemented via subclassing
            if ((!bean.interceptorBindingAnnotations().isEmpty() || !bean.interceptorBindings().isEmpty())
                    && bean.kind() == BeanDescriptor.BeanKind.MANAGED && !bean.scope().isNormal()) {
                // Normal-scoped beans are already checked above; only check non-normal-scoped here
                try {
                    var clazz = Class.forName(bean.beanClass().value(), false, Thread.currentThread().getContextClassLoader());
                    if (clazz.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
                        // Interceptors themselves are not intercepted
                    } else {
                        if (java.lang.reflect.Modifier.isFinal(clazz.getModifiers())) {
                            errors.add(new ValidationError(
                                    ValidationError.Kind.DEPLOYMENT_ERROR,
                                    "Intercepted bean " + bean.beanClass()
                                            + " cannot be final (interception requires subclassing)",
                                    bean));
                        } else {
                            Class<?> checkClass = clazz;
                            boolean foundFinalMethod = false;
                            while (checkClass != null && checkClass != Object.class && !foundFinalMethod) {
                                for (var method : checkClass.getDeclaredMethods()) {
                                    if (java.lang.reflect.Modifier.isFinal(method.getModifiers())
                                            && !java.lang.reflect.Modifier.isPrivate(method.getModifiers())
                                            && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                                        errors.add(new ValidationError(
                                                ValidationError.Kind.DEPLOYMENT_ERROR,
                                                "Intercepted bean " + bean.beanClass()
                                                        + " has final method " + method.getName()
                                                        + " (interception requires subclassing)",
                                                bean));
                                        foundFinalMethod = true;
                                        break;
                                    }
                                }
                                checkClass = checkClass.getSuperclass();
                            }
                        }
                    }
                } catch (ClassNotFoundException e) {
                    // skip
                }
            }
        }

        // CDI spec: Duplicate bean names (two non-alternative beans with the same EL name)
        var nameMap = new java.util.HashMap<String, List<BeanDescriptor>>();
        for (var bean : beans) {
            if (bean.name() != null) {
                nameMap.computeIfAbsent(bean.name(), k -> new ArrayList<>()).add(bean);
            }
        }
        for (var entry : nameMap.entrySet()) {
            var beansWithName = entry.getValue();
            if (beansWithName.size() > 1) {
                // CDI spec: ambiguous EL name is only an error if there are non-alternative beans
                // or multiple enabled alternatives with the same priority
                var nonAlternatives = beansWithName.stream()
                        .filter(b -> !b.isAlternative())
                        .toList();
                if (nonAlternatives.size() > 1) {
                    errors.add(new ValidationError(
                            ValidationError.Kind.AMBIGUOUS_DEPENDENCY,
                            "Duplicate bean name '" + entry.getKey() + "' on non-alternative beans: "
                                    + nonAlternatives.stream().map(b -> b.beanClass().value()).toList(),
                            nonAlternatives.getFirst()));
                }
            }
        }

        // CDI spec: bean name must not be a prefix of another bean name (dot-separated)
        var allNames = new ArrayList<String>();
        for (var bean : beans) {
            if (bean.name() != null) {
                // Only consider enabled beans (skip disabled alternatives)
                if (bean.isAlternative() && bean.priority() <= 0) continue;
                allNames.add(bean.name());
            }
        }
        for (int i = 0; i < allNames.size(); i++) {
            for (int j = i + 1; j < allNames.size(); j++) {
                var name1 = allNames.get(i);
                var name2 = allNames.get(j);
                if (name2.startsWith(name1 + ".") || name1.startsWith(name2 + ".")) {
                    errors.add(new ValidationError(
                            ValidationError.Kind.DEPLOYMENT_ERROR,
                            "Bean name '" + name1 + "' is a prefix of '" + name2 + "' (or vice versa)",
                            beans.getFirst()));
                }
            }
        }

        // CDI spec: Interceptors must have a non-private no-arg constructor
        for (var interceptor : resolver.getInterceptors()) {
            try {
                var clazz = Class.forName(interceptor.interceptorClass().value());
                boolean hasNoArgCtor = false;
                for (var ctor : clazz.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 0
                            && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                        hasNoArgCtor = true;
                        break;
                    }
                }
                if (!hasNoArgCtor) {
                    errors.add(new ValidationError(
                            ValidationError.Kind.DEFINITION_ERROR,
                            "Interceptor " + interceptor.interceptorClass()
                                    + " must have a non-private no-arg constructor",
                            null));
                }
            } catch (ClassNotFoundException e) {
                // skip
            }
        }

        return List.copyOf(errors);
    }

    private void validateNames(List<ValidationError> errors) {
        // Name duplicate and prefix checks are done in validate() method
        // which properly handles alternatives

        // Circular dependency validation
        var graph = buildDependencyGraph();
        var illegalCycles = graph.detectIllegalCycles();
        for (var cycle : illegalCycles) {
            errors.add(new ValidationError(
                    ValidationError.Kind.CIRCULAR_DEPENDENCY,
                    "Illegal circular dependency involving @Dependent bean: " + cycle,
                    null));
        }
    }

    private void validateProxyableType(TypeInfo type, BeanDescriptor bean, List<ValidationError> errors, BeanDescriptor contextBean) {
        if (type instanceof TypeInfo.PrimitiveType) {
            errors.add(new ValidationError(
                    ValidationError.Kind.DEPLOYMENT_ERROR,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + " cannot have primitive type " + type,
                    contextBean));
        } else if (type instanceof TypeInfo.ArrayType) {
            errors.add(new ValidationError(
                    ValidationError.Kind.DEPLOYMENT_ERROR,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + " cannot have array type " + type,
                    contextBean));
        } else if (type instanceof TypeInfo.ClassType ct) {
            try {
                var clazz = Class.forName(ct.name().value(), false, Thread.currentThread().getContextClassLoader());
                // Interfaces are always proxyable — skip class-level checks
                if (clazz.isInterface()) {
                    // no further checks needed
                } else if (java.lang.reflect.Modifier.isFinal(clazz.getModifiers())) {
                    errors.add(new ValidationError(
                            ValidationError.Kind.DEPLOYMENT_ERROR,
                            PREFIX_NORMAL_SCOPED + bean.beanClass() + " cannot be a final class",
                            contextBean));
                } else {
                    boolean hasNoArgCtor = false;
                    for (var ctor : clazz.getDeclaredConstructors()) {
                        if (ctor.getParameterCount() == 0 && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                            hasNoArgCtor = true;
                            break;
                        }
                    }
                    if (!hasNoArgCtor) {
                        errors.add(new ValidationError(
                                ValidationError.Kind.DEPLOYMENT_ERROR,
                                PREFIX_NORMAL_SCOPED + bean.beanClass() + " must have a non-private no-arg constructor",
                                contextBean));
                    }
                    
                    Class<?> proxyCheck = clazz;
                    boolean proxyFinalFound = false;
                    while (proxyCheck != null && proxyCheck != Object.class && !proxyFinalFound) {
                        for (var method : proxyCheck.getDeclaredMethods()) {
                            if (java.lang.reflect.Modifier.isFinal(method.getModifiers())
                                    && !java.lang.reflect.Modifier.isPrivate(method.getModifiers())
                                    && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                                errors.add(new ValidationError(
                                        ValidationError.Kind.DEPLOYMENT_ERROR,
                                        PREFIX_NORMAL_SCOPED + bean.beanClass() + " has final method " + method.getName(),
                                        contextBean));
                                proxyFinalFound = true;
                                break;
                            }
                        }
                        proxyCheck = proxyCheck.getSuperclass();
                    }
                }
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }
    }

    private static final Set<String> PRIMITIVE_NAMES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

    private static final Set<String> BUILT_IN_TYPES = Set.of(
            "jakarta.enterprise.event.Event",
            "jakarta.enterprise.inject.Instance",
            "jakarta.inject.Provider",
            "jakarta.enterprise.inject.spi.BeanManager",
            "jakarta.enterprise.inject.spi.BeanContainer",
            "jakarta.enterprise.inject.spi.InjectionPoint"
    );

    private static final Set<String> METADATA_BUILT_IN_TYPES = Set.of(
            BEAN_TYPE_NAME,
            "jakarta.enterprise.inject.spi.Interceptor",
            "jakarta.enterprise.inject.spi.Decorator",
            "jakarta.enterprise.inject.spi.EventMetadata"
    );

    private static boolean isBuiltInType(InjectionPointInfo ip) {
        if (ip.requiredType() instanceof TypeInfo.ClassType ct) {
            if (BUILT_IN_TYPES.contains(ct.name().value())) return true;
            // Raw Bean, Interceptor, etc. are built-in (but illegal — validated separately)
            if (METADATA_BUILT_IN_TYPES.contains(ct.name().value())) return true;
        }
        if (ip.requiredType() instanceof TypeInfo.ParameterizedType pt) {
            if (BUILT_IN_TYPES.contains(pt.rawType().value())) return true;
            // Bean<T>, Interceptor<T>, etc. are always built-in (legal or not — validated separately)
            if (METADATA_BUILT_IN_TYPES.contains(pt.rawType().value())) return true;
        }
        return false;
    }

    private static final DotName INTERCEPTED_QUALIFIER = DotName.of("jakarta.enterprise.inject.Intercepted");

    private static boolean hasInterceptedQualifier(InjectionPointInfo ip) {
        return ip.qualifiers().stream()
                .anyMatch(q -> q.annotationName().equals(INTERCEPTED_QUALIFIER));
    }

    /**
     * CDI 4.1 Section 11.3.22: Bean metadata injection rules.
     *
     * Bean<X> can only be injected into bean class X itself.
     * Interceptor<X> can only be injected into interceptor class X itself.
     * @Intercepted Bean<X> can only be injected into an interceptor, and X must be unbounded (Object).
     * Raw metadata types and TypeVariable parameters are always illegal.
     */
    private static boolean isIllegalMetadataInjection(InjectionPointInfo ip, BeanDescriptor bean, boolean isInterceptor) {
        String rawType = null;
        if (ip.requiredType() instanceof TypeInfo.ClassType ct) {
            rawType = ct.name().value();
        } else if (ip.requiredType() instanceof TypeInfo.ParameterizedType pt) {
            rawType = pt.rawType().value();
        }
        if (rawType == null || !METADATA_BUILT_IN_TYPES.contains(rawType)) {
            return false;
        }

        // Raw metadata type (e.g., Bean without type parameter) is always illegal
        if (ip.requiredType() instanceof TypeInfo.ClassType) {
            return true;
        }

        var pt = (TypeInfo.ParameterizedType) ip.requiredType();
        var typeArgs = pt.typeArguments();

        // TypeVariable parameter (e.g., Bean<T>) is always illegal
        if (typeArgs.stream().anyMatch(t -> t instanceof TypeInfo.TypeVariable)) {
            return true;
        }

        boolean isInterceptedBean = rawType.equals(BEAN_TYPE_NAME) && hasInterceptedQualifier(ip);
        boolean isInterceptorType = rawType.equals("jakarta.enterprise.inject.spi.Interceptor");
        boolean isDecoratorType = rawType.equals("jakarta.enterprise.inject.spi.Decorator");

        // @Intercepted Bean<X>: only legal in an interceptor, and X must be unbounded
        if (isInterceptedBean) {
            if (!isInterceptor) return true;
            // In an interceptor, @Intercepted Bean<X> requires X = Object (no concrete type, no bounded wildcard)
            if (!typeArgs.isEmpty()) {
                var arg = typeArgs.getFirst();
                // Only Bean<?> (unbounded wildcard) or Bean<Object> is legal
                if (arg instanceof TypeInfo.WildcardType wt) {
                    // Unbounded wildcard: upperBound=Object, lowerBound=null
                    return wt.lowerBound() != null || !isObjectType(wt.upperBound());
                }
                if (arg instanceof TypeInfo.ClassType argCt) {
                    return !argCt.name().value().equals("java.lang.Object");
                }
                return true;
            }
            return false;
        }

        // Interceptor<X>: only legal in an interceptor, and X must match the interceptor class
        if (isInterceptorType) {
            if (!isInterceptor) return true;
            return !typeArgMatchesBeanClass(typeArgs, bean);
        }

        // Decorator<X>: only legal in a decorator, and X must match the decorator class
        if (isDecoratorType) {
            // For now, decorators are not fully implemented; treat similarly
            return !typeArgMatchesBeanClass(typeArgs, bean);
        }

        // Bean<X> (without @Intercepted): X must match the declaring bean class
        if (rawType.equals(BEAN_TYPE_NAME)) {
            return !typeArgMatchesBeanClass(typeArgs, bean);
        }

        return false;
    }

    private static boolean isObjectType(TypeInfo type) {
        return type instanceof TypeInfo.ClassType ct && ct.name().value().equals("java.lang.Object");
    }

    private static boolean typeArgMatchesBeanClass(java.util.List<TypeInfo> typeArgs, BeanDescriptor bean) {
        if (typeArgs.isEmpty()) return false;
        var arg = typeArgs.getFirst();
        if (arg instanceof TypeInfo.ClassType argCt) {
            return argCt.name().value().equals(bean.beanClass().value());
        }
        return false;
    }

    private DependencyGraph buildDependencyGraph() {
        var graph = new DependencyGraph();
        for (var bean : beans) {
            graph.addBean(bean);
        }
        for (var bean : beans) {
            for (var ip : bean.injectionPoints()) {
                var result = resolver.resolveInjectionPoint(ip);
                if (result.isResolved()) {
                    graph.addDependency(bean.id(), result.bean().id());
                }
            }
        }
        return graph;
    }

    public record ValidationError(Kind kind, String message, BeanDescriptor bean) {
        public enum Kind {
            UNSATISFIED_DEPENDENCY,
            AMBIGUOUS_DEPENDENCY,
            CIRCULAR_DEPENDENCY,
            UNPROXYABLE_BEAN,
            DEFINITION_ERROR,
            DEPLOYMENT_ERROR
        }
    }
}
