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

import io.vidocq.vauban.core.codegen.BeanMembers;
import io.vidocq.vauban.core.codegen.ShadowedDefaults;

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
     * <p>Iteration order is that of the original pre-refactor code, the inherited non-public
     * methods added last so the shape of a bean that has none is unchanged. Within each group the
     * methods come in {@link BeanMembers#STABLE_ORDER} rather than in reflection order, which is
     * unspecified and depends on the classes the JVM loaded before (BUG-20261007-02). Constructors
     * stay in reflection order: they all share one name, so the symbol order cannot move them:
     * <ol>
     *   <li>Constructors via {@link Class#getDeclaredConstructors()}, skipping private ones.</li>
     *   <li>Declared methods via {@link Class#getDeclaredMethods()} filtered by
     *       {@link #shouldIntercept(Method)}.</li>
     *   <li>Inherited public methods via {@link Class#getMethods()}, skipping those whose
     *       declaring class is the bean itself (already covered) or {@code Object}, and deduplicating
     *       by {@code name + Arrays.toString(parameterTypes)}, filtered by {@link #shouldIntercept(Method)}.</li>
     *   <li>Inherited protected and package-private methods, walking the superclasses (see
     *       {@code addInheritedNonPublicMethods}), so the set matches the processor's front-end.</li>
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

        for (var method : BeanMembers.inStableOrder(beanClass.getDeclaredMethods())) {
            if (shouldIntercept(method)) {
                var shape = methodShapeOf(beanClass, method);
                if (shape != null) methods.add(shape);
                seen.add(method.getName() + Arrays.toString(method.getParameterTypes()));
            }
        }
        // Also intercept inherited public methods (from superclasses)
        for (var method : BeanMembers.inStableOrder(beanClass.getMethods())) {
            if (method.getDeclaringClass() == beanClass) continue; // already handled
            if (method.getDeclaringClass() == Object.class) continue;
            var key = method.getName() + Arrays.toString(method.getParameterTypes());
            if (seen.contains(key)) continue; // already intercepted
            if (shouldIntercept(method)) {
                var shape = methodShapeOf(beanClass, method);
                if (shape == null) {
                    seen.add(key);
                    continue;
                }
                if (ShadowedDefaults.isShadowed(beanClass, method)) {
                    // super.<name>() would resolve to the shadowing declaration: the bridge reaches
                    // the default through an interface the subclass lists (BUG-20261004-08), or,
                    // when no interface it may list carries it, the method is left alone.
                    var owner = ShadowedDefaults.accessibleOwner(beanClass, method, true);
                    seen.add(key);
                    if (owner == null) continue;
                    shape = shape.withDefaultOwner(TypeRef.fromClass(owner));
                }
                methods.add(shape);
                seen.add(key);
            }
        }
        // And the inherited protected and package-private ones, which getMethods() leaves out:
        // business methods too, and intercepted by the processor's subclass (BUG-20261004-03).
        addInheritedNonPublicMethods(beanClass, methods, seen);

        return new InterceptedShape(beanBinaryName, ctors, methods);
    }

    /**
     * The protected and package-private methods {@code beanClass} inherits from its superclasses,
     * as {@code Elements#getAllMembers} lists them for the processor's front-end: the most derived
     * declaration of a signature decides (a {@code final} or {@code private} one hides the rest), and
     * a package-private method is inherited only when every class from the bean up to its declaring
     * class shares that class's runtime package (JLS 8.4.8) — the only case a subclass can override.
     */
    private static void addInheritedNonPublicMethods(Class<?> beanClass, List<MethodShape> methods,
            java.util.Set<String> seen) {
        var declared = new java.util.HashSet<String>();
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            var declaredHere = new ArrayList<String>();
            for (var method : BeanMembers.inStableOrder(c.getDeclaredMethods())) {
                var key = method.getName() + Arrays.toString(method.getParameterTypes());
                declaredHere.add(key);
                if (c == beanClass || declared.contains(key) || seen.contains(key)) continue;
                // Public ones came from getMethods(); the rest only when the bean inherits them.
                if (!Modifier.isPublic(method.getModifiers())
                        && BusinessMethods.isBusinessMethodOf(beanClass, method) && shouldIntercept(method)) {
                    var shape = methodShapeOf(beanClass, method);
                    if (shape != null) methods.add(shape);
                    seen.add(key);
                }
            }
            declared.addAll(declaredHere);
        }
    }

    // ---- helpers ----

    /**
     * {@code method}'s shape in the subclass of {@code beanClass}; {@code null} — not intercepted —
     * when its return type is a class the subclass may not access: the value an interceptor chain
     * returns is an {@code Object}, and no instruction of a class outside that class's package can
     * type it so (the {@code checkcast} throws {@code IllegalAccessError}, and so does a method
     * handle call whose descriptor names that class). Flagged when a parameter is such a class,
     * which the emitter handles without resolving it (BUG-20261004-09). The processor's front-end
     * decides alike ({@code InterceptedShapeFromElements#resolvableFrom}).
     */
    private static MethodShape methodShapeOf(Class<?> beanClass, Method method) {
        if (!accessibleFrom(method.getReturnType(), beanClass)) return null;
        TypeRef returnType = TypeRef.fromClass(method.getReturnType());
        var params = new ArrayList<TypeRef>();
        boolean inaccessible = false;
        for (Class<?> p : method.getParameterTypes()) {
            params.add(TypeRef.fromClass(p));
            inaccessible |= !accessibleFrom(p, beanClass);
        }
        var shape = new MethodShape(method.getName(), returnType, params);
        return inaccessible ? shape.withInaccessibleTypes() : shape;
    }

    /**
     * Whether a class generated in {@code beanClass}'s runtime package and module may access
     * {@code type} (JVMS 5.4.4): a primitive; a class of the same runtime package, whatever its
     * modifiers — a private nested class is package access in its class file; else a class public
     * in its class file — declared public, or a protected member class — in a package its module
     * exports to the bean's module, which reads it. An array, as its element type.
     */
    static boolean accessibleFrom(Class<?> type, Class<?> beanClass) {
        while (type.isArray()) type = type.getComponentType();
        if (type.isPrimitive()) return true;
        if (type.getClassLoader() == beanClass.getClassLoader()
                && type.getPackageName().equals(beanClass.getPackageName())) {
            return true;
        }
        int modifiers = type.getModifiers();
        boolean classFilePublic = Modifier.isPublic(modifiers)
                || (type.isMemberClass() && Modifier.isProtected(modifiers));
        if (!classFilePublic) return false;
        var from = beanClass.getModule();
        return from.canRead(type.getModule()) && type.getModule().isExported(type.getPackageName(), from);
    }

    static boolean shouldIntercept(Method method) {
        if (Modifier.isStatic(method.getModifiers())) return false;
        if (Modifier.isPrivate(method.getModifiers())) return false;
        if (Modifier.isFinal(method.getModifiers())) return false;
        if (method.isSynthetic()) return false;
        if (method.isBridge()) return false;
        if (method.getName().startsWith("$$")) return false;
        // @Inject initializers, the target class's interceptor methods and lifecycle callbacks are not
        // business methods: one list, shared with the processor's front-end
        return !io.vidocq.vauban.core.codegen.BeanMembers.hasNonBusinessMethodAnnotation(method);
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
