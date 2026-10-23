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

import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates CDI class-level rules before bean discovery.
 * These rules detect invalid CDI constructs that should cause a DefinitionException.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class ClassValidator {

    private static final DotName INJECT = DotName.of("jakarta.inject.Inject");
    private static final DotName PRODUCES = DotName.of("jakarta.enterprise.inject.Produces");
    private static final DotName OBSERVES = DotName.of("jakarta.enterprise.event.Observes");
    private static final DotName OBSERVES_ASYNC = DotName.of("jakarta.enterprise.event.ObservesAsync");
    private static final DotName DISPOSES = DotName.of("jakarta.enterprise.inject.Disposes");
    private static final DotName NORMAL_SCOPE = DotName.of("jakarta.enterprise.context.NormalScope");
    private static final DotName SCOPE = DotName.of("jakarta.inject.Scope");
    private static final DotName STEREOTYPE = DotName.of("jakarta.enterprise.inject.Stereotype");
    private static final DotName INTERCEPTOR = DotName.of("jakarta.interceptor.Interceptor");
    private static final DotName NAMED = DotName.of("jakarta.inject.Named");
    private static final DotName DEPENDENT = DotName.of("jakarta.enterprise.context.Dependent");
    private static final DotName PRIORITY = DotName.of("jakarta.annotation.Priority");

    private static final Set<DotName> BUILT_IN_SCOPES = Set.of(
            DotName.of("jakarta.enterprise.context.ApplicationScoped"),
            DotName.of("jakarta.enterprise.context.RequestScoped"),
            DotName.of("jakarta.enterprise.context.Dependent"),
            DotName.of("jakarta.enterprise.context.SessionScoped"),
            DotName.of("jakarta.enterprise.context.ConversationScoped"),
            DotName.of("jakarta.inject.Singleton")
    );

    private static final String PREFIX_BEAN = "Bean ";
    private static final String PREFIX_INTERCEPTOR = "Interceptor ";
    private static final String PREFIX_METHOD = "Method ";
    private static final String MEMBER_VALUE = "value";

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

        // Multiple @Inject constructors
        long injectCtorCount = classInfo.methods().stream()
                .filter(m -> m.isConstructor() && hasAnn(m.annotations(), INJECT))
                .count();
        if (injectCtorCount > 1) {
            errors.add(PREFIX_BEAN + className + " has multiple @Inject constructors");
        }

        if (isBean) {
            validateScopes(classInfo, index, className, errors);
            // Normal-scoped bean + final / intercepted bean + final
            // These are DeploymentExceptions, not DefinitionExceptions — validated in DeploymentValidator

            // Generic managed bean — deferred until ClassInfo has typeParameters support

            // Normal-scoped bean with non-private non-static public field
            // Deferred: too many false positives with test beans
        }

        // Stereotype validations
        if (classInfo.hasAnnotation(STEREOTYPE) && classInfo.isAnnotation()) {
            validateStereotypeName(classInfo, className, errors);
        }

        // Interceptor validations
        if (classInfo.hasAnnotation(INTERCEPTOR)) {
            validateInterceptor(classInfo, index, className, errors);
        }

        // Check for conflicting @Priority from stereotypes
        if (isBean) {
            validateStereotypePriorityConflicts(classInfo, index, className, errors);
        }

        validateConditionalObservers(classInfo, index, className, errors);

        // Validate each method
        for (var method : classInfo.methods()) {
            validateMethod(method, className, errors);
        }

        validateFields(classInfo, className, errors);

        // Static observer/disposer methods — CDI 4.1 Lite allows static observers/disposers

        // @Produces + @Inject on the same method
        for (var method : classInfo.methods()) {
            if (hasAnn(method.annotations(), PRODUCES) && hasAnn(method.annotations(), INJECT)) {
                errors.add(PREFIX_METHOD + className + "." + method.name()
                        + " cannot be both @Produces and @Inject");
            }
        }
    }

    /** Multiple scopes, declared directly or contributed by conflicting stereotypes. */
    private static void validateScopes(ClassInfo classInfo, VaubanIndex index, String className,
            List<String> errors) {
        var scopes = classInfo.annotations().stream()
                .filter(a -> isScopeAnnotation(a.name(), index))
                .toList();
        // Also count scopes from stereotypes
        var stereotypeScopes = new ArrayList<DotName>();
        for (var ann : classInfo.annotations()) {
            if (isStereotype(ann.name(), index)) {
                index.getClassByName(ann.name())
                        .ifPresent(stereoClass -> addScopes(stereoClass, index, stereotypeScopes));
            }
        }
        // Direct scopes
        if (scopes.size() > 1) {
            errors.add(PREFIX_BEAN + className + " has multiple scope annotations");
        }
        // Stereotype scope conflicts (multiple different scopes from stereotypes, no direct scope)
        if (scopes.isEmpty() && stereotypeScopes.stream().distinct().count() > 1) {
            errors.add(PREFIX_BEAN + className + " has conflicting scope stereotypes");
        }
    }

    private static void addScopes(ClassInfo stereoClass, VaubanIndex index, List<DotName> scopes) {
        for (var sa : stereoClass.annotations()) {
            if (isScopeAnnotation(sa.name(), index)) {
                scopes.add(sa.name());
            }
        }
    }

    /**
     * {@code @Stereotype} with {@code @Named} must have an empty value. Conflicting {@code @Priority}
     * values from several stereotypes are checked at bean level.
     */
    private static void validateStereotypeName(ClassInfo classInfo, String className, List<String> errors) {
        for (var ann : classInfo.annotations()) {
            if (ann.name().equals(NAMED)
                    && ann.member(MEMBER_VALUE) instanceof AnnotationValue.StringVal(var name)
                    && !name.isEmpty()) {
                errors.add("Stereotype " + className + " has @Named with non-empty value");
            }
        }
    }

    private static void validateInterceptor(ClassInfo classInfo, VaubanIndex index, String className,
            List<String> errors) {
        // Interceptor must be @Dependent
        boolean hasDependentScope = classInfo.hasAnnotation(DEPENDENT);
        boolean hasAnyScope = classInfo.annotations().stream()
                .anyMatch(a -> isScopeAnnotation(a.name(), index));
        if (hasAnyScope && !hasDependentScope) {
            errors.add(PREFIX_INTERCEPTOR + className + " must be @Dependent");
        }

        // Interceptor cannot have observer methods
        for (var method : classInfo.methods()) {
            for (var param : method.parameters()) {
                if (hasAnn(param.annotations(), OBSERVES)) {
                    errors.add(PREFIX_INTERCEPTOR + className + " cannot have observer method: " + method.name());
                }
                if (hasAnn(param.annotations(), OBSERVES_ASYNC)) {
                    errors.add(PREFIX_INTERCEPTOR + className + " cannot have async observer method: " + method.name());
                }
            }
        }

        // Interceptor cannot have producer methods/fields
        for (var method : classInfo.methods()) {
            if (hasAnn(method.annotations(), PRODUCES)) {
                errors.add(PREFIX_INTERCEPTOR + className + " cannot have producer method: " + method.name());
            }
        }
        for (var field : classInfo.fields()) {
            if (hasAnn(field.annotations(), PRODUCES)) {
                errors.add(PREFIX_INTERCEPTOR + className + " cannot have producer field: " + field.name());
            }
        }

        // Interceptor cannot have disposer methods
        for (var method : classInfo.methods()) {
            for (var param : method.parameters()) {
                if (hasAnn(param.annotations(), DISPOSES)) {
                    errors.add(PREFIX_INTERCEPTOR + className + " cannot have disposer method: " + method.name());
                }
            }
        }
    }

    /** Conditional observer on @Dependent bean. */
    private static void validateConditionalObservers(ClassInfo classInfo, VaubanIndex index,
            String className, List<String> errors) {
        for (var method : classInfo.methods()) {
            for (var param : method.parameters()) {
                if (isConditionalObserver(param.annotations())
                        && (classInfo.hasAnnotation(DEPENDENT) || !hasBeanDefiningAnnotation(classInfo, index))) {
                    errors.add("Conditional observer on @Dependent bean " + className);
                }
            }
        }
    }

    /** An {@code @Observes(notifyObserver = IF_EXISTS)} parameter. */
    private static boolean isConditionalObserver(List<AnnotationInfo> parameterAnnotations) {
        var observesAnn = parameterAnnotations.stream()
                .filter(a -> a.name().equals(OBSERVES)).findFirst();
        return observesAnn.isPresent()
                && observesAnn.get().member("notifyObserver") instanceof AnnotationValue.EnumVal ev
                && "IF_EXISTS".equals(ev.constantName());
    }

    private static void validateFields(ClassInfo classInfo, String className, List<String> errors) {
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
    }

    private static void validateMethod(MethodInfo method,
            String className, List<String> errors) {
        var params = method.parameters();
        var location = className + "." + method.name();

        // Constructor with @Observes, @ObservesAsync, or @Disposes parameter
        if (method.isConstructor()) {
            validateConstructorParameters(params, className, errors);
            // Raw Event/Instance in constructor — deferred until scanner supports generic signatures
        }

        validateMethodRoles(method, location, errors);

        // Per-parameter checks
        for (var param : params) {
            validateParameter(param, location, errors);
        }

        // Producer method return type validation — done via reflection in VaubanContainer.Builder

        // Disposer method that is also @Inject (initializer)
        boolean hasDisposes = params.stream().anyMatch(p -> hasAnn(p.annotations(), DISPOSES));
        if (hasDisposes && hasAnn(method.annotations(), INJECT)) {
            errors.add("Disposer method " + location + " cannot also be an initializer method");
        }

        // Generic initializer method — deferred until MethodInfo has typeParameters support

        // Raw Event/Instance injection — deferred until scanner supports generic signatures

        validateInjectionParameters(method, location, errors);
    }

    private static void validateConstructorParameters(List<ParameterInfo> params, String className,
            List<String> errors) {
        for (var param : params) {
            if (hasAnn(param.annotations(), OBSERVES) || hasAnn(param.annotations(), OBSERVES_ASYNC)) {
                errors.add("Constructor of " + className + " cannot have @Observes parameter");
            }
            if (hasAnn(param.annotations(), DISPOSES)) {
                errors.add("Constructor of " + className + " cannot have @Disposes parameter");
            }
        }
    }

    /** A method is at most one of an observer, a producer and a disposer, each with one such parameter. */
    private static void validateMethodRoles(MethodInfo method, String location, List<String> errors) {
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
        long disposesCount = params.stream()
                .filter(p -> hasAnn(p.annotations(), DISPOSES))
                .count();
        boolean hasDisposes = disposesCount > 0;

        // @Observes + @Produces
        if (hasObserves && hasProduces) {
            errors.add(PREFIX_METHOD + location + " cannot be both an observer and a producer");
        }

        // @Observes + @Disposes
        if (hasObserves && hasDisposes) {
            errors.add(PREFIX_METHOD + location + " cannot be both an observer and a disposer");
        }

        // @Produces + @Disposes
        if (hasProduces && hasDisposes) {
            errors.add(PREFIX_METHOD + location + " cannot be both a producer and a disposer");
        }

        // Multiple @Observes parameters
        if (totalObservesCount > 1) {
            errors.add(PREFIX_METHOD + location + " cannot have multiple observer parameters");
        }

        // @Observes and @ObservesAsync in the same method
        if (observesCount > 0 && asyncObservesCount > 0) {
            errors.add(PREFIX_METHOD + location + " cannot have both @Observes and @ObservesAsync parameters");
        }

        // Multiple @Disposes parameters
        if (disposesCount > 1) {
            errors.add(PREFIX_METHOD + location + " has multiple @Disposes parameters");
        }
    }

    private static void validateParameter(ParameterInfo param, String location, List<String> errors) {
        boolean isObserver = hasAnn(param.annotations(), OBSERVES)
                || hasAnn(param.annotations(), OBSERVES_ASYNC);
        boolean isDisposer = hasAnn(param.annotations(), DISPOSES);
        boolean isInject = hasAnn(param.annotations(), INJECT);

        if (isObserver && isInject) {
            errors.add("Parameter in " + location + " cannot be both @Observes and @Inject");
        }

        if (isObserver && isDisposer) {
            errors.add("Parameter in " + location + " cannot be both @Observes and @Disposes");
        }

        if (isDisposer) {
            validateTypeNotTypeVariableOrWildcard(param.type(), "Disposer parameter in", location, errors);
        }
    }

    /** {@code @Named} without a value and type variables on the parameters of an {@code @Inject} method. */
    private static void validateInjectionParameters(MethodInfo method, String location, List<String> errors) {
        if (!hasAnn(method.annotations(), INJECT)) {
            return;
        }
        var params = method.parameters();
        // @Named on non-field injection point: on an initializer method parameter it is invalid
        // unless the value is specified, and so it is on a constructor parameter
        var namedMessage = method.isConstructor()
                ? "@Named without value on constructor parameter: "
                : "@Named without value on non-field injection point: ";
        for (var param : params) {
            if (hasNamedWithoutValue(param)) {
                errors.add(namedMessage + location);
            }
        }

        // Type variable injection point
        for (var param : params) {
            if (param.type() instanceof TypeInfo.TypeVariable) {
                errors.add("Injection point has type variable type: " + location);
            }
        }
    }

    private static boolean hasNamedWithoutValue(ParameterInfo param) {
        var namedAnn = param.annotations().stream()
                .filter(a -> a.name().equals(NAMED)).findFirst();
        if (namedAnn.isEmpty()) {
            return false;
        }
        var nameValue = namedAnn.get().member(MEMBER_VALUE);
        return nameValue == null
                || (nameValue instanceof AnnotationValue.StringVal(var name) && name.isEmpty());
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
                            var value = sa.member(MEMBER_VALUE);
                            if (value instanceof AnnotationValue.IntVal(var priority)) {
                                priorityValues.add(priority);
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
                errors.add(PREFIX_BEAN + className + " has conflicting @Priority values from stereotypes: "
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

}
