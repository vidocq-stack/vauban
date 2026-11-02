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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The definition rules the container used to check by reflecting on each bean class ({@code ReflectionValidator}),
 * checked on the index instead (VAU-PERF-008): same rules, same messages.
 *
 * <p>It needs the generic member types ({@link ClassInfo#genericMembers()}) and the class's superclasses in the
 * index. When it cannot decide — erased member types, a superclass or a supertype the index does not hold where the
 * answer depends on it — {@link #validate} returns empty, and the caller validates that class by reflection, as
 * before. Annotation types are described by {@link AnnotationTypes}.
 */
@SuppressWarnings("java:S3776") // mirrors ReflectionValidator rule by rule
public final class IndexClassValidator {

    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final DotName INJECT = DotName.of("jakarta.inject.Inject");
    private static final DotName PRODUCES = DotName.of("jakarta.enterprise.inject.Produces");
    private static final DotName TYPED = DotName.of("jakarta.enterprise.inject.Typed");
    private static final DotName NAMED = DotName.of("jakarta.inject.Named");
    private static final DotName PRIORITY = DotName.of("jakarta.annotation.Priority");
    private static final DotName OBSERVES = DotName.of("jakarta.enterprise.event.Observes");
    private static final DotName OBSERVES_ASYNC = DotName.of("jakarta.enterprise.event.ObservesAsync");
    private static final DotName DISPOSES = DotName.of("jakarta.enterprise.inject.Disposes");
    private static final DotName EVENT = DotName.of("jakarta.enterprise.event.Event");
    private static final DotName INSTANCE = DotName.of("jakarta.enterprise.inject.Instance");
    private static final DotName INJECTION_POINT = DotName.of("jakarta.enterprise.inject.spi.InjectionPoint");
    private static final DotName DEPENDENT = DotName.of("jakarta.enterprise.context.Dependent");
    private static final DotName SINGLETON = DotName.of("jakarta.inject.Singleton");
    private static final String VALUE = "value";

    private static final String TYPED_VALUE = "@Typed value ";
    private static final String NOT_A_LEGAL_BEAN_TYPE = " is not a legal bean type";
    private static final String PRODUCER = "Producer ";
    private static final String PRODUCER_METHOD = "Producer method ";
    private static final String BEAN = "Bean ";

    /** The index cannot decide: the class is validated by reflection. */
    private static final class Undecidable extends RuntimeException {
        Undecidable() {
            super(null, null, false, false);
        }
    }

    private final VaubanIndex index;
    private final AnnotationTypes types;
    private final boolean compilerElements;

    /**
     * @param loader                   loads an annotation type the index does not hold, or returns {@code null}
     * @param customInterceptorBinding the annotation types an extension registered as interceptor bindings
     */
    public IndexClassValidator(VaubanIndex index, Function<String, Class<?>> loader,
            Predicate<DotName> customInterceptorBinding) {
        this(index, new AnnotationTypes(index, loader, customInterceptorBinding), false);
    }

    private IndexClassValidator(VaubanIndex index, AnnotationTypes types, boolean compilerElements) {
        this.index = index;
        this.types = types;
        this.compilerElements = compilerElements;
    }

    /**
     * For the processor: its index is built from the compiler's elements, whose member types are the generic ones
     * the source declares, so it validates them at build. An annotation type the index does not hold is described
     * by {@code describer}, from the compiler's elements too: nothing is loaded.
     */
    public static IndexClassValidator forCompilerElements(VaubanIndex index,
            Function<DotName, Optional<ClassInfo>> describer) {
        return new IndexClassValidator(index, new AnnotationTypes(index, describer, name -> null, name -> false),
                true);
    }

    /** The definition errors of {@code classInfo}, or empty when the index cannot decide them. */
    public Optional<List<String>> validate(ClassInfo classInfo) {
        if (!classInfo.genericMembers() && !compilerElements) {
            return Optional.empty();
        }
        if (classInfo.isInterface() || classInfo.isAnnotation() || classInfo.isEnum()) {
            return Optional.of(List.of());
        }
        try {
            var errors = new ArrayList<String>();
            var annotations = annotations(classInfo);
            var beanDefining = hasBeanDefiningAnnotation(classInfo, annotations);
            validateStereotypeNamed(annotations, errors);
            validateGenericBeanScope(classInfo, annotations, beanDefining, errors);
            validateNamedOnParameters(classInfo, beanDefining, errors);
            validateNormalScopedPublicFields(classInfo, annotations, beanDefining, errors);
            validateTypedValues(classInfo, errors);
            validateInjectFields(classInfo, errors);
            validateMethods(classInfo, errors);
            validateProducerFields(classInfo, errors);
            validateConstructors(classInfo, errors);
            validateStereotypePriorities(classInfo, annotations, beanDefining, errors);
            validateStereotypeScopes(classInfo, annotations, beanDefining, errors);
            validateInterceptorBindings(classInfo, annotations, beanDefining, errors);
            return Optional.of(errors);
        } catch (Undecidable _) {
            return Optional.empty();
        }
    }

    // ---- Class.getAnnotations(): declared, then the @Inherited ones of the superclasses ----

    /**
     * In {@code Class.getAnnotations()} order: the inherited annotations of the superclass first, then the declared
     * ones, a declared annotation taking the place of an inherited one of its type.
     */
    private List<AnnotationInfo> annotations(ClassInfo classInfo) {
        var byType = new LinkedHashMap<DotName, AnnotationInfo>();
        var superName = classInfo.superName();
        if (superName != null && !superName.equals(OBJECT)) {
            var superClass = index.getClassByName(superName).orElseThrow(Undecidable::new);
            for (var annotation : annotations(superClass)) {
                if (types.meta(annotation.name()).map(AnnotationTypes.Meta::inherited).orElse(false)) {
                    byType.put(annotation.name(), annotation);
                }
            }
        }
        for (var annotation : classInfo.annotations()) {
            if (types.meta(annotation.name()).isPresent()) {
                byType.put(annotation.name(), annotation);
            }
        }
        return List.copyOf(byType.values());
    }

    /** An annotation type's own annotations, those whose type resolves. */
    private List<AnnotationInfo> metaAnnotations(DotName annotationType) {
        return types.meta(annotationType).map(AnnotationTypes.Meta::annotations).orElse(List.of()).stream()
                .filter(a -> types.meta(a.name()).isPresent())
                .toList();
    }

    private boolean hasBeanDefiningAnnotation(ClassInfo classInfo, List<AnnotationInfo> annotations) {
        for (var annotation : annotations) {
            if (types.isScope(annotation.name()) || types.isStereotype(annotation.name())) return true;
        }
        return constructors(classInfo).anyMatch(c -> has(c.annotations(), INJECT));
    }

    // ---- The rules, in ReflectionValidator's order ----

    private void validateStereotypeNamed(List<AnnotationInfo> annotations, List<String> errors) {
        for (var annotation : annotations) {
            if (!types.isStereotype(annotation.name())) continue;
            var named = types.meta(annotation.name()).flatMap(m -> m.annotation(NAMED));
            var value = named.map(n -> stringValue(n.member(VALUE))).orElse("");
            if (named.isPresent() && !value.isEmpty()) {
                errors.add("Stereotype " + annotation.name().value() + " has @Named with non-empty value '" + value
                        + "'");
            }
        }
    }

    private void validateGenericBeanScope(ClassInfo classInfo, List<AnnotationInfo> annotations,
            boolean beanDefining, List<String> errors) {
        if (beanDefining && !classInfo.typeParameters().isEmpty()) {
            boolean nonDependent = annotations.stream()
                    .anyMatch(a -> types.isNormalScope(a.name()) || a.name().equals(SINGLETON));
            if (nonDependent) {
                errors.add("Managed bean " + classInfo.name().value() + " has type parameters and is not @Dependent");
            }
        }
    }

    private void validateNamedOnParameters(ClassInfo classInfo, boolean beanDefining, List<String> errors) {
        var className = classInfo.name().value();
        if (beanDefining) {
            for (var method : methods(classInfo)) {
                if (has(method.annotations(), INJECT)) {
                    for (var parameter : method.parameters()) {
                        if (namedWithoutValue(parameter)) {
                            errors.add("@Named without value on initializer parameter: " + className + "."
                                    + method.name());
                        }
                    }
                }
            }
            for (var ctor : constructors(classInfo).toList()) {
                if (has(ctor.annotations(), INJECT)) {
                    for (var parameter : ctor.parameters()) {
                        if (namedWithoutValue(parameter)) {
                            errors.add("@Named without value on constructor parameter: " + className);
                        }
                    }
                }
            }
        }
        for (var method : methods(classInfo)) {
            if (has(method.annotations(), PRODUCES)) {
                for (var parameter : method.parameters()) {
                    if (namedWithoutValue(parameter)) {
                        errors.add("@Named without value on producer method parameter: " + className + "."
                                + method.name());
                    }
                }
            }
            if (method.parameters().stream().anyMatch(IndexClassValidator::isObservesOrDisposes)) {
                for (var parameter : method.parameters()) {
                    if (!isObservesOrDisposes(parameter) && namedWithoutValue(parameter)) {
                        errors.add("@Named without value on observer/disposer method parameter: " + className + "."
                                + method.name());
                    }
                }
            }
        }
    }

    private boolean namedWithoutValue(ParameterInfo parameter) {
        return parameter.annotations().stream()
                .filter(a -> a.name().equals(NAMED))
                .anyMatch(a -> stringValue(a.member(VALUE)).isEmpty());
    }

    private static boolean isObserves(ParameterInfo parameter) {
        return has(parameter.annotations(), OBSERVES) || has(parameter.annotations(), OBSERVES_ASYNC);
    }

    private static boolean isDisposes(ParameterInfo parameter) {
        return has(parameter.annotations(), DISPOSES);
    }

    private static boolean isObservesOrDisposes(ParameterInfo parameter) {
        return isObserves(parameter) || isDisposes(parameter);
    }

    private void validateNormalScopedPublicFields(ClassInfo classInfo, List<AnnotationInfo> annotations,
            boolean beanDefining, List<String> errors) {
        if (!beanDefining || annotations.stream().noneMatch(a -> types.isNormalScope(a.name()))) return;
        for (var field : classInfo.fields()) {
            if (field.isPublic() && !field.isStatic() && !has(field.annotations(), PRODUCES)) {
                errors.add("Normal-scoped bean " + classInfo.name().value() + " has non-static public field '"
                        + field.name() + "'");
                break;
            }
        }
    }

    private void validateTypedValues(ClassInfo classInfo, List<String> errors) {
        var className = classInfo.name().value();
        var classTyped = annotation(classInfo.annotations(), TYPED);
        if (classTyped.isPresent()) {
            for (var t : classValues(classTyped.get())) {
                if (!assignable(t, classInfo.name())) {
                    errors.add(TYPED_VALUE + t.value() + " on " + className + NOT_A_LEGAL_BEAN_TYPE
                            + " (not a supertype)");
                }
            }
        }
        for (var method : methods(classInfo)) {
            if (has(method.annotations(), PRODUCES) && has(method.annotations(), TYPED)) {
                validateTypedProducer(annotation(method.annotations(), TYPED).orElseThrow(), method.returnType(),
                        "producer method " + className + "." + method.name(), errors);
            }
        }
        for (var field : classInfo.fields()) {
            if (has(field.annotations(), PRODUCES) && has(field.annotations(), TYPED)) {
                validateTypedProducer(annotation(field.annotations(), TYPED).orElseThrow(), field.type(),
                        "producer field " + className + "." + field.name(), errors);
            }
        }
    }

    private void validateTypedProducer(AnnotationInfo typed, TypeInfo producedType, String producer,
            List<String> errors) {
        for (var t : classValues(typed)) {
            if (t.equals(OBJECT)) continue;
            var raw = rawClass(producedType);
            if (!assignable(t, raw)) {
                errors.add(TYPED_VALUE + t.value() + " on " + producer + NOT_A_LEGAL_BEAN_TYPE);
            }
        }
    }

    private void validateInjectFields(ClassInfo classInfo, List<String> errors) {
        for (var field : classInfo.fields()) {
            if (has(field.annotations(), INJECT)) {
                validateNoRawParameterized(field.type(), classInfo.name().value() + "." + field.name(), errors);
            }
        }
    }

    private void validateMethods(ClassInfo classInfo, List<String> errors) {
        var className = classInfo.name().value();
        for (var method : methods(classInfo)) {
            boolean producer = has(method.annotations(), PRODUCES);
            var location = className + "." + method.name();
            if (producer) {
                validateProducerMethod(method, location, errors);
            }
            if (has(method.annotations(), INJECT)) {
                if (!method.typeParameters().isEmpty()) {
                    errors.add("Initializer method " + location + " cannot declare type parameters");
                }
                var parameters = method.parameters();
                for (int i = 0; i < parameters.size(); i++) {
                    validateNoRawParameterized(parameters.get(i).type(), location + " param " + i, errors);
                }
            }
            if (method.parameters().stream().anyMatch(IndexClassValidator::isObserves)) {
                for (var parameter : method.parameters()) {
                    if (!isObserves(parameter)) {
                        validateNoRawParameterized(parameter.type(), location + " observer param", errors);
                    }
                }
            }
            if (method.parameters().stream().anyMatch(IndexClassValidator::isDisposes)) {
                for (var parameter : method.parameters()) {
                    if (!isDisposes(parameter)) {
                        validateNoRawParameterized(parameter.type(), location + " disposer param", errors);
                    }
                    if (INJECTION_POINT.equals(rawClass(parameter.type()))) {
                        errors.add("Disposer method " + location + " must not have InjectionPoint parameter");
                    }
                }
            }
            if (producer) {
                for (var parameter : method.parameters()) {
                    validateNoRawParameterized(parameter.type(), location + " producer param", errors);
                }
            }
        }
    }

    private void validateProducerMethod(MethodInfo method, String location, List<String> errors) {
        var type = method.returnType();
        validateProducerReturnType(type, location, errors);
        validateNoMultipleScopes(method.annotations(), PRODUCER_METHOD + location, errors);
        if (containsTypeVariable(type) && hasNonDependentScope(method.annotations())) {
            errors.add(PRODUCER_METHOD + location + " has TypeVariable return type and non-@Dependent scope");
        }
        if (containsWildcard(type)) {
            errors.add(PRODUCER_METHOD + location + " has wildcard type parameter in return type");
        }
    }

    private void validateProducerFields(ClassInfo classInfo, List<String> errors) {
        for (var field : classInfo.fields()) {
            if (!has(field.annotations(), PRODUCES)) continue;
            var location = classInfo.name().value() + "." + field.name();
            validateProducerReturnType(field.type(), location, errors);
            validateNoMultipleScopes(field.annotations(), "Producer field " + location, errors);
            if (containsTypeVariable(field.type()) && hasNonDependentScope(field.annotations())) {
                errors.add("Producer field " + location + " has TypeVariable type and non-@Dependent scope");
            }
        }
    }

    private void validateConstructors(ClassInfo classInfo, List<String> errors) {
        for (var ctor : constructors(classInfo).toList()) {
            if (!has(ctor.annotations(), INJECT)) continue;
            var parameters = ctor.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                validateNoRawParameterized(parameters.get(i).type(),
                        classInfo.name().value() + " constructor param " + i, errors);
            }
        }
    }

    private void validateStereotypePriorities(ClassInfo classInfo, List<AnnotationInfo> annotations,
            boolean beanDefining, List<String> errors) {
        if (!beanDefining) return;
        var priorities = new LinkedHashSet<Integer>();
        collectStereotypePriorities(annotations, priorities, new HashSet<>());
        if (priorities.size() > 1) {
            errors.add(BEAN + classInfo.name().value() + " has conflicting stereotype priorities: " + priorities);
        }
    }

    private void collectStereotypePriorities(List<AnnotationInfo> annotations, Set<Integer> priorities,
            Set<DotName> visited) {
        for (var annotation : annotations) {
            var type = annotation.name();
            if (!types.isStereotype(type) || !visited.add(type)) continue;
            types.meta(type).flatMap(m -> m.annotation(PRIORITY))
                    .map(p -> p.member(VALUE))
                    .filter(AnnotationValue.IntVal.class::isInstance)
                    .ifPresent(v -> priorities.add(((AnnotationValue.IntVal) v).value()));
            collectStereotypePriorities(metaAnnotations(type), priorities, visited);
        }
    }

    private void validateStereotypeScopes(ClassInfo classInfo, List<AnnotationInfo> annotations,
            boolean beanDefining, List<String> errors) {
        if (!beanDefining || classInfo.annotations().stream().anyMatch(a -> types.isScope(a.name()))) return;
        var scopes = new LinkedHashSet<String>();
        collectStereotypeScopes(annotations, scopes, new HashSet<>());
        if (scopes.size() > 1) {
            errors.add(BEAN + classInfo.name().value() + " has conflicting stereotype scopes: " + scopes);
        }
    }

    private void collectStereotypeScopes(List<AnnotationInfo> annotations, Set<String> scopes, Set<DotName> visited) {
        for (var annotation : annotations) {
            var type = annotation.name();
            if (!types.isStereotype(type) || !visited.add(type)) continue;
            for (var meta : metaAnnotations(type)) {
                if (types.isScope(meta.name())) {
                    // As Class#toString spells an annotation type, the form the reflective rule reported.
                    scopes.add("interface " + meta.name().value());
                }
            }
            collectStereotypeScopes(metaAnnotations(type), scopes, visited);
        }
    }

    private void validateInterceptorBindings(ClassInfo classInfo, List<AnnotationInfo> annotations,
            boolean beanDefining, List<String> errors) {
        if (!beanDefining) return;
        collectTransitiveInterceptorBindings(annotations, new HashMap<>(), errors, classInfo.name().value(),
                new HashSet<>());
    }

    private void collectTransitiveInterceptorBindings(List<AnnotationInfo> annotations,
            Map<DotName, AnnotationInfo> bindingsByType, List<String> errors, String beanName, Set<DotName> visited) {
        for (var annotation : annotations) {
            var type = annotation.name();
            boolean stereotypeOrBinding = types.isStereotype(type) || types.isInterceptorBinding(type);
            if (stereotypeOrBinding && visited.add(type)) {
                for (var meta : metaAnnotations(type)) {
                    if (types.isInterceptorBinding(meta.name())) {
                        var value = types.withDefaults(meta);
                        var previous = bindingsByType.put(meta.name(), value);
                        if (previous != null && !previous.equals(value)) {
                            errors.add(BEAN + beanName + " has conflicting interceptor binding values for "
                                    + simpleName(meta.name()));
                        }
                    }
                }
                collectTransitiveInterceptorBindings(metaAnnotations(type), bindingsByType, errors, beanName, visited);
            }
        }
    }

    // ---- Types ----

    /** As reflection judged it: the erased type is {@code Event}/{@code Instance}, the generic one not parameterized. */
    private static void validateNoRawParameterized(TypeInfo type, String location, List<String> errors) {
        if (type instanceof TypeInfo.ParameterizedType) return;
        var raw = rawClass(type);
        if (EVENT.equals(raw)) {
            errors.add("Raw Event type injected at " + location + " — must be parameterized");
        }
        if (INSTANCE.equals(raw)) {
            errors.add("Raw Instance type injected at " + location + " — must be parameterized");
        }
    }

    private static void validateProducerReturnType(TypeInfo type, String location, List<String> errors) {
        switch (type) {
            case TypeInfo.TypeVariable _ -> errors.add(PRODUCER + location + " has type variable return type");
            case TypeInfo.WildcardType _ -> errors.add(PRODUCER + location + " has wildcard return type");
            case TypeInfo.ParameterizedType(_, var arguments) when hasWildcard(arguments) ->
                    errors.add(PRODUCER + location + " has parameterized type with wildcard argument");
            // Reflection's GenericArrayType: one dimension of a generic component (a T[][] nests another array)
            case TypeInfo.ArrayType(var component, var dimensions) when dimensions == 1 -> {
                switch (component) {
                    case TypeInfo.TypeVariable _ ->
                            errors.add(PRODUCER + location + " has array type with type variable component");
                    case TypeInfo.ParameterizedType(_, var arguments) when hasWildcard(arguments) ->
                            errors.add(PRODUCER + location + " has array of parameterized type with wildcard");
                    default -> { /* a plain component */ }
                }
            }
            default -> { /* nothing to report */ }
        }
    }

    private static boolean hasWildcard(List<TypeInfo> arguments) {
        return arguments.stream().anyMatch(TypeInfo.WildcardType.class::isInstance);
    }

    private static boolean containsWildcard(TypeInfo type) {
        return switch (type) {
            case TypeInfo.WildcardType _ -> true;
            case TypeInfo.ParameterizedType(_, var arguments) ->
                    arguments.stream().anyMatch(IndexClassValidator::containsWildcard);
            case TypeInfo.ArrayType(var component, _) -> containsWildcard(component);
            default -> false;
        };
    }

    private static boolean containsTypeVariable(TypeInfo type) {
        return switch (type) {
            case null -> false;
            case TypeInfo.TypeVariable _ -> true;
            case TypeInfo.ParameterizedType(_, var arguments) ->
                    arguments.stream().anyMatch(IndexClassValidator::containsTypeVariable);
            case TypeInfo.ArrayType(var component, _) -> containsTypeVariable(component);
            case TypeInfo.WildcardType(var upper, var lower) -> containsTypeVariable(upper) || containsTypeVariable(lower);
            default -> false;
        };
    }

    private boolean hasNonDependentScope(List<AnnotationInfo> annotations) {
        return annotations.stream().anyMatch(a -> types.isNormalScope(a.name())
                || (types.isScope(a.name()) && !a.name().equals(DEPENDENT)));
    }

    private void validateNoMultipleScopes(List<AnnotationInfo> annotations, String location, List<String> errors) {
        if (annotations.stream().filter(a -> types.isScope(a.name())).count() > 1) {
            errors.add(location + " has multiple scope annotations");
        }
    }

    /** The erased class of a type, as {@code getType()}/{@code getReturnType()} give it. */
    private static DotName rawClass(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType(var name) -> name;
            case TypeInfo.ParameterizedType(var raw, _) -> raw;
            case TypeInfo.TypeVariable(_, var bounds) -> bounds.isEmpty() ? OBJECT : rawClass(bounds.getFirst());
            default -> null;
        };
    }

    /**
     * Whether {@code type} is {@code from} or one of its supertypes, as {@code Class.isAssignableFrom}; undecidable
     * when {@code from} is not a class or a supertype it would have to look at is not in the index.
     */
    private boolean assignable(DotName type, DotName from) {
        if (from == null || index.getClassByName(from).isEmpty() && !from.equals(OBJECT)) {
            throw new Undecidable();
        }
        if (type.equals(from) || type.equals(OBJECT)) return true;
        var pending = new ArrayDeque<DotName>();
        var seen = new HashSet<DotName>();
        pending.add(from);
        while (!pending.isEmpty()) {
            var current = pending.poll();
            if (current.equals(type)) return true;
            if (current.equals(OBJECT) || !seen.add(current)) continue;
            var info = index.getClassByName(current).orElseThrow(Undecidable::new);
            if (info.superName() != null) pending.add(info.superName());
            pending.addAll(info.interfaces());
        }
        return false;
    }

    // ---- Members ----

    private static List<MethodInfo> methods(ClassInfo classInfo) {
        return classInfo.methods().stream().filter(m -> !m.isConstructor() && !m.isStaticInitializer()).toList();
    }

    private static java.util.stream.Stream<MethodInfo> constructors(ClassInfo classInfo) {
        return classInfo.methods().stream().filter(MethodInfo::isConstructor);
    }

    private static boolean has(List<AnnotationInfo> annotations, DotName name) {
        return annotations.stream().anyMatch(a -> a.name().equals(name));
    }

    private static Optional<AnnotationInfo> annotation(List<AnnotationInfo> annotations, DotName name) {
        return annotations.stream().filter(a -> a.name().equals(name)).findFirst();
    }

    private static List<DotName> classValues(AnnotationInfo annotation) {
        return switch (annotation.member(VALUE)) {
            case AnnotationValue.ArrayVal(var values) -> values.stream()
                    .filter(AnnotationValue.ClassVal.class::isInstance)
                    .map(v -> ((AnnotationValue.ClassVal) v).className())
                    .toList();
            case AnnotationValue.ClassVal(var name) -> List.of(name);
            case null, default -> List.of();
        };
    }

    private static String stringValue(AnnotationValue value) {
        return value instanceof AnnotationValue.StringVal(var s) ? s : "";
    }

    /** As {@code Class.getSimpleName()} spells a top-level or nested annotation type. */
    private static String simpleName(DotName name) {
        var value = name.value();
        return value.substring(Math.max(value.lastIndexOf('.'), value.lastIndexOf('$')) + 1);
    }
}
