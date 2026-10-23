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
package io.vidocq.vauban.core.container;

import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReflectionValidator {

    private static final String TYPED_VALUE = "@Typed value ";
    private static final String NOT_A_LEGAL_BEAN_TYPE = " is not a legal bean type";
    private static final String PRODUCER = "Producer ";
    private static final String PRODUCER_METHOD = "Producer method ";
    private static final String BEAN = "Bean ";

    private ReflectionValidator() {}

    static List<String> validateWithReflection(List<Class<?>> beanClasses) {
        var errors = new ArrayList<String>();
        for (var clazz : beanClasses) {
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum()) continue;

            // Defensive: every check below inspects members reflectively, and
            // getDeclaredFields/Methods/Constructors throw a LinkageError (typically
            // NoClassDefFoundError) as soon as one member signature references a type that
            // cannot be resolved here — an optional dependency declared 'requires static'
            // and absent at runtime, or a stale .class left by an interrupted build.
            // Such a class cannot be inspected member-by-member, so we skip it rather than
            // let validation abort the whole deployment. Same guard as BceTypeMatcher,
            // BceProcessor.getDeclaredMethodsSafe, ContainerScanner and VaubanContainerBuilder.
            try {
                validateStereotypeNamed(clazz, errors);
                validateGenericBeanScope(clazz, errors);
                validateNamedOnParameters(clazz, errors);
                validateNormalScopedPublicFields(clazz, errors);
                validateTypedValues(clazz, errors);
                validateInjectFields(clazz, errors);
                validateMethods(clazz, errors);
                validateProducerFields(clazz, errors);
                validateConstructors(clazz, errors);
                validateStereotypePriorities(clazz, errors);
                validateStereotypeScopes(clazz, errors);
                validateInterceptorBindings(clazz, errors);
            } catch (LinkageError _) {
                // Uninspectable in this context — skip this class.
            }
        }
        return errors;
    }

    private static void validateStereotypeNamed(Class<?> clazz, List<String> errors) {
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                var named = ann.annotationType().getAnnotation(jakarta.inject.Named.class);
                if (named != null && !named.value().isEmpty()) {
                    errors.add("Stereotype " + ann.annotationType().getName()
                            + " has @Named with non-empty value '" + named.value() + "'");
                }
            }
        }
    }

    private static void validateGenericBeanScope(Class<?> clazz, List<String> errors) {
        if (hasBeanDefiningAnnotation(clazz) && clazz.getTypeParameters().length > 0) {
            boolean isNonDependent = false;
            for (var ann : clazz.getAnnotations()) {
                var annType = ann.annotationType();
                if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                        || annType == jakarta.inject.Singleton.class) {
                    isNonDependent = true;
                    break;
                }
            }
            if (isNonDependent) {
                errors.add("Managed bean " + clazz.getName()
                        + " has type parameters and is not @Dependent");
            }
        }
    }

    private static void validateNamedOnParameters(Class<?> clazz, List<String> errors) {
        if (hasBeanDefiningAnnotation(clazz)) {
            validateNamedOnInitializerParameters(clazz, errors);
            validateNamedOnConstructorParameters(clazz, errors);
        }
        for (var method : clazz.getDeclaredMethods()) {
            validateNamedOnProducerParameters(clazz, method, errors);
            validateNamedOnObserverOrDisposerParameters(clazz, method, errors);
        }
    }

    private static void validateNamedOnInitializerParameters(Class<?> clazz, List<String> errors) {
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                for (var param : method.getParameters()) {
                    if (isNamedWithoutValue(param)) {
                        errors.add("@Named without value on initializer parameter: "
                                + clazz.getName() + "." + method.getName());
                    }
                }
            }
        }
    }

    private static void validateNamedOnConstructorParameters(Class<?> clazz, List<String> errors) {
        for (var ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                for (var param : ctor.getParameters()) {
                    if (isNamedWithoutValue(param)) {
                        errors.add("@Named without value on constructor parameter: "
                                + clazz.getName());
                    }
                }
            }
        }
    }

    private static void validateNamedOnProducerParameters(Class<?> clazz, Method method, List<String> errors) {
        if (!method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
            return;
        }
        for (var param : method.getParameters()) {
            if (isNamedWithoutValue(param)) {
                errors.add("@Named without value on producer method parameter: "
                        + clazz.getName() + "." + method.getName());
            }
        }
    }

    private static void validateNamedOnObserverOrDisposerParameters(Class<?> clazz, Method method,
            List<String> errors) {
        var params = method.getParameters();
        if (Arrays.stream(params).noneMatch(ReflectionValidator::isObservesOrDisposes)) {
            return;
        }
        for (var param : params) {
            if (!isObservesOrDisposes(param) && isNamedWithoutValue(param)) {
                errors.add("@Named without value on observer/disposer method parameter: "
                        + clazz.getName() + "." + method.getName());
            }
        }
    }

    private static boolean isNamedWithoutValue(Parameter param) {
        var named = param.getAnnotation(jakarta.inject.Named.class);
        return named != null && named.value().isEmpty();
    }

    private static boolean isObserves(Parameter param) {
        return param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class);
    }

    private static boolean isDisposes(Parameter param) {
        return param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class);
    }

    private static boolean isObservesOrDisposes(Parameter param) {
        return isObserves(param) || isDisposes(param);
    }

    private static void validateNormalScopedPublicFields(Class<?> clazz, List<String> errors) {
        if (!hasBeanDefiningAnnotation(clazz)) return;
        boolean isNormalScoped = false;
        for (var ann : clazz.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(
                    jakarta.enterprise.context.NormalScope.class)) {
                isNormalScoped = true;
                break;
            }
        }
        if (isNormalScoped) {
            for (var field : clazz.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())
                        && !Modifier.isStatic(field.getModifiers())
                        && !field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    errors.add("Normal-scoped bean " + clazz.getName()
                            + " has non-static public field '" + field.getName() + "'");
                    break;
                }
            }
        }
    }

    private static void validateTypedValues(Class<?> clazz, List<String> errors) {
        var classTyped = clazz.getAnnotation(jakarta.enterprise.inject.Typed.class);
        if (classTyped != null) {
            for (var t : classTyped.value()) {
                if (!t.isAssignableFrom(clazz)) {
                    errors.add(TYPED_VALUE + t.getName() + " on " + clazz.getName()
                            + NOT_A_LEGAL_BEAN_TYPE + " (not a supertype)");
                }
            }
        }

        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                    && method.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                validateTypedProducer(method.getAnnotation(jakarta.enterprise.inject.Typed.class),
                        method.getReturnType(), "producer method " + clazz.getName() + "." + method.getName(),
                        errors);
            }
        }
        for (var field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                    && field.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                validateTypedProducer(field.getAnnotation(jakarta.enterprise.inject.Typed.class),
                        field.getType(), "producer field " + clazz.getName() + "." + field.getName(), errors);
            }
        }
    }

    /** Reports each {@code @Typed} value of a producer that is neither {@code Object} nor a supertype of its type. */
    private static void validateTypedProducer(jakarta.enterprise.inject.Typed typed, Class<?> producedType,
            String producer, List<String> errors) {
        for (var t : typed.value()) {
            if (!t.isAssignableFrom(producedType) && t != Object.class) {
                errors.add(TYPED_VALUE + t.getName() + " on " + producer + NOT_A_LEGAL_BEAN_TYPE);
            }
        }
    }

    private static void validateInjectFields(Class<?> clazz, List<String> errors) {
        for (var field : clazz.getDeclaredFields()) {
            if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
            validateNoRawParameterized(field.getGenericType(), field.getType(),
                    clazz.getName() + "." + field.getName(), errors);
        }
    }

    private static void validateMethods(Class<?> clazz, List<String> errors) {
        for (var method : clazz.getDeclaredMethods()) {
            boolean producer = method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class);
            if (producer) {
                validateProducerMethod(clazz, method, errors);
            }
            if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                validateInitializerMethod(clazz, method, errors);
            }
            var params = method.getParameters();
            if (Arrays.stream(params).anyMatch(ReflectionValidator::isObserves)) {
                validateObserverParameters(clazz, method, params, errors);
            }
            if (Arrays.stream(params).anyMatch(ReflectionValidator::isDisposes)) {
                validateDisposerParameters(clazz, method, params, errors);
            }
            if (producer) {
                validateAllParameters(method, clazz.getName() + "." + method.getName() + " producer param", errors);
            }
        }
    }

    private static void validateProducerMethod(Class<?> clazz, Method method, List<String> errors) {
        String location = clazz.getName() + "." + method.getName();
        validateProducerReturnType(method.getGenericReturnType(), location, errors);
        validateNoMultipleScopes(method.getAnnotations(), PRODUCER_METHOD + location, errors);

        var genRetType = method.getGenericReturnType();
        if (containsTypeVariable(genRetType) && hasNonDependentScope(method.getAnnotations())) {
            errors.add(PRODUCER_METHOD + location + " has TypeVariable return type and non-@Dependent scope");
        }
        if (containsWildcard(genRetType)) {
            errors.add(PRODUCER_METHOD + location + " has wildcard type parameter in return type");
        }
    }

    private static void validateInitializerMethod(Class<?> clazz, Method method, List<String> errors) {
        if (method.getTypeParameters().length > 0 && !method.getName().equals("<init>")) {
            errors.add("Initializer method " + clazz.getName() + "." + method.getName()
                    + " cannot declare type parameters");
        }
        var paramTypes = method.getGenericParameterTypes();
        var rawTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            validateNoRawParameterized(paramTypes[i], rawTypes[i],
                    clazz.getName() + "." + method.getName() + " param " + i, errors);
        }
    }

    private static void validateObserverParameters(Class<?> clazz, Method method, Parameter[] params,
            List<String> errors) {
        var paramTypes = method.getGenericParameterTypes();
        var rawTypes = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (!isObserves(params[i])) {
                validateNoRawParameterized(paramTypes[i], rawTypes[i],
                        clazz.getName() + "." + method.getName() + " observer param", errors);
            }
        }
    }

    private static void validateDisposerParameters(Class<?> clazz, Method method, Parameter[] params,
            List<String> errors) {
        var paramTypes = method.getGenericParameterTypes();
        var rawTypes = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (!isDisposes(params[i])) {
                validateNoRawParameterized(paramTypes[i], rawTypes[i],
                        clazz.getName() + "." + method.getName() + " disposer param", errors);
            }
            if (rawTypes[i] == jakarta.enterprise.inject.spi.InjectionPoint.class) {
                errors.add("Disposer method " + clazz.getName() + "." + method.getName()
                        + " must not have InjectionPoint parameter");
            }
        }
    }

    private static void validateAllParameters(Method method, String location, List<String> errors) {
        var paramTypes = method.getGenericParameterTypes();
        var rawTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            validateNoRawParameterized(paramTypes[i], rawTypes[i], location, errors);
        }
    }

    /** Whether one of {@code annotations} is a normal scope, or a pseudo-scope other than {@code @Dependent}. */
    private static boolean hasNonDependentScope(Annotation[] annotations) {
        for (var ann : annotations) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                    || (annType.isAnnotationPresent(jakarta.inject.Scope.class)
                        && annType != jakarta.enterprise.context.Dependent.class)) {
                return true;
            }
        }
        return false;
    }

    private static void validateProducerFields(Class<?> clazz, List<String> errors) {
        for (var field : clazz.getDeclaredFields()) {
            if (!field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) continue;
            String location = clazz.getName() + "." + field.getName();
            validateProducerReturnType(field.getGenericType(), location, errors);
            validateNoMultipleScopes(field.getAnnotations(), "Producer field " + location, errors);
            if (containsTypeVariable(field.getGenericType()) && hasNonDependentScope(field.getAnnotations())) {
                errors.add("Producer field " + location + " has TypeVariable type and non-@Dependent scope");
            }
        }
    }

    private static void validateConstructors(Class<?> clazz, List<String> errors) {
        for (var ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                var paramTypes = ctor.getGenericParameterTypes();
                var rawTypes = ctor.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    validateNoRawParameterized(paramTypes[i], rawTypes[i],
                            clazz.getName() + " constructor param " + i, errors);
                }
            }
        }
    }

    private static void validateStereotypePriorities(Class<?> clazz, List<String> errors) {
        if (!hasBeanDefiningAnnotation(clazz)) return;
        var priorities = new LinkedHashSet<Integer>();
        collectStereotypePriorities(clazz, priorities, new HashSet<>());
        if (priorities.size() > 1) {
            errors.add(BEAN + clazz.getName()
                    + " has conflicting stereotype priorities: " + priorities);
        }
    }

    private static void validateStereotypeScopes(Class<?> clazz, List<String> errors) {
        if (!hasBeanDefiningAnnotation(clazz) || hasExplicitScope(clazz)) return;
        var scopes = new LinkedHashSet<Class<?>>();
        collectStereotypeScopes(clazz, scopes, new HashSet<>());
        if (scopes.size() > 1) {
            errors.add(BEAN + clazz.getName()
                    + " has conflicting stereotype scopes: " + scopes);
        }
    }

    private static void validateInterceptorBindings(Class<?> clazz, List<String> errors) {
        if (!hasBeanDefiningAnnotation(clazz)) return;
        var bindingsByType = new HashMap<Class<?>, Annotation>();
        collectTransitiveInterceptorBindings(clazz.getAnnotations(), bindingsByType, errors,
                clazz.getName(), new HashSet<>());
    }

    private static void collectTransitiveInterceptorBindings(
            Annotation[] annotations,
            Map<Class<?>, Annotation> bindingsByType,
            List<String> errors, String beanName, Set<Class<?>> visited) {
        for (var ann : annotations) {
            var annType = ann.annotationType();
            boolean stereotypeOrBinding = annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)
                    || isInterceptorBinding(annType);
            if (stereotypeOrBinding && visited.add(annType)) {
                recordMetaBindings(annType, bindingsByType, errors, beanName);
                collectTransitiveInterceptorBindings(annType.getAnnotations(), bindingsByType,
                        errors, beanName, visited);
            }
        }
    }

    /** Records the interceptor bindings {@code annType} is annotated with, reporting a value that conflicts. */
    private static void recordMetaBindings(Class<? extends Annotation> annType,
            Map<Class<?>, Annotation> bindingsByType, List<String> errors, String beanName) {
        for (var metaAnn : annType.getAnnotations()) {
            var mt = metaAnn.annotationType();
            if (isInterceptorBinding(mt)) {
                var prev = bindingsByType.put(mt, metaAnn);
                if (prev != null && !prev.equals(metaAnn)) {
                    errors.add(BEAN + beanName
                            + " has conflicting interceptor binding values for "
                            + mt.getSimpleName());
                }
            }
        }
    }

    private static boolean isInterceptorBinding(Class<? extends Annotation> annType) {
        return annType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                || VaubanBeanManager.isCustomInterceptorBinding(annType);
    }

    private static void collectStereotypePriorities(Class<?> clazz,
            Set<Integer> priorities, Set<Class<?>> visited) {
        for (var ann : clazz.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                if (!visited.add(annType)) continue;
                var priority = annType.getAnnotation(jakarta.annotation.Priority.class);
                if (priority != null) {
                    priorities.add(priority.value());
                }
                collectStereotypePriorities(annType, priorities, visited);
            }
        }
    }

    private static boolean hasExplicitScope(Class<?> clazz) {
        for (var ann : clazz.getDeclaredAnnotations()) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                    || annType.isAnnotationPresent(jakarta.inject.Scope.class)) {
                return true;
            }
        }
        return false;
    }

    private static void collectStereotypeScopes(Class<?> clazz,
            Set<Class<?>> scopes, Set<Class<?>> visited) {
        for (var ann : clazz.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                if (!visited.add(annType)) continue;
                for (var metaAnn : annType.getAnnotations()) {
                    if (metaAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                            || metaAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)) {
                        scopes.add(metaAnn.annotationType());
                    }
                }
                collectStereotypeScopes(annType, scopes, visited);
            }
        }
    }

    private static void validateNoRawParameterized(Type genericType,
            Class<?> rawType, String location, List<String> errors) {
        if (rawType == jakarta.enterprise.event.Event.class
                && !(genericType instanceof ParameterizedType)) {
            errors.add("Raw Event type injected at " + location + " — must be parameterized");
        }
        if (rawType == jakarta.enterprise.inject.Instance.class
                && !(genericType instanceof ParameterizedType)) {
            errors.add("Raw Instance type injected at " + location + " — must be parameterized");
        }
    }

    private static void validateProducerReturnType(Type type, String location,
            List<String> errors) {
        if (type instanceof TypeVariable<?>) {
            errors.add(PRODUCER + location + " has type variable return type");
        }
        if (type instanceof WildcardType) {
            errors.add(PRODUCER + location + " has wildcard return type");
        }
        if (type instanceof ParameterizedType pt && hasWildcardArgument(pt)) {
            errors.add(PRODUCER + location + " has parameterized type with wildcard argument");
        }
        if (type instanceof GenericArrayType gat) {
            validateProducerArrayComponent(gat.getGenericComponentType(), location, errors);
        }
    }

    private static void validateProducerArrayComponent(Type componentType, String location, List<String> errors) {
        if (componentType instanceof TypeVariable<?>) {
            errors.add(PRODUCER + location + " has array type with type variable component");
        }
        if (componentType instanceof WildcardType) {
            errors.add(PRODUCER + location + " has array type with wildcard component");
        }
        if (componentType instanceof ParameterizedType cpt && hasWildcardArgument(cpt)) {
            errors.add(PRODUCER + location + " has array of parameterized type with wildcard");
        }
    }

    private static boolean hasWildcardArgument(ParameterizedType type) {
        return Arrays.stream(type.getActualTypeArguments()).anyMatch(WildcardType.class::isInstance);
    }

    private static void validateNoMultipleScopes(Annotation[] annotations,
            String location, List<String> errors) {
        int scopeCount = 0;
        for (var ann : annotations) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.inject.Scope.class)
                    || annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                scopeCount++;
            }
        }
        if (scopeCount > 1) {
            errors.add(location + " has multiple scope annotations");
        }
    }

    private static boolean containsWildcard(Type type) {
        if (type instanceof WildcardType) return true;
        if (type instanceof ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsWildcard(arg)) return true;
            }
        }
        if (type instanceof GenericArrayType gat) {
            return containsWildcard(gat.getGenericComponentType());
        }
        return false;
    }

    private static boolean containsTypeVariable(Type type) {
        return switch (type) {
            case TypeVariable<?> _ -> true;
            case ParameterizedType pt -> anyContainsTypeVariable(pt.getActualTypeArguments());
            case GenericArrayType gat -> containsTypeVariable(gat.getGenericComponentType());
            case WildcardType wt -> anyContainsTypeVariable(wt.getUpperBounds())
                    || anyContainsTypeVariable(wt.getLowerBounds());
            case null, default -> false;
        };
    }

    private static boolean anyContainsTypeVariable(Type[] types) {
        for (var type : types) {
            if (containsTypeVariable(type)) return true;
        }
        return false;
    }

    static boolean isBuildCompatibleExtension(Class<?> clazz) {
        return implementsInterface(clazz,
                "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension");
    }

    static boolean implementsInterface(Class<?> clazz, String interfaceName) {
        if (clazz == null || clazz == Object.class) return false;
        for (var iface : clazz.getInterfaces()) {
            if (iface.getName().equals(interfaceName)) return true;
            if (implementsInterface(iface, interfaceName)) return true;
        }
        return implementsInterface(clazz.getSuperclass(), interfaceName);
    }

    static boolean hasBeanDefiningAnnotation(Class<?> clazz) {
        for (var ann : clazz.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType == jakarta.enterprise.context.ApplicationScoped.class
                    || annType == jakarta.enterprise.context.RequestScoped.class
                    || annType == jakarta.enterprise.context.Dependent.class
                    || annType == jakarta.inject.Singleton.class) return true;
            if (annType.isAnnotationPresent(jakarta.inject.Scope.class)
                    || annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                    || annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) return true;
        }
        for (var ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
        }
        return false;
    }
}
