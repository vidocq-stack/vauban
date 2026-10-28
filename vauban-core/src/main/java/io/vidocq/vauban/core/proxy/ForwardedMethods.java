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

import io.vidocq.vauban.core.codegen.TypeView;
import io.vidocq.vauban.core.codegen.TypeView.Flag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The class methods a client proxy forwards, on every build path (vauban#227): the run-time and Maven plugin
 * generator judge a {@code Class}, the annotation processor a {@code TypeElement}, both through a {@link TypeView}.
 * Interface default methods are chosen apart, after these.
 *
 * <p>The walk goes from the proxied type up to {@code java.lang.Object}, included: {@code toString()},
 * {@code hashCode()} and {@code equals(Object)} reach the contextual instance too. The most derived declaration of
 * a signature decides, whether the proxy forwards it or not: the declaration it overrides is never forwarded.
 */
public final class ForwardedMethods {

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
     * @param keys     the override key of every method met, forwarded or not: a default method with one of them is
     *                 a class method's, not the interface's
     */
    public record Selection(List<Forward> forwards, Set<String> keys) {}

    /** The class methods a proxy of {@code type}, living where {@code placement} says, forwards. */
    public static Selection select(TypeView type, Placement placement) {
        var keys = new HashSet<String>();
        var forwards = new ArrayList<Forward>();
        for (TypeView c = type; c != null; c = c.superclass()) {
            for (var m : c.methods()) {
                if (keys.add(m.overrideKey()) && isProxyable(m) && isForwarded(type, c, m, placement)) {
                    forwards.add(new Forward(c, m, placement == Placement.TYPE_PACKAGE
                            && !m.is(Flag.PUBLIC) && !c.isSamePackage(type)));
                }
            }
        }
        return new Selection(List.copyOf(forwards), Set.copyOf(keys));
    }

    /**
     * Whether a proxy may override {@code m}: neither static, private, final nor abstract, neither a bridge nor
     * generated plumbing, nor a finalizer or {@code clone()}, which no proxy forwards.
     */
    public static boolean isProxyable(TypeView.Member m) {
        if (m.is(Flag.STATIC) || m.is(Flag.PRIVATE) || m.is(Flag.FINAL) || m.is(Flag.ABSTRACT) || m.isBridge()) {
            return false;
        }
        var name = m.name();
        return !(name.startsWith("$$")
                || (name.equals("finalize") && m.parameterCount() == 0)
                || (name.equals("clone") && m.parameterCount() == 0));
    }

    /**
     * Whether {@code m}, declared by {@code declaring}, is a method of {@code type} the proxy reaches from where it
     * lives. A package-private method is a member of {@code type} only when every class from {@code type} up to
     * {@code declaring} shares that class's runtime package (JLS 8.4.8): {@code A(p) <- B(q) <- C(p)} does not
     * give {@code C} the package-private methods of {@code A}.
     */
    private static boolean isForwarded(TypeView type, TypeView declaring, TypeView.Member m, Placement placement) {
        if (m.is(Flag.PUBLIC)) {
            return true;
        }
        if (placement == Placement.OTHER_PACKAGE) {
            return false;
        }
        return m.is(Flag.PROTECTED) || packagePrivateInherited(type, declaring);
    }

    private static boolean packagePrivateInherited(TypeView type, TypeView declaring) {
        for (TypeView c = type; c != null && !c.isSameType(declaring); c = c.superclass()) {
            if (!c.isSamePackage(declaring)) {
                return false;
            }
        }
        return true;
    }
}
