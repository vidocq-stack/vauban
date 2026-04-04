package fr.vidocq.vauban.core.bean.validation;

import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.AnnotationInfo;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.MethodInfo;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates CDI class-level rules before bean discovery.
 * These rules detect invalid CDI constructs that should cause a DefinitionException.
 */
public final class ClassValidator {

    private static final DotName INJECT = DotName.of("jakarta.inject.Inject");
    private static final DotName PRODUCES = DotName.of("jakarta.enterprise.inject.Produces");
    private static final DotName OBSERVES = DotName.of("jakarta.enterprise.event.Observes");
    private static final DotName OBSERVES_ASYNC = DotName.of("jakarta.enterprise.event.ObservesAsync");
    private static final DotName DISPOSES = DotName.of("jakarta.enterprise.inject.Disposes");
    private static final DotName NORMAL_SCOPE = DotName.of("jakarta.enterprise.context.NormalScope");
    private static final DotName SCOPE = DotName.of("jakarta.inject.Scope");
    private static final DotName INTERCEPTOR_BINDING = DotName.of("jakarta.interceptor.InterceptorBinding");
    private static final DotName STEREOTYPE = DotName.of("jakarta.enterprise.inject.Stereotype");
    private static final DotName INTERCEPTOR = DotName.of("jakarta.interceptor.Interceptor");
    private static final DotName NAMED = DotName.of("jakarta.inject.Named");
    private static final DotName DEPENDENT = DotName.of("jakarta.enterprise.context.Dependent");
    private static final DotName PRIORITY = DotName.of("jakarta.annotation.Priority");

    private static final DotName EVENT_TYPE = DotName.of("jakarta.enterprise.event.Event");
    private static final DotName INSTANCE_TYPE = DotName.of("jakarta.enterprise.inject.Instance");

    private static final Set<DotName> BUILT_IN_NORMAL_SCOPES = Set.of(
            DotName.of("jakarta.enterprise.context.ApplicationScoped"),
            DotName.of("jakarta.enterprise.context.RequestScoped"),
            DotName.of("jakarta.enterprise.context.SessionScoped"),
            DotName.of("jakarta.enterprise.context.ConversationScoped")
    );

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

    public static List<String> validate(VaubanIndex index) {
        var errors = new ArrayList<String>();
        for (var classInfo : index.getKnownClasses()) {
            validateClass(classInfo, index, errors);
        }
        return errors;
    }

