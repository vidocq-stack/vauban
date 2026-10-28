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
import io.vidocq.vauban.core.loading.DeploymentClasses;
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.indexer.VaubanIndex;
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
    private static final String MSG_HAS_FINAL_METHOD = " has final method ";
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";
    private static final String PREFIX_NORMAL_SCOPED = "Normal-scoped bean ";
    private static final String BEAN_TYPE_NAME = "jakarta.enterprise.inject.spi.Bean";
    private static final String MSG_UNPROXYABLE_CTOR =
            " must have a non-private no-arg constructor, or declare a non-private constructor"
            + " taking " + ClientProxyShape.PROXY_LINK_CLASS
            + " as its client-proxy entry point (Vauban extension, Vidocq/vauban#24)";

    private final List<BeanDescriptor> beans;
    private final BeanResolver resolver;
    private final VaubanIndex index;

    /**
     * Bean classes the load-time weaving agent will equip with the {@code (ProxyLink)}
     * constructor at class definition (vauban#24 IDE-build fallback) — their build output
     * legitimately lacks the marker, so the unproxyable-constructor check must not fire.
     */
    private Set<String> loadTimeWoven = Set.of();

    /** Reflection-only fallback — proxyability checks are blind to unloadable classes. */
    public DeploymentValidator(List<BeanDescriptor> beans, BeanResolver resolver) {
        this(beans, resolver, null);
    }

    /**
     * Index-backed validator: proxyability (CDI 4.1 "Unproxyable bean types") is checked
     * against {@code index} first, so it works at annotation-processing time and on the
     * module path where {@code Class.forName} over the TCCL cannot see the bean classes.
     */
    public DeploymentValidator(List<BeanDescriptor> beans, BeanResolver resolver, VaubanIndex index) {
        this.beans = List.copyOf(beans);
        this.resolver = Objects.requireNonNull(resolver);
        this.index = index;
    }

    /** Declares classes that will be woven at load time; returns {@code this}. */
    public DeploymentValidator loadTimeWoven(Set<String> beanClassNames) {
        this.loadTimeWoven = Set.copyOf(beanClassNames);
        return this;
    }

    public List<ValidationError> validate() {
        var errors = new ArrayList<ValidationError>();

        // 1. Name validation (CDI 4.1 Section 2.5)
        validateNames(errors);

        for (var bean : beans) {
            validateInjectionPoints(bean, errors);
        }

        // Unproxyable beans with normal scope
        for (var bean : beans) {
            validateNormalScopedProducer(bean, errors);
            validateInterceptedBeanClass(bean, errors);
        }

        validateDuplicateNames(errors);
        validateNamePrefixes(errors);
        validateInterceptorConstructors(errors);

        return List.copyOf(errors);
    }

    /** Whether a managed bean's class, loaded from the deployment, is an interceptor. */
    private static boolean isInterceptorBean(BeanDescriptor bean) {
        if (bean.kind() != BeanDescriptor.BeanKind.MANAGED) {
            return false;
        }
        try {
            var clazz = DeploymentClasses.load(bean.beanClass().value());
            return clazz.isAnnotationPresent(jakarta.interceptor.Interceptor.class);
        } catch (Exception _) {
            return false;
        }
    }

    private void validateInjectionPoints(BeanDescriptor bean, List<ValidationError> errors) {
        boolean isInterceptor = isInterceptorBean(bean);

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
            } else if (!isBuiltInType(ip)) {
                validateResolution(ip, bean, errors);
            }
        }
    }

    private void validateResolution(InjectionPointInfo ip, BeanDescriptor bean, List<ValidationError> errors) {
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

    /** Producer beans with primitive return types cannot be normal-scoped. */
    private static void validateNormalScopedProducer(BeanDescriptor bean, List<ValidationError> errors) {
        if (!bean.scope().isNormal()
                || (bean.kind() != BeanDescriptor.BeanKind.PRODUCER_METHOD
                    && bean.kind() != BeanDescriptor.BeanKind.PRODUCER_FIELD)) {
            return;
        }
        bean.types().stream()
                .filter(type -> type instanceof TypeInfo.PrimitiveType
                        || (type instanceof TypeInfo.ClassType(var name) && PRIMITIVE_NAMES.contains(name.value())))
                .findFirst()
                .ifPresent(type -> errors.add(new ValidationError(
                        ValidationError.Kind.DEPLOYMENT_ERROR,
                        "Normal-scoped producer " + bean.id() + " has primitive return type " + type,
                        bean)));
    }

    /**
     * CDI spec: Intercepted beans (any scope) cannot be final or have final methods because
     * interception is implemented via subclassing. Normal-scoped beans are already checked as
     * proxyable types; only the others are checked here, and interceptors are not intercepted.
     */
    private static void validateInterceptedBeanClass(BeanDescriptor bean, List<ValidationError> errors) {
        if ((bean.interceptorBindingAnnotations().isEmpty() && bean.interceptorBindings().isEmpty())
                || bean.kind() != BeanDescriptor.BeanKind.MANAGED || bean.scope().isNormal()) {
            return;
        }
        try {
            var clazz = DeploymentClasses.load(bean.beanClass().value());
            if (clazz.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
                return;
            }
            if (java.lang.reflect.Modifier.isFinal(clazz.getModifiers())) {
                errors.add(new ValidationError(
                        ValidationError.Kind.DEPLOYMENT_ERROR,
                        "Intercepted bean " + bean.beanClass()
                                + " cannot be final (interception requires subclassing)",
                        bean));
                return;
            }
            var finalMethod = firstOverridableFinalMethod(clazz);
            if (finalMethod != null) {
                errors.add(new ValidationError(
                        ValidationError.Kind.DEPLOYMENT_ERROR,
                        "Intercepted bean " + bean.beanClass()
                                + MSG_HAS_FINAL_METHOD + finalMethod.getName()
                                + " (interception requires subclassing)",
                        bean));
            }
        } catch (ClassNotFoundException _) {
            // Not loadable here; the runtime container re-validates
        }
    }

    /**
     * The first final method a subclass would have to override, walking up from {@code clazz}:
     * neither private nor static. {@code null} when there is none.
     */
    private static java.lang.reflect.Method firstOverridableFinalMethod(Class<?> clazz) {
        for (Class<?> checkClass = clazz; checkClass != null && checkClass != Object.class;
                checkClass = checkClass.getSuperclass()) {
            for (var method : checkClass.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (java.lang.reflect.Modifier.isFinal(modifiers)
                        && !java.lang.reflect.Modifier.isPrivate(modifiers)
                        && !java.lang.reflect.Modifier.isStatic(modifiers)) {
                    return method;
                }
            }
        }
        return null;
    }

    /** CDI spec: Duplicate bean names (two non-alternative beans with the same EL name). */
    private void validateDuplicateNames(List<ValidationError> errors) {
        var nameMap = new java.util.HashMap<String, List<BeanDescriptor>>();
        for (var bean : beans) {
            if (bean.name() != null) {
                nameMap.computeIfAbsent(bean.name(), k -> new ArrayList<>()).add(bean);
            }
        }
        for (var entry : nameMap.entrySet()) {
            // CDI spec: ambiguous EL name is only an error if there are non-alternative beans
            // or multiple enabled alternatives with the same priority
            var nonAlternatives = entry.getValue().stream()
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

    /** CDI spec: bean name must not be a prefix of another bean name (dot-separated). */
    private void validateNamePrefixes(List<ValidationError> errors) {
        var allNames = new ArrayList<String>();
        for (var bean : beans) {
            // Only consider enabled beans (skip disabled alternatives)
            if (bean.name() != null && !(bean.isAlternative() && bean.priority() <= 0)) {
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
    }

    /**
     * An interceptor is a managed bean, so it needs a bean constructor (CDI 4.1 "Declaring a bean constructor"):
     * exactly one {@code @Inject} constructor, or else a non-private no-arg constructor (VAU-INT-009).
     */
    private void validateInterceptorConstructors(List<ValidationError> errors) {
        for (var interceptor : resolver.getInterceptors()) {
            try {
                var clazz = DeploymentClasses.load(interceptor.interceptorClass().value());
                var constructors = clazz.getDeclaredConstructors();
                long injectCtors = java.util.Arrays.stream(constructors)
                        .filter(ctor -> ctor.isAnnotationPresent(jakarta.inject.Inject.class))
                        .count();
                boolean hasNoArgCtor = java.util.Arrays.stream(constructors)
                        .anyMatch(ctor -> ctor.getParameterCount() == 0
                                && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()));
                String problem = null;
                if (injectCtors > 1) {
                    problem = " declares more than one @Inject constructor";
                } else if (injectCtors == 0 && !hasNoArgCtor) {
                    problem = " must have a non-private no-arg constructor or an @Inject constructor";
                }
                if (problem != null) {
                    errors.add(new ValidationError(
                            ValidationError.Kind.DEFINITION_ERROR,
                            "Interceptor " + interceptor.interceptorClass() + problem,
                            null));
                }
            } catch (ClassNotFoundException _) {
                // Not loadable here; the runtime container re-validates
            }
        }
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

    /**
     * CDI 4.1 "Unproxyable bean types", checked <strong>against the index first</strong>:
     * reflection over the TCCL cannot see classes being compiled (annotation processing) nor
     * app modules on the module path, and used to skip silently — letting an unproxyable bean
     * through validation to fail later inside the generated proxy (Vidocq/vauban#24).
     * Reflection remains the fallback for types absent from the index.
     */
    private void validateProxyableType(TypeInfo type, BeanDescriptor bean, List<ValidationError> errors, BeanDescriptor contextBean) {
        switch (type) {
        case TypeInfo.PrimitiveType _ ->
            errors.add(new ValidationError(
                    ValidationError.Kind.DEPLOYMENT_ERROR,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + " cannot have primitive type " + type,
                    contextBean));
        case TypeInfo.ArrayType _ ->
            errors.add(new ValidationError(
                    ValidationError.Kind.DEPLOYMENT_ERROR,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + " cannot have array type " + type,
                    contextBean));
        case TypeInfo.ClassType ct -> {
            var indexed = index == null
                    ? java.util.Optional.<io.vidocq.vauban.indexer.model.ClassInfo>empty()
                    : index.getClassByName(ct.name());
            if (indexed.isPresent()) {
                validateProxyableClassInfo(indexed.get(), bean, errors, contextBean);
            } else {
                validateProxyableByReflection(ct, bean, errors, contextBean);
            }
        }
        case null, default -> {
            // Any other type (a type variable, a parameterized type) has nothing to check here
        }
        }
    }

    private void validateProxyableClassInfo(io.vidocq.vauban.indexer.model.ClassInfo info,
            BeanDescriptor bean, List<ValidationError> errors, BeanDescriptor contextBean) {
        // Interfaces are always proxyable — skip class-level checks
        if (info.isInterface()) {
            return;
        }
        if (info.isFinal()) {
            errors.add(new ValidationError(
                    ValidationError.Kind.DEPLOYMENT_ERROR,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + " cannot be a final class",
                    contextBean));
            return;
        }

        boolean hasExplicitCtor = info.methods().stream().anyMatch(m -> m.isConstructor());
        boolean hasProxyEntryCtor = !hasExplicitCtor // implicit no-arg constructor
                || info.methods().stream().anyMatch(m -> m.isConstructor() && !m.isPrivate()
                        && (m.parameters().isEmpty() || ClientProxyShape.isProxyLinkConstructor(m)));
        if (!hasProxyEntryCtor && !loadTimeWoven.contains(info.name().value())) {
            // Distinct kind: the vauban-maven-plugin can weave the marker at process-classes,
            // so the processor downgrades this one to a warning (final members it cannot fix).
            errors.add(new ValidationError(
                    ValidationError.Kind.UNPROXYABLE_BEAN,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + MSG_UNPROXYABLE_CTOR,
                    contextBean));
        }

        // Final methods, walking the hierarchy through the index; a superclass absent from
        // the index ends the walk (its members are re-checked by the runtime container).
        var current = info;
        while (current != null && !JAVA_LANG_OBJECT.equals(current.name().value())) {
            for (var method : current.methods()) {
                if (method.isConstructor() || method.isStaticInitializer()) continue;
                if (method.isFinal() && !method.isPrivate() && !method.isStatic()) {
                    errors.add(new ValidationError(
                            ValidationError.Kind.DEPLOYMENT_ERROR,
                            PREFIX_NORMAL_SCOPED + bean.beanClass() + MSG_HAS_FINAL_METHOD + method.name(),
                            contextBean));
                    return;
                }
            }
            var superName = current.superName();
            current = superName == null || index == null
                    ? null : index.getClassByName(superName).orElse(null);
        }
    }

    private void validateProxyableByReflection(TypeInfo.ClassType ct, BeanDescriptor bean,
            List<ValidationError> errors, BeanDescriptor contextBean) {
        try {
            var clazz = DeploymentClasses.load(ct.name().value());
            // Interfaces are always proxyable — skip class-level checks
            if (!clazz.isInterface()) {
                validateProxyableClass(clazz, bean, errors, contextBean);
            }
        } catch (ClassNotFoundException _) {
            // Ignore — the type is not loadable here; the runtime container re-validates.
        }
    }

    private void validateProxyableClass(Class<?> clazz, BeanDescriptor bean,
            List<ValidationError> errors, BeanDescriptor contextBean) {
        if (java.lang.reflect.Modifier.isFinal(clazz.getModifiers())) {
            errors.add(new ValidationError(
                    ValidationError.Kind.DEPLOYMENT_ERROR,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + " cannot be a final class",
                    contextBean));
            return;
        }
        boolean hasProxyEntryCtor = java.util.Arrays.stream(clazz.getDeclaredConstructors())
                .anyMatch(ctor -> !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())
                        && (ctor.getParameterCount() == 0 || ClientProxyShape.isProxyLinkConstructor(ctor)));
        if (!hasProxyEntryCtor && !loadTimeWoven.contains(clazz.getName())) {
            errors.add(new ValidationError(
                    ValidationError.Kind.UNPROXYABLE_BEAN,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + MSG_UNPROXYABLE_CTOR,
                    contextBean));
        }

        var finalMethod = firstOverridableFinalMethod(clazz);
        if (finalMethod != null) {
            errors.add(new ValidationError(
                    ValidationError.Kind.DEPLOYMENT_ERROR,
                    PREFIX_NORMAL_SCOPED + bean.beanClass() + MSG_HAS_FINAL_METHOD + finalMethod.getName(),
                    contextBean));
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
            "jakarta.enterprise.inject.spi.InjectionPoint",
            "jakarta.enterprise.context.control.RequestContextController"
    );

    private static final Set<String> METADATA_BUILT_IN_TYPES = Set.of(
            BEAN_TYPE_NAME,
            "jakarta.enterprise.inject.spi.Interceptor",
            "jakarta.enterprise.inject.spi.Decorator",
            "jakarta.enterprise.inject.spi.EventMetadata"
    );

    private static boolean isBuiltInType(InjectionPointInfo ip) {
        if (ip.requiredType() instanceof TypeInfo.ClassType(var name)) {
            if (BUILT_IN_TYPES.contains(name.value())) return true;
            // Raw Bean, Interceptor, etc. are built-in (but illegal — validated separately)
            if (METADATA_BUILT_IN_TYPES.contains(name.value())) return true;
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
        String rawType = switch (ip.requiredType()) {
            case TypeInfo.ClassType(var name) -> name.value();
            case TypeInfo.ParameterizedType pt -> pt.rawType().value();
            case null, default -> null;
        };
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
                if (arg instanceof TypeInfo.WildcardType(var upperBound, var lowerBound)) {
                    // Unbounded wildcard: upperBound=Object, lowerBound=null
                    return lowerBound != null || !isObjectType(upperBound);
                }
                if (arg instanceof TypeInfo.ClassType(var argName)) {
                    return !argName.value().equals(JAVA_LANG_OBJECT);
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
        return type instanceof TypeInfo.ClassType(var name) && name.value().equals(JAVA_LANG_OBJECT);
    }

    private static boolean typeArgMatchesBeanClass(java.util.List<TypeInfo> typeArgs, BeanDescriptor bean) {
        if (typeArgs.isEmpty()) return false;
        var arg = typeArgs.getFirst();
        if (arg instanceof TypeInfo.ClassType(var argName)) {
            return argName.value().equals(bean.beanClass().value());
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
