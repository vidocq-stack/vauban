package fr.vidocq.vauban.core.bean.validation;

import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.AnnotationInfo;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.MethodInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates CDI class-level rules before bean discovery.
 * These rules detect invalid CDI constructs that should cause a DeploymentException.
 */
public final class ClassValidator {

    private static final DotName INJECT = DotName.of("jakarta.inject.Inject");
    private static final DotName PRODUCES = DotName.of("jakarta.enterprise.inject.Produces");
    private static final DotName OBSERVES = DotName.of("jakarta.enterprise.event.Observes");
    private static final DotName OBSERVES_ASYNC = DotName.of("jakarta.enterprise.event.ObservesAsync");
    private static final DotName DISPOSES = DotName.of("jakarta.enterprise.inject.Disposes");
    private static final DotName NORMAL_SCOPE = DotName.of("jakarta.enterprise.context.NormalScope");
    private static final DotName SCOPE = DotName.of("jakarta.inject.Scope");

    private static final Set<DotName> BUILT_IN_SCOPES = Set.of(
            DotName.of("jakarta.enterprise.context.ApplicationScoped"),
            DotName.of("jakarta.enterprise.context.RequestScoped"),
            DotName.of("jakarta.enterprise.context.Dependent"),
            DotName.of("jakarta.enterprise.context.SessionScoped"),
            DotName.of("jakarta.enterprise.context.ConversationScoped"),
            DotName.of("jakarta.inject.Singleton")
    );

    private ClassValidator() {
    }

    /**
     * Validates all known classes in the index for CDI structural rules.
     *
     * @param index the class index
     * @return list of validation error messages (empty if valid)
     */
    public static List<String> validate(VaubanIndex index) {
        var errors = new ArrayList<String>();
        for (var classInfo : index.getKnownClasses()) {
            validateClass(classInfo, index, errors);
        }
        return errors;
    }

    private static void validateClass(ClassInfo classInfo, VaubanIndex index, List<String> errors) {
        // Skip interfaces, annotations, enums — only validate concrete/abstract classes
        if (classInfo.isAnnotation() || classInfo.isEnum()) return;

        var className = classInfo.name().toString();

        // 1. Multiple @Inject constructors
        long injectCtorCount = classInfo.methods().stream()
                .filter(m -> m.isConstructor() && hasAnn(m.annotations(), INJECT))
                .count();
        if (injectCtorCount > 1) {
            errors.add("Bean " + className + " has multiple @Inject constructors");
        }

        // Too many scopes on a bean with bean-defining annotation
        if (hasBeanDefiningAnnotation(classInfo, index)) {
            long scopeCount = classInfo.annotations().stream()
                    .filter(a -> isScopeAnnotation(a.name(), index))
                    .count();
            if (scopeCount > 1) {
                errors.add("Bean " + className + " has multiple scope annotations");
            }
        }

        // Generic bean class (has type parameters) cannot be a managed bean
        // unless it's a concrete subclass that resolves all type params
        if (classInfo.isAbstract() && hasBeanDefiningAnnotation(classInfo, index)
                && !classInfo.isInterface()) {
            // Abstract classes with bean-defining annotations but no concrete subclass
            // are not valid beans (this is checked elsewhere)
        }

        // Validate each method
        for (var method : classInfo.methods()) {
            validateMethod(method, className, errors);
        }

        // 8. @Produces + @Inject on the same field
        for (var field : classInfo.fields()) {
            if (hasAnn(field.annotations(), PRODUCES) && hasAnn(field.annotations(), INJECT)) {
                errors.add("Field " + className + "." + field.name()
                        + " cannot be both @Produces and @Inject");
            }
        }
    }

    private static void validateMethod(MethodInfo method, String className, List<String> errors) {
        var params = method.parameters();

        // Count observer parameters
        long observesCount = params.stream()
                .filter(p -> hasAnn(p.annotations(), OBSERVES))
                .count();
        long asyncObservesCount = params.stream()
                .filter(p -> hasAnn(p.annotations(), OBSERVES_ASYNC))
                .count();
        long totalObservesCount = observesCount + asyncObservesCount;
        boolean hasObserves = totalObservesCount > 0;
        boolean hasProduces = hasAnn(method.annotations(), PRODUCES);
        boolean hasDisposes = params.stream()
                .anyMatch(p -> hasAnn(p.annotations(), DISPOSES));

        // 7. Constructor with @Observes, @ObservesAsync, or @Disposes parameter
        if (method.isConstructor()) {
            for (var param : params) {
                if (hasAnn(param.annotations(), OBSERVES) || hasAnn(param.annotations(), OBSERVES_ASYNC)) {
                    errors.add("Constructor of " + className + " cannot have @Observes parameter");
                }
                if (hasAnn(param.annotations(), DISPOSES)) {
                    errors.add("Constructor of " + className + " cannot have @Disposes parameter");
                }
            }
        }

        // 2. @Observes + @Produces on the same method
        if (hasObserves && hasProduces) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot be both an observer and a producer");
        }

        // @Observes + @Disposes on same method
        if (hasObserves && hasDisposes) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot be both an observer and a disposer");
        }

        // @Produces + @Disposes on same method
        if (hasProduces && hasDisposes) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot be both a producer and a disposer");
        }

        // 5. Multiple @Observes parameters in the same method
        if (totalObservesCount > 1) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot have multiple observer parameters");
        }

        // 6. @Observes and @ObservesAsync in the same method
        if (observesCount > 0 && asyncObservesCount > 0) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot have both @Observes and @ObservesAsync parameters");
        }

        // Per-parameter checks
        for (var param : params) {
            boolean isObserver = hasAnn(param.annotations(), OBSERVES)
                    || hasAnn(param.annotations(), OBSERVES_ASYNC);
            boolean isDisposer = hasAnn(param.annotations(), DISPOSES);
            boolean isInject = hasAnn(param.annotations(), INJECT);

            // 3. @Observes + @Inject on the same parameter
            if (isObserver && isInject) {
                errors.add("Parameter in " + className + "." + method.name()
                        + " cannot be both @Observes and @Inject");
            }

            // 4 & 9. @Observes + @Disposes on the same parameter
            if (isObserver && isDisposer) {
                errors.add("Parameter in " + className + "." + method.name()
                        + " cannot be both @Observes and @Disposes");
            }
        }
    }

    private static boolean hasBeanDefiningAnnotation(ClassInfo classInfo, VaubanIndex index) {
        for (var ann : classInfo.annotations()) {
            if (BUILT_IN_SCOPES.contains(ann.name())) return true;
            if (isScopeAnnotation(ann.name(), index)) return true;
            var annClass = index.getClassByName(ann.name());
            if (annClass.isPresent() && annClass.get().hasAnnotation(
                    DotName.of("jakarta.enterprise.inject.Stereotype"))) return true;
        }
        return false;
    }

    private static boolean isScopeAnnotation(DotName name, VaubanIndex index) {
        if (BUILT_IN_SCOPES.contains(name)) {
            return true;
        }
        var annClass = index.getClassByName(name);
        if (annClass.isEmpty()) return false;
        return annClass.get().hasAnnotation(NORMAL_SCOPE)
                || annClass.get().hasAnnotation(SCOPE);
    }

    private static boolean hasAnn(List<AnnotationInfo> annotations, DotName name) {
        return annotations.stream().anyMatch(a -> a.name().equals(name));
    }
}
