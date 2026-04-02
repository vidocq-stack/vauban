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
    private final List<fr.vidocq.vauban.core.bean.model.InterceptorDescriptor> interceptors;
    private final AssignabilityRules assignability;
    private final fr.vidocq.vauban.core.interceptor.InterceptorManager interceptorManager;

    public BeanResolver(List<BeanDescriptor> beans, AssignabilityRules assignability) {
        this(beans, List.of(), assignability);
    }

    public BeanResolver(List<BeanDescriptor> beans,
                        List<fr.vidocq.vauban.core.bean.model.InterceptorDescriptor> interceptors,
                        AssignabilityRules assignability) {
        this.beans = List.copyOf(beans);
        this.interceptors = List.copyOf(interceptors);
        this.assignability = Objects.requireNonNull(assignability);
        this.interceptorManager = new fr.vidocq.vauban.core.interceptor.InterceptorManager(interceptors);
    }

    public List<fr.vidocq.vauban.core.bean.model.InterceptorDescriptor> getInterceptors() {
        return interceptors;
    }

    /**
     * Resolves interceptors for a set of bindings.
     * CDI 4.1 Section 9.5.2.
     */
    public List<fr.vidocq.vauban.core.bean.model.InterceptorDescriptor> resolveInterceptors(
            List<java.lang.annotation.Annotation> beanAnnotations) {
        return interceptorManager.resolveInterceptors(beanAnnotations);
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
            if (assignability.beanTypeMatches(beanType, requiredType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CDI alternative selection:
     * If any beans are alternatives with {@code @Priority}, only the highest priority alternative(s) are selected.
     * Non-alternative beans are included unless an alternative overrides them.
     */
    private List<BeanDescriptor> applyAlternativeSelection(List<BeanDescriptor> candidates) {
        if (candidates.size() <= 1) return candidates;

        // Separate alternatives and non-alternatives
        var alternatives = candidates.stream()
                .filter(b -> b.isAlternative() && b.priority() > 0)
                .toList();
        
        var nonAlternatives = candidates.stream()
                .filter(b -> !b.isAlternative())
                .toList();

        if (alternatives.isEmpty()) {
            return nonAlternatives;
        }

        // Find max priority among alternatives
        int maxPriority = alternatives.stream()
                .mapToInt(BeanDescriptor::priority)
                .max().orElse(0);

        // CDI 4.1 Section 5.2.2:
        // "An alternative with higher priority prevails over an alternative with lower priority, 
        // and over a bean which is not an alternative."
        return alternatives.stream()
                .filter(b -> b.priority() == maxPriority)
                .toList();
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
