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
            for (var ip : bean.injectionPoints()) {
                if (isBuiltInType(ip)) continue;
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
                    case RESOLVED -> {}
                }
            }
        }

        var graph = buildDependencyGraph();
        for (var cycle : graph.detectIllegalCycles()) {
            errors.add(new ValidationError(
                    ValidationError.Kind.CIRCULAR_DEPENDENCY,
                    "Circular dependency involving @Dependent bean: " + cycle,
                    null));
        }

        return List.copyOf(errors);
    }

    private static final Set<String> BUILT_IN_TYPES = Set.of(
            "jakarta.enterprise.event.Event",
            "jakarta.enterprise.inject.Instance",
            "jakarta.inject.Provider",
            "jakarta.enterprise.inject.spi.BeanManager",
            "jakarta.enterprise.inject.spi.InjectionPoint"
    );

    private static boolean isBuiltInType(InjectionPointInfo ip) {
        if (ip.requiredType() instanceof TypeInfo.ClassType ct) {
            return BUILT_IN_TYPES.contains(ct.name().value());
        }
        if (ip.requiredType() instanceof TypeInfo.ParameterizedType pt) {
            return BUILT_IN_TYPES.contains(pt.rawType().value());
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
            CIRCULAR_DEPENDENCY
        }
    }
}