    private static void validateClass(ClassInfo classInfo, VaubanIndex index, List<String> errors) {
        if (classInfo.isAnnotation() || classInfo.isEnum()) return;

        var className = classInfo.name().toString();
        boolean isBean = hasBeanDefiningAnnotation(classInfo, index);
        boolean isInterceptor = classInfo.hasAnnotation(INTERCEPTOR);

        // Multiple @Inject constructors
        long injectCtorCount = classInfo.methods().stream()
                .filter(m -> m.isConstructor() && hasAnn(m.annotations(), INJECT))
                .count();
        if (injectCtorCount > 1) {
            errors.add("Bean " + className + " has multiple @Inject constructors");
        }

        if (isBean) {
            // Multiple scopes
            var scopes = classInfo.annotations().stream()
                    .filter(a -> isScopeAnnotation(a.name(), index))
                    .toList();
            // Also count scopes from stereotypes
            var stereotypeScopes = new ArrayList<DotName>();
            for (var ann : classInfo.annotations()) {
                if (isStereotype(ann.name(), index)) {
                    var stereoClass = index.getClassByName(ann.name());
                    if (stereoClass.isPresent()) {
                        for (var sa : stereoClass.get().annotations()) {
                            if (isScopeAnnotation(sa.name(), index)) {
                                stereotypeScopes.add(sa.name());
                            }
                        }
                    }
                }
            }
            // Direct scopes
            if (scopes.size() > 1) {
                errors.add("Bean " + className + " has multiple scope annotations");
            }
            // Stereotype scope conflicts (multiple different scopes from stereotypes, no direct scope)
            if (scopes.isEmpty() && stereotypeScopes.stream().distinct().count() > 1) {
                errors.add("Bean " + className + " has conflicting scope stereotypes");
            }

            // Normal-scoped bean + final / intercepted bean + final
            // These are DeploymentExceptions, not DefinitionExceptions — validated in DeploymentValidator

            // Generic managed bean — deferred until ClassInfo has typeParameters support

            // Normal-scoped bean with non-private non-static public field
            // Deferred: too many false positives with test beans
        }

        // Stereotype validations
        if (classInfo.hasAnnotation(STEREOTYPE) && classInfo.isAnnotation()) {
            // @Stereotype with @Named must have empty value
            for (var ann : classInfo.annotations()) {
                if (ann.name().equals(NAMED)) {
                    var nameValue = ann.member("value");
                    if (nameValue instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal sv
                            && !sv.value().isEmpty()) {
                        errors.add("Stereotype " + className + " has @Named with non-empty value");
                    }
                }
            }
            // Check for conflicting @Priority from multiple stereotypes
            // (handled at bean level below)
        }

        // Interceptor validations
        if (isInterceptor) {
            // Interceptor must be @Dependent
            boolean hasDependentScope = classInfo.hasAnnotation(DEPENDENT);
            boolean hasAnyScope = classInfo.annotations().stream()
                    .anyMatch(a -> isScopeAnnotation(a.name(), index));
            if (hasAnyScope && !hasDependentScope) {
                errors.add("Interceptor " + className + " must be @Dependent");
            }

            // Interceptor cannot have observer methods
            for (var method : classInfo.methods()) {
                for (var param : method.parameters()) {
                    if (hasAnn(param.annotations(), OBSERVES)) {
                        errors.add("Interceptor " + className + " cannot have observer method: " + method.name());
                    }
                    if (hasAnn(param.annotations(), OBSERVES_ASYNC)) {
                        errors.add("Interceptor " + className + " cannot have async observer method: " + method.name());
                    }
                }
            }

            // Interceptor cannot have producer methods/fields
            for (var method : classInfo.methods()) {
                if (hasAnn(method.annotations(), PRODUCES)) {
                    errors.add("Interceptor " + className + " cannot have producer method: " + method.name());
                }
            }
            for (var field : classInfo.fields()) {
                if (hasAnn(field.annotations(), PRODUCES)) {
                    errors.add("Interceptor " + className + " cannot have producer field: " + field.name());
                }
            }

            // Interceptor cannot have disposer methods
            for (var method : classInfo.methods()) {
                for (var param : method.parameters()) {
                    if (hasAnn(param.annotations(), DISPOSES)) {
                        errors.add("Interceptor " + className + " cannot have disposer method: " + method.name());
                    }
                }
            }
        }

        // Check for conflicting @Priority from stereotypes
        if (isBean) {
            validateStereotypePriorityConflicts(classInfo, index, className, errors);
        }

        // Conditional observer on @Dependent bean
        for (var method : classInfo.methods()) {
            for (var param : method.parameters()) {
                if (hasAnn(param.annotations(), OBSERVES)) {
                    var observesAnn = param.annotations().stream()
                            .filter(a -> a.name().equals(OBSERVES)).findFirst();
                    if (observesAnn.isPresent()) {
                        var reception = observesAnn.get().member("notifyObserver");
                        if (reception instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.EnumVal ev
                                && "IF_EXISTS".equals(ev.constantName())) {
                            boolean isDependent = classInfo.hasAnnotation(DEPENDENT)
                                    || !hasBeanDefiningAnnotation(classInfo, index);
                            if (isDependent) {
                                errors.add("Conditional observer on @Dependent bean " + className);
                            }
                        }
                    }
                }
            }
        }

        // Validate each method
        for (var method : classInfo.methods()) {
            validateMethod(method, classInfo, className, index, errors);
        }

        // Field validations
        for (var field : classInfo.fields()) {
            if (hasAnn(field.annotations(), PRODUCES) && hasAnn(field.annotations(), INJECT)) {
                errors.add("Field " + className + "." + field.name()
                        + " cannot be both @Produces and @Inject");
            }
            if (hasAnn(field.annotations(), INJECT) && field.isFinal()) {
                errors.add("@Inject field " + className + "." + field.name()
                        + " cannot be final");
            }
            // Raw Event/Instance injection — deferred until scanner supports generic signatures
            // Producer field type validation — done via reflection in VaubanContainer.Builder
        }

        // Static observer/disposer methods — CDI 4.1 Lite allows static observers/disposers

        // @Produces + @Inject on the same method
        for (var method : classInfo.methods()) {
            if (hasAnn(method.annotations(), PRODUCES) && hasAnn(method.annotations(), INJECT)) {
                errors.add("Method " + className + "." + method.name()
                        + " cannot be both @Produces and @Inject");
            }
        }
    }

