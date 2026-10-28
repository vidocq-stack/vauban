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
package io.vidocq.vauban.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/** Finding the processed types by the names the index carries, and what the generators ask of them. */
final class TypeLookup {

    private final ProcessingEnvironment processingEnv;

    TypeLookup(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
    }

    /**
     * Only top-level types get an in-module provider entry. A nested/member type's canonical name
     * is dot-separated in APT mode (e.g. {@code a.b.Outer.Inner}), which is indistinguishable from
     * a package boundary by string analysis and would make the generator emit {@code _VaubanComponents}
     * into a bogus package. Nested beans (mostly test fixtures) fall back to reflective instantiation.
     * When the element is not resolvable (bytecode-only deps), fall back to the {@code $} marker.
     */
    boolean isTopLevelType(String fqn) {
        var te = processingEnv.getElementUtils().getTypeElement(fqn);
        if (te != null) {
            return te.getNestingKind() == javax.lang.model.element.NestingKind.TOP_LEVEL;
        }
        return !fqn.contains("$");
    }

    /**
     * The type whose binary name is {@code binaryName}, or {@code null}. {@code getTypeElement}
     * wants the canonical name, which a {@code $} does not tell apart from the binary one: it
     * separates a nested type from its enclosing type ({@code app.Outer$Inner}, canonically
     * {@code app.Outer.Inner}), but it may also belong to a type's own name ({@code app.A$B}, a
     * top-level class). So each top-level candidate is tried, then its member types walked, and the
     * one whose binary name matches wins (BUG-20261004-11).
     */
    static TypeElement typeElementByBinaryName(Elements elements, String binaryName) {
        for (int end = binaryName.length(); end > 0; end = binaryName.lastIndexOf('$', end - 1)) {
            var candidate = elements.getTypeElement(binaryName.substring(0, end));
            if (candidate == null || candidate.getNestingKind() != javax.lang.model.element.NestingKind.TOP_LEVEL) {
                continue;
            }
            var found = memberByBinaryName(elements, candidate, binaryName);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The canonical name of the type whose binary name is {@code binaryName}, or {@code null} when
     * the compiler does not know it, or it has none (a local or an anonymous class).
     */
    String canonicalName(String binaryName) {
        var type = typeElementByBinaryName(processingEnv.getElementUtils(), binaryName);
        if (type == null || type.getQualifiedName().isEmpty()) return null;
        return type.getQualifiedName().toString();
    }

    /** {@code type} or the member type of {@code type}, at any depth, whose binary name is {@code binaryName}. */
    private static TypeElement memberByBinaryName(Elements elements, TypeElement type, String binaryName) {
        var name = elements.getBinaryName(type).toString();
        if (name.equals(binaryName)) return type;
        if (!binaryName.startsWith(name + "$")) return null;
        for (var member : javax.lang.model.util.ElementFilter.typesIn(type.getEnclosedElements())) {
            var found = memberByBinaryName(elements, member, binaryName);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * {@code true} when {@code te} has a non-private constructor the proxy can call via {@code super}
     * (a subclass cannot reach a private super ctor): either no explicit constructor (implicit no-arg)
     * or at least one non-private declared ctor. Gates SOURCE client-proxy generation — the proxy
     * calls the simplest non-private super ctor with default values; an all-private-ctor bean is
     * unproxyable by subclassing and keeps the bytecode proxy + runtime fallback.
     */
    static boolean hasNonPrivateCtor(javax.lang.model.element.TypeElement te) {
        var ctors = javax.lang.model.util.ElementFilter.constructorsIn(te.getEnclosedElements());
        if (ctors.isEmpty()) {
            return true; // implicit no-arg constructor
        }
        for (var c : ctors) {
            if (!c.getModifiers().contains(javax.lang.model.element.Modifier.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    /** Package of a top-level FQN — the substring before the last dot, or {@code ""} (default package). */
    static String packageOfFqn(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
