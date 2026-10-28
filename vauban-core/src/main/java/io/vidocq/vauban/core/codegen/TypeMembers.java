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
package io.vidocq.vauban.core.codegen;

import io.vidocq.vauban.core.codegen.TypeView.Flag;

/**
 * Which methods are members of a type, on every build path (vauban#227): the run-time and Maven plugin generators
 * judge a {@code Class}, the annotation processor a {@code TypeElement}, both through a {@link TypeView}.
 */
public final class TypeMembers {

    private TypeMembers() {}

    /**
     * Whether {@code method}, declared by {@code declaring} — {@code type} or one of its superclasses — is an instance
     * method of {@code type} a subclass can override: not static, not private, and when package-private, inherited
     * through classes of {@code declaring}'s runtime package only (JLS 8.4.8). {@code A(p) <- B(q) <- C(p)} does not
     * give {@code C} the package-private methods of {@code A}.
     */
    public static boolean isMemberOf(TypeView type, TypeView declaring, TypeView.Member method) {
        if (method.is(Flag.STATIC) || method.is(Flag.PRIVATE)) {
            return false;
        }
        if (method.is(Flag.PUBLIC) || method.is(Flag.PROTECTED)) {
            return true;
        }
        for (TypeView c = type; c != null && !c.isSameType(declaring); c = c.superclass()) {
            if (!c.isSamePackage(declaring)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code defaultMethod}, an interface default method {@code type} inherits, is shadowed: a class of
     * {@code type}'s superclass chain, below {@code java.lang.Object}, declares a method with its name and erased
     * descriptor that is <em>not</em> a member of {@code type} ({@link #isMemberOf}), to which the JVM resolves a call
     * typed by {@code type} and which it then refuses. A declaration the type inherits is no shadow: it is the type's
     * member, and implements the default (BUG-20261004-08).
     */
    public static boolean isShadowed(TypeView type, TypeView.Member defaultMethod) {
        boolean shadowed = false;
        for (TypeView c = type; c != null && !c.isObject(); c = c.superclass()) {
            for (var declared : c.methods()) {
                if (declared.name().equals(defaultMethod.name())
                        && declared.returnType().equals(defaultMethod.returnType())
                        && declared.parameterTypes().equals(defaultMethod.parameterTypes())) {
                    if (isMemberOf(type, c, declared)) {
                        return false;
                    }
                    shadowed = true;
                }
            }
        }
        return shadowed;
    }
}
