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

import io.vidocq.vauban.core.codegen.TypeMembers;
import io.vidocq.vauban.core.codegen.TypeView;
import io.vidocq.vauban.core.codegen.TypeView.Flag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The class methods a client proxy forwards, on every build path (vauban#227): the run-time and Maven plugin
 * generator judge a {@code Class}, the annotation processor a {@code TypeElement}, both through a {@link TypeView}.
 * Interface default methods are chosen apart, after these.
 *
 * <p>The walk goes from the proxied type up to {@code java.lang.Object}, included: {@code toString()},
 * {@code hashCode()} and {@code equals(Object)} reach the contextual instance too. The most derived declaration of
 * a signature as a member of the proxied type ({@link TypeView.Member#overrideKey}) decides, whether the proxy
 * forwards it or not: the declaration it overrides is never forwarded. A bridge takes no part, as in the language
 * model, which has none: the method it stands for decides (BUG-20261117-49).
 */
public final class ForwardedMethods {

    /** The serialization hook every client proxy declares, writing its delegate in its place. */
    public static final String WRITE_REPLACE = "writeReplace";

    /** The return type, as an erased canonical name, of the serialization hooks serialization calls. */
    public static final String OBJECT = "java.lang.Object";

    private static final String READ_RESOLVE = "readResolve";

    private ForwardedMethods() {}

    /** Where the proxy lives, relative to the type it proxies. */
    public enum Placement {
        /**
         * In the proxied type's own package: the bean's proxy, or a producer's proxy placed there. Every member of
         * the type is forwarded, an inherited protected one of another package through a {@code MethodHandle}.
         */
        TYPE_PACKAGE,
        /**
         * In another package, a producer's: only public methods can be forwarded, and
         * {@link ProducerProxyEligibility} keeps a type with any other overridable method away from this placement.
         */
        OTHER_PACKAGE
    }

    /**
     * A method the proxy forwards.
     *
     * @param declaring         the class that declares it, the proxied type or one of its superclasses
     * @param method            the declaration
     * @param needsMethodHandle {@code delegate.m()} is refused from the proxy's package, as for a protected method of
     *                          a superclass in another package (JLS 6.6.2): the proxy calls it through a
     *                          {@code MethodHandle}
     */
    public record Forward(TypeView declaring, TypeView.Member method, boolean needsMethodHandle) {}

    /**
     * What the walk found.
     *
     * @param forwards the methods the proxy forwards, in the order the model lists them, class by class upwards
     * @param keys     the override key of every method met but a bridge, forwarded or not: a default method with one
     *                 of them is a class method's, not the interface's
     */
    public record Selection(List<Forward> forwards, Set<String> keys) {}

    /** The class methods a proxy of {@code type}, living where {@code placement} says, forwards. */
    public static Selection select(TypeView type, Placement placement) {
        var keys = new HashSet<String>();
        var forwards = new ArrayList<Forward>();
        for (TypeView c = type; c != null; c = c.superclass()) {
            for (var m : c.methods()) {
                // A bridge listed first took the key of the method it stands for, which was then left out, and a
                // call ran on the proxy instance (BUG-20261117-49).
                if (!m.isBridge() && keys.add(m.overrideKey()) && isProxyable(m)
                        && isForwarded(type, c, m, placement)) {
                    forwards.add(new Forward(c, m, placement == Placement.TYPE_PACKAGE
                            && !m.is(Flag.PUBLIC) && !c.isSamePackage(type)));
                }
            }
        }
        return new Selection(List.copyOf(forwards), Set.copyOf(keys));
    }

    /**
     * The first final method, neither static nor private, of {@code type} or of a superclass: what makes a class
     * unproxyable (CDI 4.1 §3.10, "non-static, final methods with public, protected or default visibility"). The walk
     * goes from the type up to {@code java.lang.Object}, excluded, and counts a package-private method of another
     * package too: code of that package calls it on a proxy as on any instance (BUG-20261118-19). A bridge or a
     * synthetic method is the compiler's, and never counts.
     */
    public static Optional<TypeView.Member> firstFinalMethod(TypeView type) {
        for (TypeView c = type; c != null && !c.isObject(); c = c.superclass()) {
            for (var m : c.methods()) {
                if (m.is(Flag.FINAL) && !m.is(Flag.STATIC) && !m.is(Flag.PRIVATE) && !m.isBridge()) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The class a client proxy of {@code target} extends: {@code target} itself, unless it is final (BUG-20261118-21)
     * or has a final method ({@link #firstFinalMethod}), which no subclass can forward. Then the nearest superclass
     * that has none and that a proxy can extend: in the proxy's package ({@code proxyInTargetPackage} and the same
     * package as {@code target}), or fit to be extended from another one ({@link ProducerProxyEligibility}); at the
     * end, {@code java.lang.Object}. The proxy implements the interfaces of {@code target} besides, so a call typed by
     * one of them, a final method implementing it included, reaches the contextual instance; a class below the one it
     * extends is unproxyable as a requested type, as CDI 4.1 §3.10 says (BUG-20261118-20). Weld picks the same
     * superclass for the requested type.
     */
    public static TypeView proxySuperclass(TypeView target, boolean proxyInTargetPackage) {
        if (!target.is(Flag.FINAL) && firstFinalMethod(target).isEmpty()) {
            return target;
        }
        TypeView c = target.superclass();
        if (c == null) {
            return target;
        }
        // The nearest class above with no final method that a proxy can extend; a class it cannot extend is passed
        // over, not the end of the walk: one above it may do (review round 6).
        while (!c.isObject() && (firstFinalMethod(c).isPresent() || !extendable(c, target, proxyInTargetPackage))) {
            c = c.superclass();
        }
        return c;
    }

    /** Whether a proxy placed as {@code proxyInTargetPackage} says may extend {@code superclass} of {@code target}. */
    private static boolean extendable(TypeView superclass, TypeView target, boolean proxyInTargetPackage) {
        if (proxyInTargetPackage && superclass.isSamePackage(target)) {
            return ClientProxyShape.superConstructor(superclass) != null;
        }
        return ProducerProxyEligibility.of(superclass).eligible();
    }

    /**
     * Whether a proxy may override {@code m}: neither static, private nor final, neither a bridge nor generated
     * plumbing, nor a finalizer or {@code clone()}, nor a serialization hook ({@link #isSerialHook}), which no proxy
     * forwards. An abstract method is forwarded like any other: the proxy never calls {@code super}, and the
     * contextual instance implements it. Only an abstract proxied type has one the walk reaches, since a concrete
     * class overrides every abstract method above it, and CDI 4.1 §3.10 does not count an abstract class as
     * unproxyable (BUG-20261114-02).
     */
    public static boolean isProxyable(TypeView.Member m) {
        if (m.is(Flag.STATIC) || m.is(Flag.PRIVATE) || m.is(Flag.FINAL) || m.isBridge()) {
            return false;
        }
        var name = m.name();
        return !(name.startsWith("$$")
                || (name.equals("finalize") && m.parameterCount() == 0)
                || (name.equals("clone") && m.parameterCount() == 0)
                || isSerialHook(name, m.parameterCount()));
    }

    /**
     * Whether a method named {@code name} with {@code parameterCount} parameters is a serialization hook no proxy
     * forwards: {@code writeReplace()} or {@code readResolve()}. A client proxy declares its own
     * {@code Object writeReplace()}, which writes its delegate in its place (foy#114), so a forwarded one would be a
     * duplicate; and a proxy is never read back, so a forwarded {@code readResolve()} would never run. Whatever their
     * return type: serialization finds a hook through {@code Class.getDeclaredMethod}, which picks the most specific
     * return type, so a forwarded {@code String writeReplace()} next to the proxy's own would hide it, and the proxy
     * would be written as itself, without its delegate.
     */
    public static boolean isSerialHook(String name, int parameterCount) {
        return parameterCount == 0 && (name.equals(WRITE_REPLACE) || name.equals(READ_RESOLVE));
    }

    /**
     * Whether a class of {@code type} exposes {@code Object writeReplace()} to a proxy placed where {@code placement}
     * says: the proxy then inherits it, and its own {@code writeReplace()} overrides it, so it must be at least as
     * accessible (JLS 8.4.8.3) and declare no checked exception the inherited one does not. Interface methods are the
     * front-ends' to add: this walk sees the classes only.
     */
    public static boolean exposesWriteReplace(TypeView type, Placement placement) {
        return inheritedWriteReplace(type, placement)
                .filter(m -> !m.is(Flag.STATIC) && OBJECT.equals(m.returnType())).isPresent();
    }

    /**
     * The no-arg {@code writeReplace()}, static or not, of a class of {@code type} that a proxy placed where
     * {@code placement} says inherits. The most derived declaration decides; a bridge stands for the method it calls.
     * In the type's package the proxy inherits what the type does ({@link TypeMembers#isInherited}); in another one,
     * a public or protected method, as any subclass does. Java source cannot declare the proxy's own
     * {@code Object writeReplace()} next to a static one, which an instance method cannot override (JLS 8.4.8.1), nor
     * next to one returning another type (JLS 8.4.8.3); bytecode can. Interface methods are the front-ends' to add:
     * this walk sees the classes only.
     */
    public static Optional<TypeView.Member> inheritedWriteReplace(TypeView type, Placement placement) {
        for (TypeView c = type; c != null; c = c.superclass()) {
            for (var m : c.methods()) {
                if (isWriteReplace(m)) {
                    return isInheritedByProxy(type, c, m, placement) ? Optional.of(m) : Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Whether {@code m} is a no-arg {@code writeReplace()} itself, not a bridge that stands for it. */
    private static boolean isWriteReplace(TypeView.Member m) {
        return m.name().equals(WRITE_REPLACE) && m.parameterCount() == 0 && !m.isBridge();
    }

    /**
     * Whether a proxy placed where {@code placement} says inherits {@code m}, declared by {@code declaring}: what
     * {@code type} inherits in its package, a public or protected method in another one.
     */
    private static boolean isInheritedByProxy(TypeView type, TypeView declaring, TypeView.Member m,
            Placement placement) {
        return placement == Placement.TYPE_PACKAGE
                ? TypeMembers.isInherited(type, declaring, m)
                : m.is(Flag.PUBLIC) || m.is(Flag.PROTECTED);
    }

    /**
     * Whether {@code m}, declared by {@code declaring}, is a method of {@code type} ({@link TypeMembers#isMemberOf})
     * the proxy reaches from where it lives.
     */
    private static boolean isForwarded(TypeView type, TypeView declaring, TypeView.Member m, Placement placement) {
        return placement == Placement.TYPE_PACKAGE ? TypeMembers.isMemberOf(type, declaring, m) : m.is(Flag.PUBLIC);
    }
}
