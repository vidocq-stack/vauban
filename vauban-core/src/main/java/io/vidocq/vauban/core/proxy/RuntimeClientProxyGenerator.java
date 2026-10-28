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

import io.vidocq.vauban.core.codegen.BeanMembers;
import io.vidocq.vauban.core.codegen.ClassTypeView;
import io.vidocq.vauban.core.codegen.ShadowedDefaults;
import io.vidocq.vauban.core.interceptor.TypeRef;
import io.vidocq.vauban.core.proxy.ClientProxyShape.ProxyMethodShape;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime ({@code Class<?>}-driven) front-end of the client-proxy generation: the class methods
 * the proxy forwards, and whether each dispatches through a {@code MethodHandle}, come from
 * {@link ForwardedMethods}, the super constructor from {@link ClientProxyShape#superConstructor},
 * as on the processor's path; the inherited interface default methods are added here. It builds
 * the neutral {@link ClientProxyShape}.
 *
 * <p>The bytecode itself is emitted once for all front-ends by {@link ClientProxyEmitter}.
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

    /**
     * Generate a client proxy for a normal-scoped producer's produced type at an explicit
     * {@code proxyBinaryName} (in the producer's own package), extending {@code producedType}
     * (issue #42, Stage 1.6 — the bytecode counterpart of the APT producer-proxy path). Only call
     * for a produced type deemed {@link ProducerProxyEligibility#eligible eligible}: a cross-package
     * proxy forwards only public methods, so all overridable methods must be public.
     *
     * @param producedType   the fully-public produced class the proxy extends and forwards to
     * @param proxyBinaryName the binary name of the generated proxy (producer's package)
     */
    public static GeneratedProxy generateProducerProxyAt(Class<?> producedType, String proxyBinaryName) {
        ClientProxyShape shape = shapeOf(producedType, false);
        return new GeneratedProxy(proxyBinaryName, ClientProxyEmitter.emitAt(shape, proxyBinaryName));
    }

    /**
     * Builds the neutral shape: hierarchy walk + simplest-ctor defaults + MH decisions. This is the
     * canonical client-proxy shape; the APT/source path ({@code ClientProxyShapeFromElements}) must
     * agree with it on the forwarded method set, which a cross-check test pins.
     */
    public static ClientProxyShape shapeOf(Class<?> beanClass) {
        return shapeOf(beanClass, true);
    }

    /**
     * @param colocated the proxy lives in the bean's package, so an interface of that package
     *                  qualifies to forward a shadowed default method through; a proxy placed in a
     *                  producer's package only uses public ones
     */
    public static ClientProxyShape shapeOf(Class<?> beanClass, boolean colocated) {
        // The proxy is generated in the bean's package (the suffix carries no dot).
        var selection = ForwardedMethods.select(ClassTypeView.of(beanClass), ForwardedMethods.Placement.TYPE_PACKAGE);
        var proxiedSeen = new java.util.HashSet<>(selection.keys());
        var methods = new ArrayList<ProxyMethodShape>();
        for (var forward : selection.forwards()) {
            var method = ClassTypeView.method(forward.method());
            methods.add(new ProxyMethodShape(
                    method.getName(),
                    TypeRef.fromClass(method.getReturnType()),
                    typeRefs(method.getParameterTypes()),
                    typeRefs(method.getExceptionTypes()),
                    forward.needsMethodHandle()));
        }
        addInterfaceDefaults(beanClass, colocated, proxiedSeen, methods);

        // CDI 4.1: beans with only @Inject constructors (no no-arg) must still be proxyable —
        // the proxy's no-arg constructor calls the simplest super ctor with default values.
        var superCtor = findSimplestConstructor(beanClass);
        return new ClientProxyShape(
                beanClass.getName(),
                typeRefs(superCtor.getParameterTypes()),
                methods,
                typeRefs(superCtor.getExceptionTypes()));
    }

    /**
     * The interface default methods no class above overrides: left out, a call on the proxy
     * would run the default body on the proxy itself, bypassing the contextual instance and its
     * interceptors (BUG-20261004-02). getMethods() lists the most specific default of each.
     */
    private static void addInterfaceDefaults(Class<?> beanClass, boolean colocated,
                                             java.util.Set<String> proxiedSeen, List<ProxyMethodShape> methods) {
        for (var method : BeanMembers.inStableOrder(beanClass.getMethods())) {
            if (method.isDefault() && shouldProxy(method)) {
                defaultMethodShape(beanClass, method, colocated, proxiedSeen).ifPresent(methods::add);
            }
        }
    }

    private static java.util.Optional<ProxyMethodShape> defaultMethodShape(Class<?> beanClass, Method method,
                                                                           boolean colocated,
                                                                           java.util.Set<String> proxiedSeen) {
        var key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
        TypeRef interfaceOwner = null;
        if (ShadowedDefaults.isShadowed(beanClass, method)) {
            // A declaration the bean does not inherit (a private superclass method, or a
            // package-private one of another package) holds the key, and a call typed by the
            // bean class would resolve to it and be refused: forward through an interface
            // instead (BUG-20261004-08), or not at all when none qualifies.
            var owner = ShadowedDefaults.accessibleOwner(beanClass, method, colocated);
            proxiedSeen.add(key);
            if (owner == null) {
                return java.util.Optional.empty();
            }
            interfaceOwner = TypeRef.fromClass(owner);
        } else if (!proxiedSeen.add(key)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ProxyMethodShape(
                method.getName(),
                TypeRef.fromClass(method.getReturnType()),
                typeRefs(method.getParameterTypes()),
                typeRefs(method.getExceptionTypes()),
                false, // interface methods are public
                interfaceOwner));
    }

    private static List<TypeRef> typeRefs(Class<?>[] types) {
        var refs = new ArrayList<TypeRef>(types.length);
        for (Class<?> t : types) refs.add(TypeRef.fromClass(t));
        return refs;
    }

    /**
     * The super constructor the proxy chains to ({@link ClientProxyShape#superConstructor}, the rule of every build
     * path), else, when every constructor is private, the first one: no proxy can call it, and the generation fails
     * as it always did.
     */
    private static java.lang.reflect.Constructor<?> findSimplestConstructor(Class<?> beanClass) {
        var chosen = ClassTypeView.of(beanClass).superConstructor();
        return chosen != null ? chosen : beanClass.getDeclaredConstructors()[0];
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

    public record GeneratedProxy(String className, byte[] bytecode) {
        @Override public boolean equals(Object o) {
            return o instanceof GeneratedProxy(var otherName, var otherBytecode)
                    && className.equals(otherName)
                    && java.util.Arrays.equals(bytecode, otherBytecode);
        }
        @Override public int hashCode() {
            return className.hashCode() ^ java.util.Arrays.hashCode(bytecode);
        }
        @Override public String toString() {
            return "GeneratedProxy[" + className + ", " + bytecode.length + " bytes]";
        }
    }
}
