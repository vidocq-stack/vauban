package fr.vidocq.vauban.core.bean.resolution;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.InjectionPointInfo;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The main bean resolution engine.
 * <p>
 * Resolves beans matching a required type and qualifiers following CDI 4.1 Section 2.5.
 */
public final class BeanResolver {

    private final List<BeanDescriptor> beans;
    private final AssignabilityRules assignability;

    public BeanResolver(List<BeanDescriptor> beans, AssignabilityRules assignability) {
        this.beans = List.copyOf(beans);
        this.assignability = Objects.requireNonNull(assignability);
    }

    /**
     * Resolves beans matching a required type and qualifiers.
     * CDI 4.1 Section 2.5.
     */
    public List<BeanDescriptor> resolve(TypeInfo requiredType, Set<QualifierInstance> qualifiers) {
        var matching = new ArrayList<BeanDescriptor>();

        for (var bean : beans) {
            if (matchesType(bean, requiredType) && QualifierMatcher.matches(bean.qualifiers(), qualifiers)) {
                matching.add(bean);
            }
        }

        // If alternatives are present, filter by highest priority
        return applyAlternativeSelection(matching);
    }

    /**
     * Resolves a single bean. Returns empty if unsatisfied or ambiguous.
     */
    public Optional<BeanDescriptor> resolveUnique(TypeInfo requiredType, Set<QualifierInstance> qualifiers) {
        var resolved = resolve(requiredType, qualifiers);
        if (resolved.size() == 1) return Optional.of(resolved.getFirst());
        return Optional.empty();
    }

    /**
     * Resolves for an injection point, returning a detailed result.
     */
    public ResolutionResult resolveInjectionPoint(InjectionPointInfo ip) {
        var resolved = resolve(ip.requiredType(), ip.qualifiers());
        if (resolved.isEmpty()) {
            return new ResolutionResult(ip, List.of(), ResolutionResult.Status.UNSATISFIED);
        }
        if (resolved.size() > 1) {
            return new ResolutionResult(ip, resolved, ResolutionResult.Status.AMBIGUOUS);
        }
        return new ResolutionResult(ip, resolved, ResolutionResult.Status.RESOLVED);
    }

    private boolean matchesType(BeanDescriptor bean, TypeInfo requiredType) {
        for (var beanType : bean.types()) {
            if (assignability.isAssignable(beanType, requiredType)) return true;
        }
        return false;
    }

    /**
     * CDI alternative selection:
     * If any beans are alternatives with {@code @Priority}, only the highest priority alternative(s) are selected.
     * Non-alternative beans are included unless an alternative overrides them.
     */
    private List<BeanDescriptor> applyAlternativeSelection(List<BeanDescriptor> candidates) {
        // CDI spec: @Alternative without @Priority is NOT enabled — filter out
        var enabled = candidates.stream()
                .filter(b -> !b.isAlternative() || b.priority() > 0)
                .toList();

        if (enabled.size() <= 1) return new ArrayList<>(enabled);

        var alternatives = enabled.stream()
                .filter(BeanDescriptor::isAlternative)
                .filter(b -> b.priority() > 0)
                .toList();

        if (alternatives.isEmpty()) return new ArrayList<>(enabled);

        // Find max priority
        int maxPriority = alternatives.stream()
                .mapToInt(BeanDescriptor::priority)
                .max().orElse(0);

        // Return only highest priority alternatives
        return alternatives.stream()
                .filter(b -> b.priority() == maxPriority)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Result of resolving an injection point.
     */
    public record ResolutionResult(
            InjectionPointInfo injectionPoint,
            List<BeanDescriptor> beans,
            Status status
    ) {
        public enum Status { RESOLVED, UNSATISFIED, AMBIGUOUS }

        public boolean isResolved() {
            return status == Status.RESOLVED;
        }

        public BeanDescriptor bean() {
            return beans.getFirst();
        }
    }
}
