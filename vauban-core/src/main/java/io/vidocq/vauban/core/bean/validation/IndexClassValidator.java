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
    private static final DotName VETOED = DotName.of("jakarta.enterprise.inject.Vetoed");
    private static final String VALUE = "value";

    private static final String TYPED_VALUE = "@Typed value ";
    private static final String NOT_A_LEGAL_BEAN_TYPE = " is not a legal bean type";
    private static final String PRODUCER = "Producer ";
    private static final String PRODUCER_METHOD = "Producer method ";
    private static final String BEAN = "Bean ";
    private static final String INITIALIZER_PARAM = " initializer param ";
    /** The start of the definition error of an injection point of a type variable (CDI 4.1, 5.2.3). */
    public static final String TYPE_VARIABLE_INJECTION_POINT = "Injection point has type variable type ";

    /** The index cannot decide: the class is validated by reflection. */
    private static final class Undecidable extends RuntimeException {
        Undecidable() {
            super(null, null, false, false);
        }
    }

    private final VaubanIndex index;
    private final AnnotationTypes types;
    private final boolean compilerElements;
    /** Describes a class the index does not hold, a superclass the processor did not index, or returns empty. */
    private final Function<DotName, Optional<ClassInfo>> outside;
    /** The classes known to be beans without a bean-defining annotation: a bean descriptor names them. */
    private final Predicate<DotName> beanClasses;

    /**
     * @param loader                   loads an annotation type the index does not hold, or returns {@code null}
     * @param customInterceptorBinding the annotation types an extension registered as interceptor bindings
     */
    public IndexClassValidator(VaubanIndex index, Function<String, Class<?>> loader,
            Predicate<DotName> customInterceptorBinding) {
        this(index, new AnnotationTypes(index, loader, customInterceptorBinding), false, name -> Optional.empty(),
                name -> false);
    }

    private IndexClassValidator(VaubanIndex index, AnnotationTypes types, boolean compilerElements,
            Function<DotName, Optional<ClassInfo>> outside, Predicate<DotName> beanClasses) {
        this.index = index;
        this.types = types;
        this.compilerElements = compilerElements;
        this.outside = outside;
        this.beanClasses = beanClasses;
    }

    /**
     * This validator, for which a class {@code beanClasses} accepts is a bean whether it has a bean-defining
     * annotation or not: a bean descriptor names it, as an extension made it one.
     */
    public IndexClassValidator withBeanClasses(Predicate<DotName> beanClasses) {
        return new IndexClassValidator(index, types, compilerElements, outside, beanClasses);
    }

    /**
     * For the processor: its index is built from the compiler's elements, whose member types are the generic ones
     * the source declares, so it validates them at build. An annotation type or a superclass the index does not hold,
     * a vetoed one say, is described by {@code describer}, from the compiler's elements too: nothing is loaded.
     */
    public static IndexClassValidator forCompilerElements(VaubanIndex index,
            Function<DotName, Optional<ClassInfo>> describer) {
        return new IndexClassValidator(index, new AnnotationTypes(index, describer, name -> null, name -> false),
                true, describer, name -> false);
    }

    /** The class {@code name}, from the index or {@link #outside}; undecidable when neither describes it. */
    private ClassInfo superClass(DotName name) {
        return index.getClassByName(name).or(() -> outside.apply(name)).orElseThrow(Undecidable::new);
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
            var superClass = superClass(superName);
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
            annotations.stream()
                    .filter(a -> types.isNormalScope(a.name()) || a.name().equals(SINGLETON))
                    .findFirst()
                    .ifPresent(scope -> errors.add(GenericBeanScope.error(classInfo.name().value(),
                            scope.name().value())));
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
        var bean = mayBeABean(classInfo);
        for (var field : classInfo.fields()) {
            if (has(field.annotations(), INJECT)) {
                var location = classInfo.name().value() + "." + field.name();
                validateNoRawParameterized(field.type(), location, errors);
                validateNoTypeVariable(bean, field.type(), location, errors);
            }
        }
    }

    private void validateMethods(ClassInfo classInfo, List<String> errors) {
        var className = classInfo.name().value();
        var bean = mayBeABean(classInfo);
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
                    validateNoTypeVariable(bean, parameters.get(i).type(), location + INITIALIZER_PARAM + i,
                            errors);
                }
            }
            if (method.parameters().stream().anyMatch(IndexClassValidator::isObserves)) {
                var parameters = method.parameters();
                for (int i = 0; i < parameters.size(); i++) {
                    if (!isObserves(parameters.get(i))) {
                        validateNoRawParameterized(parameters.get(i).type(), location + " observer param", errors);
                        validateNoTypeVariable(bean, parameters.get(i).type(), location + " observer param " + i,
                                errors);
                    }
                }
            }
            if (method.parameters().stream().anyMatch(IndexClassValidator::isDisposes)) {
                var parameters = method.parameters();
                for (int i = 0; i < parameters.size(); i++) {
                    var parameter = parameters.get(i);
                    if (!isDisposes(parameter)) {
                        validateNoRawParameterized(parameter.type(), location + " disposer param", errors);
                        validateNoTypeVariable(bean, parameter.type(), location + " disposer param " + i, errors);
                    }
                    if (INJECTION_POINT.equals(rawClass(parameter.type()))) {
                        errors.add("Disposer method " + location + " must not have InjectionPoint parameter");
                    }
                }
            }
            if (producer) {
                var parameters = method.parameters();
                for (int i = 0; i < parameters.size(); i++) {
                    validateNoRawParameterized(parameters.get(i).type(), location + " producer param", errors);
                    validateNoTypeVariable(bean, parameters.get(i).type(), location + " producer param " + i, errors);
                }
            }
        }
        if (bean) {
            validateInheritedInjectionPoints(classInfo, errors);
        }
    }

    /**
     * The {@code @Inject} fields and the initializers a class that may be a bean inherits from superclasses that are
     * none, read as the class gives their type variables: {@code Foo extends Bar<String, Qux>} gives {@code Bar}'s
     * {@code @Inject T1 t1} a class, as the CDI TCK's {@code inheritance.generics} has it, and
     * {@code Sub<T> extends Bar<T, Qux>} leaves it a type variable, a definition error (CDI 4.1, 5.2.3;
     * BUG-20261117-29); so does {@code Sub<T> extends VetoedSetter<T>} the parameter of {@code @Inject void set(T t)}
     * (BUG-20261117-30). An initializer a class below overrides is none. A superclass that may be a bean reports its
     * own injection points.
     */
    private void validateInheritedInjectionPoints(ClassInfo classInfo, List<String> errors) {
        Map<String, TypeInfo> given = Map.of();
        var overridden = overridable(classInfo);
        var generic = classInfo.genericSuperclass();
        var superName = classInfo.superName();
        boolean own = true;
        while (superName != null && !superName.equals(OBJECT)) {
            var superClass = superClass(superName);
            given = typeArguments(superClass, generic, given, own);
            own = false;
            if (!mayBeABean(superClass)) {
                for (var field : superClass.fields()) {
                    if (has(field.annotations(), INJECT) && !field.isStatic()) {
                        reportLeftTypeVariable(field.type(), given, classInfo.name().value() + "." + field.name(),
                                errors);
                    }
                }
                for (var method : methods(superClass)) {
                    if (!has(method.annotations(), INJECT) || method.isStatic()
                            || !method.isPrivate() && overridden.contains(signature(method))) {
                        continue;
                    }
                    var parameters = method.parameters();
                    for (int i = 0; i < parameters.size(); i++) {
                        reportLeftTypeVariable(parameters.get(i).type(), given,
                                classInfo.name().value() + "." + method.name() + INITIALIZER_PARAM + i, errors);
                    }
                }
            }
            overridden.addAll(overridable(superClass));
            generic = superClass.genericSuperclass();
            superName = superClass.superName();
        }
    }

    /** Reports {@code type}, a type variable of a superclass the class leaves a type variable, at {@code location}. */
    private static void reportLeftTypeVariable(TypeInfo type, Map<String, TypeInfo> given, String location,
            List<String> errors) {
        if (type instanceof TypeInfo.TypeVariable(var name, _)
                && given.get(name) instanceof TypeInfo.TypeVariable(var left, _)) {
            errors.add(TYPE_VARIABLE_INJECTION_POINT + left + ": " + location);
        }
    }

    /** The name and arity of the methods of {@code classInfo} a subclass method may override. */
    private static Set<String> overridable(ClassInfo classInfo) {
        var signatures = new HashSet<String>();
        for (var method : methods(classInfo)) {
            if (!method.isPrivate() && !method.isStatic() && !method.isSynthetic()) {
                signatures.add(signature(method));
            }
        }
        return signatures;
    }

    private static String signature(MethodInfo method) {
        return method.name() + "/" + method.parameters().size();
    }

    /**
     * What {@code superClass}'s type variables are, by name, as the class below it gives them in {@code generic}, its
     * generic superclass: a type variable of the class below read through {@code below}, what that class's own
     * variables are, or kept as it is when the class below is the one validated ({@code own}). A raw superclass has
     * its type variables erased: none is given.
     */
    public static Map<String, TypeInfo> typeArguments(ClassInfo superClass, TypeInfo generic,
            Map<String, TypeInfo> below, boolean own) {
        if (!(generic instanceof TypeInfo.ParameterizedType(_, var arguments))
                || arguments.size() != superClass.typeParameters().size()) {
            return Map.of();
        }
        var given = new HashMap<String, TypeInfo>();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument instanceof TypeInfo.TypeVariable(var name, _) && !own) {
                argument = below.get(name);
            }
            if (argument != null) {
                given.put(superClass.typeParameters().get(i).name(), argument);
            }
        }
        return given;
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
        var bean = mayBeABean(classInfo);
        for (var ctor : constructors(classInfo).toList()) {
            if (!has(ctor.annotations(), INJECT)) continue;
            var parameters = ctor.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                var location = classInfo.name().value() + " constructor param " + i;
                validateNoRawParameterized(parameters.get(i).type(), location, errors);
                validateNoTypeVariable(bean, parameters.get(i).type(), location, errors);
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

    /**
     * As reflection judged it: an injection point of a type variable is a definition error (CDI 4.1, 5.2.3), here an
     * observer's parameter besides the event parameter, a disposer's besides the disposed one, a producer method's, an
     * initializer's, a bean constructor's or an {@code @Inject} field, of a class that {@link #mayBeABean may be a
     * bean}. A subclass that inherits the observer, the initializer or the field of a class that is none gives the
     * type variable its class, as the CDI TCK's {@code MemberLevelInheritanceTest} has it (BUG-20261117-16,
     * BUG-20261117-30, BUG-20261117-29).
     */
    private static void validateNoTypeVariable(boolean bean, TypeInfo type, String location, List<String> errors) {
        if (bean && type instanceof TypeInfo.TypeVariable(var name, _)) {
            errors.add(TYPE_VARIABLE_INJECTION_POINT + name + ": " + location);
        }
    }

    /**
     * Neither abstract nor vetoed, nor in a vetoed package, and with a bean-defining annotation or named by a bean
     * descriptor: a class whose own injection points and observers the container may use, as bean discovery decides.
     * A concrete class without a bean-defining annotation is no bean in the annotated discovery mode: a subclass
     * that inherits its members gives them their types (BUG-20261117-30, review round 1).
     */
    private boolean mayBeABean(ClassInfo classInfo) {
        if (classInfo.isAbstract() || classInfo.hasAnnotation(VETOED)) {
            return false;
        }
        if (!beanClasses.test(classInfo.name()) && !hasBeanDefiningAnnotation(classInfo, annotations(classInfo))) {
            return false;
        }
        var name = classInfo.name().value();
        var dot = name.lastIndexOf('.');
        return dot < 0 || index.getClassByName(DotName.of(name.substring(0, dot) + ".package-info"))
                .map(packageInfo -> !packageInfo.hasAnnotation(VETOED)).orElse(true);
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
