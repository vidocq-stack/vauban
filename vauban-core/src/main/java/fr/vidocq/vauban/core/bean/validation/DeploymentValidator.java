package fr.vidocq.vauban.core.bean.validation;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.InjectionPointInfo;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.bean.resolution.DependencyGraph;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates a CDI deployment. Reports all errors at once (don't fail fast).
 */
public final class DeploymentValidator {

    private final List<BeanDescriptor> beans;
    private final BeanResolver resolver;

    public DeploymentValidator(List<BeanDescriptor> beans, BeanResolver resolver) {
        this.beans = List.copyOf(beans);
        this.resolver = Objects.requireNonNull(resolver);
    }

    public List<ValidationError> validate() {
        var errors = new ArrayList<ValidationError>();

        for (var bean : beans) {
            // CDI spec: Interceptors themselves are not intercepted
            if (bean.kind() == BeanDescriptor.BeanKind.MANAGED) {
                try {
                    var clazz = Class.forName(bean.beanClass().value());
                    if (clazz.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) continue;
                } catch (Exception e) { /* ignore */ }
            }

            // Validate interceptor bindings
            var bindings = bean.interceptorBindings();
            if (!bindings.isEmpty()) {
                // CDI spec: At least one enabled interceptor must match each binding
                for (var binding : bindings) {
                    var matching = resolver.resolveInterceptors(Set.of(binding));
                    if (matching.isEmpty()) {
                        errors.add(new ValidationError(
                                ValidationError.Kind.DEFINITION_ERROR,
                                "Interceptor binding " + binding + " on bean " + bean.beanClass()
                                        + " does not match any enabled interceptor",
                                bean));
                    }
                }
            }

            for (var ip : bean.injectionPoints()) {
                if (isBuiltInType(ip)) continue;
                // Skip parameterized type validation — our bytecode index doesn't
                // track generic type arguments, so resolution would be incorrect
                if (ip.requiredType() instanceof TypeInfo.ParameterizedType) continue;
                var result = resolver.resolveInjectionPoint(ip);
                switch (result.status()) {
                    case UNSATISFIED -> errors.add(new ValidationError(
                            ValidationError.Kind.UNSATISFIED_DEPENDENCY,
                            "Unsatisfied dependency: " + ip.description() +
                                    " of type " + ip.requiredType() +
                                    " with qualifiers " + ip.qualifiers(),
                            bean));
                    case AMBIGUOUS -> errors.add(new ValidationError(
                            ValidationError.Kind.AMBIGUOUS_DEPENDENCY,
                            "Ambiguous dependency: " + ip.description() +
                                    " of type " + ip.requiredType() +
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
            if (bean.scope().isNormal()) {
                // All normal-scoped beans must have a proxyable type
                for (var type : bean.types()) {
                    validateProxyableType(type, bean, errors, bean);
                }

                if (bean.kind() == BeanDescriptor.BeanKind.MANAGED) {
                    try {
                        var clazz = Class.forName(bean.beanClass().value());
                        if (java.lang.reflect.Modifier.isFinal(clazz.getModifiers())) {
                            errors.add(new ValidationError(
                                    ValidationError.Kind.DEFINITION_ERROR,
                                    "Normal-scoped bean " + bean.beanClass()
                                            + " cannot be final (unproxyable)",
                                    bean));
                        }
                        // CDI spec: normal-scoped beans cannot have final methods
                        // (except private, static, or methods from Object)
                        for (var method : clazz.getDeclaredMethods()) {
                            if (java.lang.reflect.Modifier.isFinal(method.getModifiers())
                                    && !java.lang.reflect.Modifier.isPrivate(method.getModifiers())
                                    && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                                errors.add(new ValidationError(
                                        ValidationError.Kind.DEFINITION_ERROR,
                                        "Normal-scoped bean " + bean.beanClass()
                                                + " has final method " + method.getName()
                                                + " (unproxyable)",
                                        bean));
                                break; // one error per bean is enough
                            }
                        }
                        // Also check: bean must have a non-private no-arg constructor
                        // (or no explicit constructor) to be proxyable
                        boolean hasNoArgCtor = false;
                        boolean hasAnyCtor = false;
                        for (var ctor : clazz.getDeclaredConstructors()) {
                            hasAnyCtor = true;
                            if (ctor.getParameterCount() == 0
                                    && !java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) {
                                hasNoArgCtor = true;
                                break;
                            }
                        }
                        if (hasAnyCtor && !hasNoArgCtor) {
                            errors.add(new ValidationError(
                                    ValidationError.Kind.DEFINITION_ERROR,
                                    "Normal-scoped bean " + bean.beanClass()
                                            + " has no non-private no-arg constructor (unproxyable)",
                                    bean));
                        }
                    } catch (ClassNotFoundException e) {
                        // skip
                    }
                }
            }

            // CDI spec: Intercepted beans can have @Inject constructors.
            // But they must be proxiable if they are normal-scoped.
            // CDILite: Interception via subclassing should support parameterized constructors.
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
                            ValidationError.Kind.DEFINITION_ERROR,
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

    private void validateProxyableType(TypeInfo type, BeanDescriptor bean, List<ValidationError> errors, BeanDescriptor contextBean) {
        if (type instanceof TypeInfo.PrimitiveType) {
            errors.add(new ValidationError(
                    ValidationError.Kind.DEFINITION_ERROR,
                    "Normal-scoped bean " + bean.beanClass() + " cannot have primitive type " + type,
                    contextBean));
        } else if (type instanceof TypeInfo.ArrayType) {
            errors.add(new ValidationError(
                    ValidationError.Kind.DEFINITION_ERROR,
                    "Normal-scoped bean " + bean.beanClass() + " cannot have array type " + type,
                    contextBean));
        }
    }

    private static final Set<String> BUILT_IN_TYPES = Set.of(
            "jakarta.enterprise.event.Event",
            "jakarta.enterprise.inject.Instance",
            "jakarta.inject.Provider",
            "jakarta.enterprise.inject.spi.BeanManager",
            "jakarta.enterprise.inject.spi.BeanContainer",
            "jakarta.enterprise.inject.spi.InjectionPoint"
    );

    private static final Set<String> METADATA_BUILT_IN_TYPES = Set.of(
            "jakarta.enterprise.inject.spi.Bean",
            "jakarta.enterprise.inject.spi.Interceptor",
            "jakarta.enterprise.inject.spi.Decorator",
            "jakarta.enterprise.inject.spi.EventMetadata"
    );

    private static boolean isBuiltInType(InjectionPointInfo ip) {
        if (ip.requiredType() instanceof TypeInfo.ClassType ct) {
            return BUILT_IN_TYPES.contains(ct.name().value());
        }
        if (ip.requiredType() instanceof TypeInfo.ParameterizedType pt) {
            if (BUILT_IN_TYPES.contains(pt.rawType().value())) return true;
            // Bean<T>, Interceptor<T>, etc. are built-in only when parameterized
            // with a concrete type (not a TypeVariable)
            if (METADATA_BUILT_IN_TYPES.contains(pt.rawType().value())) {
                boolean hasTypeVariable = pt.typeArguments().stream()
                        .anyMatch(t -> t instanceof TypeInfo.TypeVariable);
                return !hasTypeVariable;
            }
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
            DEFINITION_ERROR
        }
    }
}
