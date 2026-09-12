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
package io.vidocq.vauban.processor.codegen.proxy;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * Decides whether a normal-scoped producer's produced <em>class</em> type can be proxied at build
 * time in the producer's own package (issue #42, Stage 1). This is only correct when the produced
 * type is <strong>fully public</strong>: a proxy placed in the producer's package extends the
 * external type and forwards every client-visible method through a cast to it, which — across a
 * package boundary — can only reach {@code public} members and can only chain to a
 * {@code public}/{@code protected} super constructor.
 *
 * <p>The method set and constructor choice mirror {@link ClientProxyShapeFromElements} exactly, so
 * an {@link Reason#ELIGIBLE} verdict guarantees the shape it produces renders to compilable,
 * correct cross-package source. Non-eligible producers keep the runtime fallback (which needs an
 * {@code opens}) and are the subject of Stage 3 build diagnostics.
 */
public final class ProducerProxyEligibility {

    private ProducerProxyEligibility() {}

    /** Why a produced type is or is not build-time proxyable across a module boundary. */
    public enum Reason {
        ELIGIBLE,
        NOT_A_CLASS,                // interface, enum, record or annotation type
        NOT_PUBLIC,
        FINAL_CLASS,
        SEALED_CLASS,
        ABSTRACT_CLASS,
        INACCESSIBLE_NESTED,        // a non-static nested type
        NO_ACCESSIBLE_CTOR,         // no public/protected constructor to chain super() to
        FINAL_VIRTUALS,             // a non-static final method (CDI 4.1 §3.10: unproxyable)
        PROTECTED_VIRTUALS,         // a protected overridable method: not forwardable cross-package
        PACKAGE_PRIVATE_VIRTUALS;   // a package-private overridable method: not forwardable cross-package

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
    public static Reason of(TypeElement type) {
        if (type.getKind() != ElementKind.CLASS) {
            return Reason.NOT_A_CLASS;
        }
        var mods = type.getModifiers();
        if (!mods.contains(Modifier.PUBLIC)) {
            return Reason.NOT_PUBLIC;
        }
        if (mods.contains(Modifier.FINAL)) {
            return Reason.FINAL_CLASS;
        }
        if (mods.contains(Modifier.SEALED)) {
            return Reason.SEALED_CLASS;
        }
        if (mods.contains(Modifier.ABSTRACT)) {
            return Reason.ABSTRACT_CLASS;
        }
        if (type.getNestingKind() != NestingKind.TOP_LEVEL && !mods.contains(Modifier.STATIC)) {
            return Reason.INACCESSIBLE_NESTED;
        }

        // The constructor the shape will chain super() to must be reachable from the producer's
        // package — i.e. public or protected (a subclass may invoke a protected super ctor).
        // Mirror ClientProxyShapeFromElements.superCtorParams: (ProxyLink) first, else fewest params.
        ExecutableElement chosen = null;
        for (ExecutableElement c : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (c.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            if (ClientProxyShapeFromElements.isProxyLinkConstructor(c)) {
                chosen = c;
                break;
            }
            if (chosen == null || c.getParameters().size() < chosen.getParameters().size()) {
                chosen = c;
            }
        }
        if (chosen == null) {
            return Reason.NO_ACCESSIBLE_CTOR;
        }
        var cm = chosen.getModifiers();
        if (!cm.contains(Modifier.PUBLIC) && !cm.contains(Modifier.PROTECTED)) {
            return Reason.NO_ACCESSIBLE_CTOR;
        }

        // Every client-visible overridable method must be forwardable across the package boundary,
        // i.e. public. Non-static final methods make the type unproxyable per CDI 4.1 §3.10.
        //
        // The walk covers INHERITED methods too (up to, but excluding, java.lang.Object), mirroring
        // the bytecode path: a method inherited from a superclass is neither declared here nor
        // overridden by the proxy, so judging only declared methods would hand out a proxy that
        // silently runs the superclass body against the proxy's own empty state.
        var seen = new java.util.HashSet<String>();
        for (TypeElement c = type; c != null && !isObject(c); c = superclassOf(c)) {
            for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
                var mm = m.getModifiers();
                if (mm.contains(Modifier.STATIC) || mm.contains(Modifier.PRIVATE)) {
                    continue;
                }
                if (m.getSimpleName().toString().startsWith("$$")) {
                    continue;
                }
                // A subclass override already judged wins: it is the one the proxy overrides.
                if (!seen.add(signatureKey(m))) {
                    continue;
                }
                if (mm.contains(Modifier.FINAL)) {
                    return Reason.FINAL_VIRTUALS;
                }
                if (mm.contains(Modifier.ABSTRACT)) {
                    continue; // the class is concrete; defensively ignore
                }
                if (!mm.contains(Modifier.PUBLIC)) {
                    return mm.contains(Modifier.PROTECTED)
                            ? Reason.PROTECTED_VIRTUALS
                            : Reason.PACKAGE_PRIVATE_VIRTUALS;
                }
            }
        }
        return Reason.ELIGIBLE;
    }

    /** {@code true} when {@code t} is {@code java.lang.Object} — the walk stops there. */
    private static boolean isObject(TypeElement t) {
        return t.getQualifiedName().contentEquals("java.lang.Object");
    }

    /** The superclass element, or {@code null} at the top of the hierarchy. */
    private static TypeElement superclassOf(TypeElement t) {
        return t.getSuperclass() instanceof javax.lang.model.type.DeclaredType dt
                && dt.asElement() instanceof TypeElement se ? se : null;
    }

    /** Name plus parameter types — enough to spot an override while walking upwards. */
    private static String signatureKey(ExecutableElement m) {
        var sb = new StringBuilder(m.getSimpleName().toString()).append('(');
        for (var p : m.getParameters()) {
            sb.append(p.asType().toString()).append(',');
        }
        return sb.append(')').toString();
    }
}
