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
package io.vidocq.vauban.core.proxy;

import io.vidocq.vauban.core.interceptor.TypeRef;
import io.vidocq.vauban.core.proxy.ClientProxyShape.ProxyMethodShape;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime ({@code Class<?>}-driven) front-end of the client-proxy generation: walks the
 * full class hierarchy, selects the proxied methods, decides per method whether the
 * override must dispatch through a {@code MethodHandle} (JVMS §4.10.1.9 — protected or
 * package-private member declared in another runtime package), picks the simplest
 * non-private super constructor, and builds the neutral {@link ClientProxyShape}.
 *
 * <p>The bytecode itself is emitted once for all front-ends by {@link ClientProxyEmitter};
 * the compile-time {@code ClientProxyGenerator} in {@code vauban-processor} builds the same
 * shape from the indexer {@code ClassInfo} (declared methods only, no-arg constructor).
 */
public final class RuntimeClientProxyGenerator {

    private RuntimeClientProxyGenerator() {}

    /**
     * Returns the deterministic proxy class name for a given bean class.
     * Useful for build-time pre-generation: the runtime will look for this exact name.
     */
    public static String proxyClassName(Class<?> beanClass) {
        return beanClass.getName() + ClientProxyShape.PROXY_SUFFIX;
    }

    /**
     * Generate a client proxy class for the given bean class.
     * The generated class name is deterministic: {@code BeanClass_ClientProxy}.
     *
     * @param beanClass the bean class to proxy
     * @return the generated class name and bytecode
     */
    public static GeneratedProxy generate(Class<?> beanClass) {
        ClientProxyShape shape = shapeOf(beanClass);
        return new GeneratedProxy(shape.proxyClassName(), ClientProxyEmitter.emit(shape));
    }

    /** Builds the neutral shape: hierarchy walk + simplest-ctor defaults + MH decisions. */
    private static ClientProxyShape shapeOf(Class<?> beanClass) {
        // The proxy is generated in the bean's package (the suffix carries no dot).
        String proxyPackage = packageOf(beanClass.getName());
        var proxiedSeen = new java.util.HashSet<String>();
        var methods = new ArrayList<ProxyMethodShape>();
        var current = beanClass;
        while (current != null) {
            for (var method : current.getDeclaredMethods()) {
                var key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                if (proxiedSeen.add(key) && shouldProxy(method)) {
                    methods.add(new ProxyMethodShape(
                            method.getName(),
                            TypeRef.fromClass(method.getReturnType()),
                            typeRefs(method.getParameterTypes()),
                            typeRefs(method.getExceptionTypes()),
                            needsMethodHandleDispatch(method, proxyPackage)));
                }
            }
            current = current.getSuperclass();
        }

        // CDI 4.1: beans with only @Inject constructors (no no-arg) must still be proxyable —
        // the proxy's no-arg constructor calls the simplest super ctor with default values.
        var superCtor = findSimplestConstructor(beanClass);
        return new ClientProxyShape(
                beanClass.getName(),
                typeRefs(superCtor.getParameterTypes()),
                methods);
    }

    private static List<TypeRef> typeRefs(Class<?>[] types) {
        var refs = new ArrayList<TypeRef>(types.length);
        for (Class<?> t : types) refs.add(TypeRef.fromClass(t));
        return refs;
    }

    /**
     * Find the simplest non-private constructor (fewest parameters).
     * Prefers no-arg, then smallest parameter count.
     */
    private static java.lang.reflect.Constructor<?> findSimplestConstructor(Class<?> beanClass) {
        java.lang.reflect.Constructor<?> best = null;
        for (var ctor : beanClass.getDeclaredConstructors()) {
            if (java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) continue;
            if (best == null || ctor.getParameterCount() < best.getParameterCount()) {
                best = ctor;
            }
        }
        if (best != null) return best;
        // All constructors are private — use the first one (proxy generation will still work
        // since the proxy class is in the same package)
        return beanClass.getDeclaredConstructors()[0];
    }

    private static boolean shouldProxy(Method method) {
        if (Modifier.isStatic(method.getModifiers())) return false;
        if (Modifier.isPrivate(method.getModifiers())) return false;
        if (Modifier.isFinal(method.getModifiers())) return false;
        if (method.isSynthetic()) return false;
        if (method.isBridge()) return false;
        if (method.getName().startsWith("$$")) return false;
        if (method.getName().equals("finalize") && method.getParameterCount() == 0) return false;
        return !(method.getName().equals("clone") && method.getParameterCount() == 0);
    }

    /**
     * A protected method declared in a superclass in a different package cannot be
     * invoked via {@code invokevirtual} when the stack-top receiver type is not assignable
     * to the current class (JVMS §4.10.1.9). We route those through a {@link java.lang.invoke.MethodHandle}.
     * Package-private methods follow the same rule when crossing package boundaries.
     */
    private static boolean needsMethodHandleDispatch(Method method, String proxyPackage) {
        int mods = method.getModifiers();
        if (Modifier.isPublic(mods)) return false;
        String declaringPackage = packageOf(method.getDeclaringClass().getName());
        return !declaringPackage.equals(proxyPackage);
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    public record GeneratedProxy(String className, byte[] bytecode) {
        @Override public boolean equals(Object o) {
            return o instanceof GeneratedProxy g
                    && className.equals(g.className)
                    && java.util.Arrays.equals(bytecode, g.bytecode);
        }
        @Override public int hashCode() {
            return className.hashCode() ^ java.util.Arrays.hashCode(bytecode);
        }
        @Override public String toString() {
            return "GeneratedProxy[" + className + ", " + bytecode.length + " bytes]";
        }
    }
}