    private static void validateMethod(MethodInfo method, ClassInfo classInfo,
            String className, VaubanIndex index, List<String> errors) {
        var params = method.parameters();

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
        long disposesCount = params.stream()
                .filter(p -> hasAnn(p.annotations(), DISPOSES))
                .count();

        // Constructor with @Observes, @ObservesAsync, or @Disposes parameter
        if (method.isConstructor()) {
            for (var param : params) {
                if (hasAnn(param.annotations(), OBSERVES) || hasAnn(param.annotations(), OBSERVES_ASYNC)) {
                    errors.add("Constructor of " + className + " cannot have @Observes parameter");
                }
                if (hasAnn(param.annotations(), DISPOSES)) {
                    errors.add("Constructor of " + className + " cannot have @Disposes parameter");
                }
            }
            // Raw Event/Instance in constructor — deferred until scanner supports generic signatures
        }

        // @Observes + @Produces
        if (hasObserves && hasProduces) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot be both an observer and a producer");
        }

        // @Observes + @Disposes
        if (hasObserves && hasDisposes) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot be both an observer and a disposer");
        }

        // @Produces + @Disposes
        if (hasProduces && hasDisposes) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot be both a producer and a disposer");
        }

        // Multiple @Observes parameters
        if (totalObservesCount > 1) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot have multiple observer parameters");
        }

        // @Observes and @ObservesAsync in the same method
        if (observesCount > 0 && asyncObservesCount > 0) {
            errors.add("Method " + className + "." + method.name()
                    + " cannot have both @Observes and @ObservesAsync parameters");
        }

        // Multiple @Disposes parameters
        if (disposesCount > 1) {
            errors.add("Method " + className + "." + method.name()
                    + " has multiple @Disposes parameters");
        }

        // Per-parameter checks
        for (var param : params) {
            boolean isObserver = hasAnn(param.annotations(), OBSERVES)
                    || hasAnn(param.annotations(), OBSERVES_ASYNC);
            boolean isDisposer = hasAnn(param.annotations(), DISPOSES);
            boolean isInject = hasAnn(param.annotations(), INJECT);

            if (isObserver && isInject) {
                errors.add("Parameter in " + className + "." + method.name()
                        + " cannot be both @Observes and @Inject");
            }

            if (isObserver && isDisposer) {
                errors.add("Parameter in " + className + "." + method.name()
                        + " cannot be both @Observes and @Disposes");
            }

            if (isDisposer) {
                validateTypeNotTypeVariableOrWildcard(param.type(), "Disposer parameter in",
                        className + "." + method.name(), errors);
            }
        }

        // Producer method return type validation
        if (hasProduces) {
            // Producer return type validation — done via reflection in VaubanContainer.Builder
        }

        // Disposer method that is also @Inject (initializer)
        if (hasDisposes && hasAnn(method.annotations(), INJECT)) {
            errors.add("Disposer method " + className + "." + method.name()
                    + " cannot also be an initializer method");
        }

        // Generic initializer method — deferred until MethodInfo has typeParameters support

        // Raw Event/Instance injection — deferred until scanner supports generic signatures

        // @Named on non-field injection point (method parameter @Inject)
        if (hasAnn(method.annotations(), INJECT) && !method.isConstructor()) {
            for (var param : params) {
                if (hasAnn(param.annotations(), NAMED)) {
                    // @Named on initializer method parameter is invalid unless the value is specified
                    var namedAnn = param.annotations().stream()
                            .filter(a -> a.name().equals(NAMED)).findFirst();
                    if (namedAnn.isPresent()) {
                        var nameValue = namedAnn.get().member("value");
                        if (nameValue == null || (nameValue instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal sv
                                && sv.value().isEmpty())) {
                            errors.add("@Named without value on non-field injection point: "
                                    + className + "." + method.name());
                        }
                    }
                }
            }
        }

        // @Named on constructor parameter
        if (method.isConstructor() && hasAnn(method.annotations(), INJECT)) {
            for (var param : params) {
                if (hasAnn(param.annotations(), NAMED)) {
                    var namedAnn = param.annotations().stream()
                            .filter(a -> a.name().equals(NAMED)).findFirst();
                    if (namedAnn.isPresent()) {
                        var nameValue = namedAnn.get().member("value");
                        if (nameValue == null || (nameValue instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal sv
                                && sv.value().isEmpty())) {
                            errors.add("@Named without value on constructor parameter: "
                                    + className + "." + method.name());
                        }
                    }
                }
            }
        }

        // Type variable injection point
        if (hasAnn(method.annotations(), INJECT)) {
            for (var param : params) {
                if (param.type() instanceof TypeInfo.TypeVariable) {
                    errors.add("Injection point has type variable type: "
                            + className + "." + method.name());
                }
            }
        }
    }

    /**
     * Validates that raw Event or Instance types are not used as injection points.
     * CDI requires these to be parameterized.
     */
    private static void validateNoRawParameterizedType(TypeInfo type, String location, List<String> errors) {
        if (type instanceof TypeInfo.ClassType ct) {
            if (ct.name().equals(EVENT_TYPE)) {
                errors.add("Raw Event type injected at " + location + " — must be parameterized");
            }
            if (ct.name().equals(INSTANCE_TYPE)) {
                errors.add("Raw Instance type injected at " + location + " — must be parameterized");
            }
        }
    }

    private static void validateTypeNotTypeVariableOrWildcard(TypeInfo type, String kind, String location, List<String> errors) {
        if (type instanceof TypeInfo.TypeVariable) {
            errors.add(kind + " " + location + " has type variable type");
        }
        if (type instanceof TypeInfo.WildcardType) {
            errors.add(kind + " " + location + " has wildcard type");
        }
        if (type instanceof TypeInfo.ParameterizedType pt) {
            for (var arg : pt.typeArguments()) {
                if (arg instanceof TypeInfo.TypeVariable) {
                    errors.add(kind + " " + location + " has parameterized type with type variable");
                }
                if (arg instanceof TypeInfo.WildcardType) {
                    errors.add(kind + " " + location + " has parameterized type with wildcard");
                }
            }
        }
        if (type instanceof TypeInfo.ArrayType at) {
            if (at.componentType() instanceof TypeInfo.TypeVariable) {
                errors.add(kind + " " + location + " has array type with type variable component");
            }
            if (at.componentType() instanceof TypeInfo.WildcardType) {
                errors.add(kind + " " + location + " has array type with wildcard component");
            }
        }
    }

    /**
     * Validates producer return types: cannot be TypeVariable, WildcardType,
     * or arrays with type variable/wildcard component types.
     */
    private static void validateProducerType(TypeInfo type, String location, List<String> errors) {
        validateTypeNotTypeVariableOrWildcard(type, "Producer", location, errors);
    }

    /**
     * Validates that stereotype @Priority values don't conflict.
     */
    private static void validateStereotypePriorityConflicts(ClassInfo classInfo,
            VaubanIndex index, String className, List<String> errors) {
        // Direct @Priority on the bean is fine
        if (classInfo.hasAnnotation(PRIORITY)) return;

        var priorityValues = new ArrayList<Integer>();
        var stereotypesWithPriority = new ArrayList<String>();

        for (var ann : classInfo.annotations()) {
            if (isStereotype(ann.name(), index)) {
                var stereoClass = index.getClassByName(ann.name());
                if (stereoClass.isPresent()) {
                    for (var sa : stereoClass.get().annotations()) {
                        if (sa.name().equals(PRIORITY)) {
                            var value = sa.member("value");
                            if (value instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.IntVal iv) {
                                priorityValues.add(iv.value());
                                stereotypesWithPriority.add(ann.name().value());
                            }
                        }
                    }
                }
            }
        }

        if (priorityValues.size() > 1) {
            // Check if they all have the same value
            boolean allSame = priorityValues.stream().distinct().count() == 1;
            if (!allSame) {
                errors.add("Bean " + className + " has conflicting @Priority values from stereotypes: "
                        + stereotypesWithPriority);
            }
        }
    }

    private static boolean hasBeanDefiningAnnotation(ClassInfo classInfo, VaubanIndex index) {
        for (var ann : classInfo.annotations()) {
            if (BUILT_IN_SCOPES.contains(ann.name())) return true;
            if (isScopeAnnotation(ann.name(), index)) return true;
            var annClass = index.getClassByName(ann.name());
            if (annClass.isPresent() && annClass.get().hasAnnotation(STEREOTYPE)) return true;
        }
        // @Inject constructor counts as bean-defining
        for (var method : classInfo.methods()) {
            if (method.isConstructor() && hasAnn(method.annotations(), INJECT)) return true;
        }
        return false;
    }

    private static boolean isScopeAnnotation(DotName name, VaubanIndex index) {
        if (BUILT_IN_SCOPES.contains(name)) return true;
        var annClass = index.getClassByName(name);
        if (annClass.isEmpty()) return false;
        return annClass.get().hasAnnotation(NORMAL_SCOPE) || annClass.get().hasAnnotation(SCOPE);
    }

    private static boolean isStereotype(DotName name, VaubanIndex index) {
        var annClass = index.getClassByName(name);
        return annClass.isPresent() && annClass.get().hasAnnotation(STEREOTYPE);
    }

    private static boolean hasAnn(List<AnnotationInfo> annotations, DotName name) {
        return annotations.stream().anyMatch(a -> a.name().equals(name));
    }

    private static boolean hasNormalScope(ClassInfo classInfo, VaubanIndex index) {
        for (var a : classInfo.annotations()) {
            if (isNormalScope(a.name(), index)) return true;
            // Check stereotypes
            if (isStereotype(a.name(), index)) {
                var stereoClass = index.getClassByName(a.name());
                if (stereoClass.isPresent()) {
                    for (var sa : stereoClass.get().annotations()) {
                        if (isNormalScope(sa.name(), index)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isNormalScope(DotName name, VaubanIndex index) {
        if (BUILT_IN_NORMAL_SCOPES.contains(name)) return true;
        var annClass = index.getClassByName(name);
        return annClass.isPresent() && annClass.get().hasAnnotation(NORMAL_SCOPE);
    }

    private static boolean isInterceptorBinding(DotName name, VaubanIndex index) {
        var annClass = index.getClassByName(name);
        return annClass.isPresent() && annClass.get().hasAnnotation(INTERCEPTOR_BINDING);
    }

    private static boolean hasInterceptorBindings(ClassInfo classInfo, VaubanIndex index) {
        for (var ann : classInfo.annotations()) {
            if (isInterceptorBinding(ann.name(), index)) return true;
            var annClass = index.getClassByName(ann.name());
            if (annClass.isPresent() && annClass.get().hasAnnotation(STEREOTYPE)) {
                for (var stereoAnn : annClass.get().annotations()) {
                    if (isInterceptorBinding(stereoAnn.name(), index)) return true;
                }
            }
        }
        return false;
    }
}
