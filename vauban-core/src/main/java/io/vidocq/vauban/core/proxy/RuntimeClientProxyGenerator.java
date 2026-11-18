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
import io.vidocq.vauban.core.codegen.BridgeCalls;
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
        // Named after the bean, which the shape may not extend (ForwardedMethods.proxySuperclass).
        var proxyName = proxyClassName(beanClass);
        return new GeneratedProxy(proxyName, ClientProxyEmitter.emitAt(shape, proxyName));
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
        ClientProxyShape shape = producerShapeAt(producedType, proxyBinaryName);
        return new GeneratedProxy(proxyBinaryName, ClientProxyEmitter.emitAt(shape, proxyBinaryName));
    }

    /** The shape {@link #generateProducerProxyAt} emits: the walk placed where {@code proxyBinaryName} lives. */
    public static ClientProxyShape producerShapeAt(Class<?> producedType, String proxyBinaryName) {
        return shapeAt(producedType, false, placementOf(producedType, proxyBinaryName));
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
        return shapeAt(beanClass, colocated, ForwardedMethods.Placement.TYPE_PACKAGE);
    }

    /**
     * Where a producer's proxy named {@code proxyBinaryName} lives relative to {@code producedType}: in its package,
     * or in another one, where the walk forwards public methods only, as the processor's does. Stated rather than
     * left to {@link ProducerProxyEligibility}, which keeps a type with other overridable methods away from it.
     */
    static ForwardedMethods.Placement placementOf(Class<?> producedType, String proxyBinaryName) {
        int dot = proxyBinaryName.lastIndexOf('.');
        var proxyPackage = dot < 0 ? "" : proxyBinaryName.substring(0, dot);
        return proxyPackage.equals(producedType.getPackageName())
                ? ForwardedMethods.Placement.TYPE_PACKAGE : ForwardedMethods.Placement.OTHER_PACKAGE;
    }

    /** The shape of a proxy of {@code beanClass} placed as {@code placement} says. */
    private static ClientProxyShape shapeAt(Class<?> beanClass, boolean colocated,
            ForwardedMethods.Placement placement) {
        var inPackage = placement == ForwardedMethods.Placement.TYPE_PACKAGE;
        var superclass = ((ClassTypeView) ForwardedMethods.proxySuperclass(ClassTypeView.of(beanClass), inPackage))
                .type();
        if (superclass != beanClass) {
            return beyondFinalMethod(beanClass, superclass, colocated, placement);
        }
        var type = ClassTypeView.of(beanClass);
        var selection = ForwardedMethods.select(type, placement);
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
        addSuperCallingBridges(beanClass, methods);

        // CDI 4.1: beans with only @Inject constructors (no no-arg) must still be proxyable —
        // the proxy's no-arg constructor calls the simplest super ctor with default values.
        var superCtor = findSimplestConstructor(beanClass);
        return new ClientProxyShape(
                beanClass.getName(),
                typeRefs(superCtor.getParameterTypes()),
                methods,
                typeRefs(superCtor.getExceptionTypes()),
                ForwardedMethods.exposesWriteReplace(type, placement)
                        || inheritsInterfaceWriteReplace(beanClass));
    }

    /**
     * The shape of a proxy of {@code beanClass}, which has a final method, that extends {@code superclass} instead, a
     * class above it with none, and implements the interfaces of {@code beanClass}: the shape of {@code superclass},
     * placed where the proxy of {@code beanClass} lives, and every method of those interfaces, forwarded through its
     * interface (BUG-20261118-20). A final method that implements one of them is reached that way.
     */
    private static ClientProxyShape beyondFinalMethod(Class<?> beanClass, Class<?> superclass, boolean colocated,
            ForwardedMethods.Placement placement) {
        var samePackage = placement == ForwardedMethods.Placement.TYPE_PACKAGE
                && superclass.getPackageName().equals(beanClass.getPackageName());
        var base = shapeAt(superclass, colocated && samePackage,
                samePackage ? placement : ForwardedMethods.Placement.OTHER_PACKAGE);
        var methods = new ArrayList<>(base.methods());
        var declared = new java.util.HashSet<String>();
        for (var m : methods) declared.add(descriptorKey(m));
        var interfaces = new ArrayList<TypeRef>();
        var proxyPackage = placement == ForwardedMethods.Placement.TYPE_PACKAGE ? beanClass.getPackageName() : null;
        for (var implemented : proxiedInterfaces(beanClass, proxyPackage)) {
            interfaces.add(TypeRef.fromClass(implemented));
            addInterfaceMethods(implemented, declared, methods);
        }
        return new ClientProxyShape(superclass.getName(), base.superCtorParams(), methods, base.superCtorThrown(),
                base.overridesWriteReplace() || inheritsInterfaceWriteReplace(beanClass), interfaces);
    }

    /**
     * Adds to {@code methods} each method of {@code implemented}, neither static nor a {@code writeReplace()}, whose
     * descriptor {@code declared} does not hold yet, forwarded through {@code implemented}.
     */
    private static void addInterfaceMethods(Class<?> implemented, java.util.Set<String> declared,
            List<ProxyMethodShape> methods) {
        var owner = TypeRef.fromClass(implemented);
        for (var method : BeanMembers.inStableOrder(implemented.getMethods())) {
            if (Modifier.isStatic(method.getModifiers()) || isObjectWriteReplace(method)) {
                continue;
            }
            var shape = new ProxyMethodShape(method.getName(), TypeRef.fromClass(method.getReturnType()),
                    typeRefs(method.getParameterTypes()), typeRefs(method.getExceptionTypes()), false, owner);
            if (declared.add(descriptorKey(shape))) {
                methods.add(shape);
            }
        }
    }

    /**
     * The interfaces of {@code beanClass} and of its superclasses, with theirs, in the order the classes declare them,
     * that a proxy in {@code proxyPackage} may implement, public ones only when it is {@code null}:
     * {@code java.io.Serializable} aside, which every proxy implements.
     */
    static List<Class<?>> proxiedInterfaces(Class<?> beanClass, String proxyPackage) {
        var found = new java.util.LinkedHashSet<Class<?>>();
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var implemented : c.getInterfaces()) {
                addWithSuperinterfaces(implemented, found);
            }
        }
        found.remove(java.io.Serializable.class);
        found.removeIf(implemented -> !accessibleFrom(implemented, proxyPackage));
        return List.copyOf(found);
    }

    private static void addWithSuperinterfaces(Class<?> implemented, java.util.Set<Class<?>> found) {
        if (found.add(implemented)) {
            for (var parent : implemented.getInterfaces()) {
                addWithSuperinterfaces(parent, found);
            }
        }
    }

    /** Whether code of {@code packageName} may name {@code type}: public, enclosing types included, or its own. */
    private static boolean accessibleFrom(Class<?> type, String packageName) {
        if (type.getPackageName().equals(packageName)) { // a null packageName is no package
            return true;
        }
        for (Class<?> t = type; t != null; t = t.getEnclosingClass()) {
            if (!Modifier.isPublic(t.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code beanClass} inherits {@code Object writeReplace()} from an interface, a default method no class
     * above overrides, or an abstract one an abstract class leaves to its subclasses: public, so the proxy inherits it
     * too.
     */
    private static boolean inheritsInterfaceWriteReplace(Class<?> beanClass) {
        for (var method : beanClass.getMethods()) {
            if (method.getDeclaringClass().isInterface() && !Modifier.isStatic(method.getModifiers())
                    && isObjectWriteReplace(method)) {
                return true;
            }
        }
        return false;
    }

    /** {@code Object writeReplace()}: the name, no parameter, and the return type serialization looks for. */
    private static boolean isObjectWriteReplace(Method method) {
        return method.getName().equals(ForwardedMethods.WRITE_REPLACE) && method.getParameterCount() == 0
                && method.getReturnType() == Object.class;
    }

    /**
     * The interface methods no class above overrides: a default one, which left out would run its
     * body on the proxy itself, bypassing the contextual instance and its interceptors
     * (BUG-20261004-02), and an abstract one an abstract proxied type leaves to its subclasses, which
     * left out would have no body on the proxy at all (BUG-20261114-02). getMethods() lists the most
     * specific of each.
     */
    private static void addInterfaceDefaults(Class<?> beanClass, boolean colocated,
                                             java.util.Set<String> proxiedSeen, List<ProxyMethodShape> methods) {
        for (var method : BeanMembers.inStableOrder(beanClass.getMethods())) {
            if (method.getDeclaringClass().isInterface() && shouldProxy(method)) {
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

    /**
     * The bridges a proxy forwards as they are: javac implements an interface method with a superclass method of
     * another erasure, {@code PlainTagBase.tag(String)} for {@code Tagged<String>.tag(T)}, through a bridge
     * {@code tag(Object)} in the class that brings them together, which calls the superclass method by
     * {@code invokespecial}. An override of {@code tag(String)} alone does not catch a call typed by the interface,
     * which then ran on the proxy (BUG-20261118-17). The class bytes tell such a bridge apart ({@link BridgeCalls});
     * one that calls a final method is left alone, as that method is, and as the processor leaves it.
     */
    private static void addSuperCallingBridges(Class<?> beanClass, List<ProxyMethodShape> methods) {
        var declared = new java.util.HashSet<String>();
        for (var m : methods) declared.add(descriptorKey(m));
        for (var method : BeanMembers.inStableOrder(beanClass.getMethods())) {
            if (method.isBridge() && !method.getDeclaringClass().isInterface() && callsASuperclassMethod(method)) {
                var shape = new ProxyMethodShape(method.getName(), TypeRef.fromClass(method.getReturnType()),
                        typeRefs(method.getParameterTypes()), typeRefs(method.getExceptionTypes()), false);
                if (declared.add(descriptorKey(shape))) {
                    methods.add(shape);
                }
            }
        }
    }

    /** Whether {@code bridge} calls a superclass method, neither final, of another descriptor. */
    private static boolean callsASuperclassMethod(Method bridge) {
        var call = BridgeCalls.of(bridge);
        return call.kind() == BridgeCalls.Kind.SUPER_CALL
                && (call.target() == null || !Modifier.isFinal(call.target().getModifiers()));
    }

    /** The name, parameter types and return type: what a method of a class file is told apart by. */
    static String descriptorKey(ProxyMethodShape m) {
        return m.name() + m.params() + m.returnType();
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
        if (ForwardedMethods.isSerialHook(method.getName(), method.getParameterCount())) return false;
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
