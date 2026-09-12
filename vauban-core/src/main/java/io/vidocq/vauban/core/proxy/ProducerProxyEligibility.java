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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflection front-end of the "fully-public produced type" predicate (issue #42), used by the
 * Maven plugin / runtime bytecode path. Mirrors the compile-time
 * {@code io.vidocq.vauban.processor.codegen.proxy.ProducerProxyEligibility} (which works on
 * {@code javax.lang.model} elements): a produced class type can be proxied at build time in the
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
        if (type.isInterface() || type.isEnum() || type.isAnnotation()
                || type.isArray() || type.isPrimitive() || type.isRecord()) {
            return Reason.NOT_A_CLASS;
        }
        int mods = type.getModifiers();
        if (!Modifier.isPublic(mods)) {
            return Reason.NOT_PUBLIC;
        }
        if (Modifier.isFinal(mods)) {
            return Reason.FINAL_CLASS;
        }
        if (type.isSealed()) {
            return Reason.SEALED_CLASS;
        }
        if (Modifier.isAbstract(mods)) {
            return Reason.ABSTRACT_CLASS;
        }
        if (type.getEnclosingClass() != null && !Modifier.isStatic(mods)) {
            return Reason.INACCESSIBLE_NESTED;
        }

        // The constructor the shape chains super() to must be reachable from another package —
        // public or protected. Mirror RuntimeClientProxyGenerator.findSimplestConstructor:
        // (ProxyLink) first, else fewest params, among non-private constructors.
        Constructor<?> chosen = null;
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (Modifier.isPrivate(c.getModifiers())) {
                continue;
            }
            if (ClientProxyShape.isProxyLinkConstructor(c)) {
                chosen = c;
                break;
            }
            if (chosen == null || c.getParameterCount() < chosen.getParameterCount()) {
                chosen = c;
            }
        }
        if (chosen == null) {
            return Reason.NO_ACCESSIBLE_CTOR;
        }
        int cm = chosen.getModifiers();
        if (!Modifier.isPublic(cm) && !Modifier.isProtected(cm)) {
            return Reason.NO_ACCESSIBLE_CTOR;
        }

        // Every overridable method the proxy would forward (walking the hierarchy, as the bytecode
        // shape does) must be public; a non-static final method makes the type unproxyable (§3.10).
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                int mm = m.getModifiers();
                if (Modifier.isStatic(mm) || Modifier.isPrivate(mm)) {
                    continue;
                }
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                var name = m.getName();
                if (name.startsWith("$$")
                        || (name.equals("finalize") && m.getParameterCount() == 0)
                        || (name.equals("clone") && m.getParameterCount() == 0)) {
                    continue;
                }
                if (Modifier.isFinal(mm)) {
                    return Reason.FINAL_VIRTUALS;
                }
                if (Modifier.isAbstract(mm)) {
                    continue;
                }
                if (!Modifier.isPublic(mm)) {
                    return Modifier.isProtected(mm)
                            ? Reason.PROTECTED_VIRTUALS
                            : Reason.PACKAGE_PRIVATE_VIRTUALS;
                }
            }
        }
        return Reason.ELIGIBLE;
    }
}
