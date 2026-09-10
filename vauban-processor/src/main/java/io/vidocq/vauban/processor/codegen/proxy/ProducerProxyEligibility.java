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
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            var mm = m.getModifiers();
            if (mm.contains(Modifier.STATIC) || mm.contains(Modifier.PRIVATE)) {
                continue;
            }
            if (m.getSimpleName().toString().startsWith("$$")) {
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
        return Reason.ELIGIBLE;
    }
}
