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
package io.vidocq.vauban.core.interceptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Generates intercepted bean subclasses using the JDK 25 Class-File API.
 *
 * <p>For a bean class {@code MyService} with interceptor bindings, generates
 * {@code MyService$$Intercepted extends MyService} where each non-private,
 * non-final, non-static method is overridden to invoke the interceptor chain
 * via {@link VaubanInvocationContext}.
 *
 * <p>The actual bytecode emission is delegated to {@link InterceptedEmitter} which
 * consumes a neutral {@link InterceptedShape} model. This decoupling allows the APT
 * front-end ({@code InterceptedShapeFromElements} in vauban-processor) to pre-generate
 * the same bytecode at compile time from {@code javax.lang.model.element.TypeElement}.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class InterceptorSubclassGenerator {

    private InterceptorSubclassGenerator() {}

    /**
     * Generate an intercepted subclass for the given bean class.
     *
     * @param beanClass the bean class to intercept
     * @return the generated class name and bytecode
     */
    public static GeneratedInterceptedClass generate(Class<?> beanClass) {
        InterceptedShape shape = fromClass(beanClass);
        byte[] bytecode = InterceptedEmitter.emit(shape);
        return new GeneratedInterceptedClass(shape.subclassName(), bytecode);
    }

    /**
     * Build an {@link InterceptedShape} from a runtime {@link Class}.
     *
     * <p>Iteration order is IDENTICAL to the original pre-refactor code:
     * <ol>
     *   <li>Constructors via {@link Class#getDeclaredConstructors()}, skipping private ones.</li>
     *   <li>Declared methods via {@link Class#getDeclaredMethods()} filtered by
     *       {@link #shouldIntercept(Method)}.</li>
     *   <li>Inherited public methods via {@link Class#getMethods()}, skipping those whose
     *       declaring class is the bean itself (already covered) or {@code Object}, and deduplicating
     *       by {@code name + Arrays.toString(parameterTypes)}, filtered by {@link #shouldIntercept(Method)}.</li>
     * </ol>
     */
    public static InterceptedShape fromClass(Class<?> beanClass) {
        String beanBinaryName = beanClass.getName();

        // Constructors — the (ProxyLink) client-proxy entry constructor is not mirrored:
        // the $$Intercepted subclass IS the contextual instance and must run business
        // constructors only (Vidocq/vauban#24).
        var ctors = new ArrayList<CtorShape>();
        for (var constructor : beanClass.getDeclaredConstructors()) {
            if (Modifier.isPrivate(constructor.getModifiers())) continue;
            if (io.vidocq.vauban.core.proxy.ClientProxyShape.isProxyLinkConstructor(constructor)) continue;
            var params = new ArrayList<TypeRef>();
            for (Class<?> p : constructor.getParameterTypes()) {
                params.add(TypeRef.fromClass(p));
            }
            ctors.add(new CtorShape(params));
        }

        // Methods (declared first, then inherited — preserving original order/dedup)
        var methods = new ArrayList<MethodShape>();
        var seen = new LinkedHashSet<String>();

        for (var method : beanClass.getDeclaredMethods()) {
            if (shouldIntercept(method)) {
                methods.add(methodShapeOf(method));
                seen.add(method.getName() + Arrays.toString(method.getParameterTypes()));
            }
        }
        // Also intercept inherited public methods (from superclasses)
        for (var method : beanClass.getMethods()) {
            if (method.getDeclaringClass() == beanClass) continue; // already handled
            if (method.getDeclaringClass() == Object.class) continue;
            var key = method.getName() + Arrays.toString(method.getParameterTypes());
            if (seen.contains(key)) continue; // already intercepted
            if (shouldIntercept(method)) {
                methods.add(methodShapeOf(method));
                seen.add(key);
            }
        }

        return new InterceptedShape(beanBinaryName, ctors, methods);
    }

    // ---- helpers ----

    private static MethodShape methodShapeOf(Method method) {
        TypeRef returnType = TypeRef.fromClass(method.getReturnType());
        var params = new ArrayList<TypeRef>();
        for (Class<?> p : method.getParameterTypes()) {
            params.add(TypeRef.fromClass(p));
        }
        return new MethodShape(method.getName(), returnType, params);
    }

    static boolean shouldIntercept(Method method) {
        if (Modifier.isStatic(method.getModifiers())) return false;
        if (Modifier.isPrivate(method.getModifiers())) return false;
        if (Modifier.isFinal(method.getModifiers())) return false;
        if (method.isSynthetic()) return false;
        if (method.isBridge()) return false;
        if (method.getName().startsWith("$$")) return false;
        // CDI spec: @Inject initializer methods are NOT intercepted
        if (method.isAnnotationPresent(jakarta.inject.Inject.class)) return false;
        // Target class interceptor methods (@AroundInvoke etc.) are not business methods
        return !method.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class);
    }

    public record GeneratedInterceptedClass(String className, byte[] bytecode) {
        @Override public boolean equals(Object o) {
            return o instanceof GeneratedInterceptedClass g
                    && className.equals(g.className)
                    && java.util.Arrays.equals(bytecode, g.bytecode);
        }
        @Override public int hashCode() {
            return className.hashCode() ^ java.util.Arrays.hashCode(bytecode);
        }
        @Override public String toString() {
            return "GeneratedInterceptedClass[" + className + ", " + bytecode.length + " bytes]";
        }
    }
}
