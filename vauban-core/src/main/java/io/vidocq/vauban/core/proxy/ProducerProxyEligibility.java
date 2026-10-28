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

import io.vidocq.vauban.core.codegen.ClassTypeView;
import io.vidocq.vauban.core.codegen.TypeView;
import io.vidocq.vauban.core.codegen.TypeView.Flag;

import java.util.HashSet;
import java.util.List;

/**
 * The "fully-public produced type" predicate (issue #42), the one rule of every build path: the
 * Maven plugin and the run-time fallback judge a {@link Class}, the annotation processor a
 * {@code TypeElement}, both through a {@link TypeView}. A produced class type can be proxied at build time in the
 * producer's own package only when it is fully public, since a cross-package proxy forwards every
 * method through a cast to the external type (reaching only public members) and chains to a
 * public/protected super constructor.
 */
public final class ProducerProxyEligibility {

    private ProducerProxyEligibility() {}

    /** Why a produced type is or is not build-time proxyable across a module boundary. */
    public enum Reason {
        ELIGIBLE,
        NOT_A_CLASS,
        NOT_PUBLIC,
        FINAL_CLASS,
        SEALED_CLASS,
        ABSTRACT_CLASS,
        INACCESSIBLE_NESTED,
        NO_ACCESSIBLE_CTOR,
        FINAL_VIRTUALS,
        PROTECTED_VIRTUALS,
        PACKAGE_PRIVATE_VIRTUALS;

        public boolean eligible() {
            return this == ELIGIBLE;
        }

        /**
         * Whether a proxy for this verdict becomes possible once it sits <em>inside the produced
         * type's own package</em>. A non-public overridable member and an inaccessible constructor
         * are cross-package obstacles only: co-located, the proxy overrides and forwards them. A
         * final or sealed class, a final method and an abstract class are unproxyable anywhere
         * (CDI 4.1 §3.10), and nothing is shipped for a non-public or a non-static nested type.
         */
        public boolean placeable() {
            return switch (this) {
                case PROTECTED_VIRTUALS, PACKAGE_PRIVATE_VIRTUALS, NO_ACCESSIBLE_CTOR -> true;
                default -> false;
            };
        }
    }

    /** The verdict for {@code type}. */
    public static Reason of(Class<?> type) {
        return of(ClassTypeView.of(type));
    }

    /**
     * The verdict for {@code type}, whatever model it comes from: the same rule judges a {@link Class} (run-time
     * fallback, Maven plugin) and a {@code TypeElement} (annotation processor), so both build paths agree
     * (vauban#227, BUG-20261028-02).
     */
    public static Reason of(TypeView type) {
        var verdict = declarationVerdict(type);
        if (!verdict.eligible()) {
            return verdict;
        }
        verdict = constructorVerdict(type);
        if (!verdict.eligible()) {
            return verdict;
        }
        return methodsVerdict(type);
    }

    /** What the declaration of {@code type} alone says: its kind, its modifiers, its nesting. */
    private static Reason declarationVerdict(TypeView type) {
        if (type.kind() != TypeView.Kind.CLASS) {
            return Reason.NOT_A_CLASS;
        }
        if (!type.is(Flag.PUBLIC)) {
            return Reason.NOT_PUBLIC;
        }
        if (type.is(Flag.FINAL)) {
            return Reason.FINAL_CLASS;
        }
        if (type.is(Flag.SEALED)) {
            return Reason.SEALED_CLASS;
        }
        if (type.is(Flag.ABSTRACT)) {
            return Reason.ABSTRACT_CLASS;
        }
        if (type.isNested() && !type.is(Flag.STATIC)) {
            return Reason.INACCESSIBLE_NESTED;
        }
        return Reason.ELIGIBLE;
    }

    /**
     * The constructor the shape chains super() to must be reachable from another package — public
     * or protected.
     */
    private static Reason constructorVerdict(TypeView type) {
        var chosen = ClientProxyShape.superConstructor(type);
        if (chosen == null) {
            return Reason.NO_ACCESSIBLE_CTOR;
        }
        return chosen.is(Flag.PUBLIC) || chosen.is(Flag.PROTECTED) ? Reason.ELIGIBLE : Reason.NO_ACCESSIBLE_CTOR;
    }

    /**
     * Every overridable method the proxy would forward (walking the hierarchy, as the bytecode
     * shape does) must be public; a non-static final method makes the type unproxyable (§3.10).
     * Walking upwards, the most derived override of a signature decides: the method it overrides is
     * not the one the proxy forwards. A bridge marks the signature it stands for as overridden. The
     * worst obstacle is the verdict, whatever the order the model lists the methods in: a final
     * method, then a package-private one, then a protected one.
     */
    private static Reason methodsVerdict(TypeView type) {
        var seen = new HashSet<String>();
        var verdict = Reason.ELIGIBLE;
        for (TypeView c = type; c != null; c = c.superclass()) {
            for (var m : c.methods()) {
                // A private or static method overrides nothing; a signature already met is overridden.
                if (m.is(Flag.STATIC) || m.is(Flag.PRIVATE) || !seen.add(m.overrideKey()) || !isForwarded(m)) {
                    continue;
                }
                verdict = worse(verdict, methodVerdict(m));
            }
        }
        return verdict;
    }

    private static Reason methodVerdict(TypeView.Member m) {
        if (m.is(Flag.FINAL)) {
            return Reason.FINAL_VIRTUALS;
        }
        if (m.is(Flag.ABSTRACT) || m.is(Flag.PUBLIC)) {
            return Reason.ELIGIBLE;
        }
        return m.is(Flag.PROTECTED) ? Reason.PROTECTED_VIRTUALS : Reason.PACKAGE_PRIVATE_VIRTUALS;
    }

    /** The worse of two method verdicts: a final method, then a package-private one, then a protected one. */
    private static Reason worse(Reason a, Reason b) {
        return METHOD_SEVERITY.indexOf(a) >= METHOD_SEVERITY.indexOf(b) ? a : b;
    }

    private static final List<Reason> METHOD_SEVERITY = List.of(Reason.ELIGIBLE, Reason.PROTECTED_VIRTUALS,
            Reason.PACKAGE_PRIVATE_VIRTUALS, Reason.FINAL_VIRTUALS);

    /** Whether a proxy forwards {@code m}: neither a bridge, nor generated plumbing, nor a finalizer or clone. */
    private static boolean isForwarded(TypeView.Member m) {
        var name = m.name();
        return !(m.isBridge() || name.startsWith("$$")
                || (name.equals("finalize") && m.parameterCount() == 0)
                || (name.equals("clone") && m.parameterCount() == 0));
    }
}
