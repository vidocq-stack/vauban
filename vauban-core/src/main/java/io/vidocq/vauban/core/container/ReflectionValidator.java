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
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReflectionValidator {

    private ReflectionValidator() {}

    static List<String> validateWithReflection(List<Class<?>> beanClasses) {
        var errors = new ArrayList<String>();
        for (var clazz : beanClasses) {
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum()) continue;

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
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    for (var param : method.getParameters()) {
                        var named = param.getAnnotation(jakarta.inject.Named.class);
                        if (named != null && named.value().isEmpty()) {
                            errors.add("@Named without value on initializer parameter: "
                                    + clazz.getName() + "." + method.getName());
                        }
                    }
                }
            }
            for (var ctor : clazz.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    for (var param : ctor.getParameters()) {
                        var named = param.getAnnotation(jakarta.inject.Named.class);
                        if (named != null && named.value().isEmpty()) {
                            errors.add("@Named without value on constructor parameter: "
                                    + clazz.getName());
                        }
                    }
                }
            }
        }

        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                for (var param : method.getParameters()) {
                    var named = param.getAnnotation(jakarta.inject.Named.class);
                    if (named != null && named.value().isEmpty()) {
                        errors.add("@Named without value on producer method parameter: "
                                + clazz.getName() + "." + method.getName());
                    }
                }
            }
            boolean hasObservesOrDisposes = false;
            for (var param : method.getParameters()) {
                if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                        || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)
                        || param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                    hasObservesOrDisposes = true;
                    break;
                }
            }
            if (hasObservesOrDisposes) {
                for (var param : method.getParameters()) {
                    if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                            || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)
                            || param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) continue;
                    var named = param.getAnnotation(jakarta.inject.Named.class);
                    if (named != null && named.value().isEmpty()) {
                        errors.add("@Named without value on observer/disposer method parameter: "
                                + clazz.getName() + "." + method.getName());
                    }
                }
            }
        }
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
        if (clazz.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
            var typed = clazz.getAnnotation(jakarta.enterprise.inject.Typed.class);
            for (var t : typed.value()) {
                if (!t.isAssignableFrom(clazz)) {
                    errors.add("@Typed value " + t.getName() + " on " + clazz.getName()
                            + " is not a legal bean type (not a supertype)");
                }
            }
        }

        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                    && method.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                var typed = method.getAnnotation(jakarta.enterprise.inject.Typed.class);
                var returnType = method.getReturnType();
                for (var t : typed.value()) {
                    if (!t.isAssignableFrom(returnType) && t != Object.class) {
                        errors.add("@Typed value " + t.getName() + " on producer method "
                                + clazz.getName() + "." + method.getName()
                                + " is not a legal bean type");
                    }
                }
            }
        }
        for (var field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                    && field.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                var typed = field.getAnnotation(jakarta.enterprise.inject.Typed.class);
                var fieldType = field.getType();
                for (var t : typed.value()) {
                    if (!t.isAssignableFrom(fieldType) && t != Object.class) {
                        errors.add("@Typed value " + t.getName() + " on producer field "
                                + clazz.getName() + "." + field.getName()
                                + " is not a legal bean type");
                    }
                }
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
            if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                validateProducerReturnType(method.getGenericReturnType(),
                        clazz.getName() + "." + method.getName(), errors);
                validateNoMultipleScopes(method.getAnnotations(),
                        "Producer method " + clazz.getName() + "." + method.getName(), errors);

                var genRetType = method.getGenericReturnType();
                if (containsTypeVariable(genRetType)) {
                    boolean isDependent = true;
                    for (var mAnn : method.getAnnotations()) {
                        if (mAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                                || (mAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)
                                    && mAnn.annotationType() != jakarta.enterprise.context.Dependent.class)) {
                            isDependent = false;
                            break;
                        }
                    }
                    if (!isDependent) {
                        errors.add("Producer method " + clazz.getName() + "." + method.getName()
                                + " has TypeVariable return type and non-@Dependent scope");
                    }
                }

                if (containsWildcard(genRetType)) {
                    errors.add("Producer method " + clazz.getName() + "." + method.getName()
                            + " has wildcard type parameter in return type");
                }
            }

            if (method.isAnnotationPresent(jakarta.inject.Inject.class)
                    && method.getTypeParameters().length > 0
                    && !method.getName().equals("<init>")) {
                errors.add("Initializer method " + clazz.getName() + "." + method.getName()
                        + " cannot declare type parameters");
            }

            if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                var paramTypes = method.getGenericParameterTypes();
                var rawTypes = method.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    validateNoRawParameterized(paramTypes[i], rawTypes[i],
                            clazz.getName() + "." + method.getName() + " param " + i, errors);
                }
            }

            var params = method.getParameters();
            boolean hasObserves = false;
            for (var p : params) {
                if (p.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                        || p.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                    hasObserves = true;
                    break;
                }
            }
            if (hasObserves) {
                var paramTypes = method.getGenericParameterTypes();
                var rawTypes = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (!params[i].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                            && !params[i].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                        validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                clazz.getName() + "." + method.getName() + " observer param", errors);
                    }
                }
            }

            boolean hasDisposes = false;
            for (var p : params) {
                if (p.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                    hasDisposes = true;
                    break;
                }
            }
            if (hasDisposes) {
                var paramTypes = method.getGenericParameterTypes();
                var rawTypes = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (!params[i].isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                        validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                clazz.getName() + "." + method.getName() + " disposer param", errors);
                    }
                    if (rawTypes[i] == jakarta.enterprise.inject.spi.InjectionPoint.class) {
                        errors.add("Disposer method " + clazz.getName() + "." + method.getName()
                                + " must not have InjectionPoint parameter");
                    }
                }
            }

            if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                var paramTypes = method.getGenericParameterTypes();
                var rawTypes = method.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    validateNoRawParameterized(paramTypes[i], rawTypes[i],
                            clazz.getName() + "." + method.getName() + " producer param", errors);
                }
            }
        }
    }

    private static void validateProducerFields(Class<?> clazz, List<String> errors) {
        for (var field : clazz.getDeclaredFields()) {
            if (!field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) continue;
            validateProducerReturnType(field.getGenericType(),
                    clazz.getName() + "." + field.getName(), errors);
            validateNoMultipleScopes(field.getAnnotations(),
                    "Producer field " + clazz.getName() + "." + field.getName(), errors);
            if (containsTypeVariable(field.getGenericType())) {
                boolean isDependent = true;
                for (var fAnn : field.getAnnotations()) {
                    if (fAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                            || (fAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)
                                && fAnn.annotationType() != jakarta.enterprise.context.Dependent.class)) {
                        isDependent = false;
                        break;
                    }
                }
                if (!isDependent) {
                    errors.add("Producer field " + clazz.getName() + "." + field.getName()
                            + " has TypeVariable type and non-@Dependent scope");
                }
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
            errors.add("Bean " + clazz.getName()
                    + " has conflicting stereotype priorities: " + priorities);
        }
    }

    private static void validateStereotypeScopes(Class<?> clazz, List<String> errors) {
        if (!hasBeanDefiningAnnotation(clazz) || hasExplicitScope(clazz)) return;
        var scopes = new LinkedHashSet<Class<?>>();
        collectStereotypeScopes(clazz, scopes, new HashSet<>());
        if (scopes.size() > 1) {
            errors.add("Bean " + clazz.getName()
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
            if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)
                    || annType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                    || VaubanBeanManager.isCustomInterceptorBinding(annType)) {
                if (!visited.add(annType)) continue;
                for (var metaAnn : annType.getAnnotations()) {
                    var mt = metaAnn.annotationType();
                    if (mt.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                            || VaubanBeanManager.isCustomInterceptorBinding(mt)) {
                        var prev = bindingsByType.put(metaAnn.annotationType(), metaAnn);
                        if (prev != null && !prev.equals(metaAnn)) {
                            errors.add("Bean " + beanName
                                    + " has conflicting interceptor binding values for "
                                    + metaAnn.annotationType().getSimpleName());
                        }
                    }
                }
                collectTransitiveInterceptorBindings(annType.getAnnotations(), bindingsByType,
                        errors, beanName, visited);
            }
        }
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
            errors.add("Producer " + location + " has type variable return type");
        }
        if (type instanceof WildcardType) {
            errors.add("Producer " + location + " has wildcard return type");
        }
        if (type instanceof ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (arg instanceof WildcardType) {
                    errors.add("Producer " + location + " has parameterized type with wildcard argument");
                    break;
                }
            }
        }
        if (type instanceof GenericArrayType gat) {
            var componentType = gat.getGenericComponentType();
            if (componentType instanceof TypeVariable<?>) {
                errors.add("Producer " + location + " has array type with type variable component");
            }
            if (componentType instanceof WildcardType) {
                errors.add("Producer " + location + " has array type with wildcard component");
            }
            if (componentType instanceof ParameterizedType cpt) {
                for (var arg : cpt.getActualTypeArguments()) {
                    if (arg instanceof WildcardType) {
                        errors.add("Producer " + location + " has array of parameterized type with wildcard");
                        break;
                    }
                }
            }
        }
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
        if (type instanceof TypeVariable<?>) return true;
        if (type instanceof ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) return true;
            }
        }
        if (type instanceof GenericArrayType gat) {
            return containsTypeVariable(gat.getGenericComponentType());
        }
        if (type instanceof WildcardType wt) {
            for (var bound : wt.getUpperBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
            for (var bound : wt.getLowerBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
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
