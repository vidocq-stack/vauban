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

import java.util.List;
import java.util.Set;

/**
 * What the code-generation rules need to know about a type, whatever model it comes from (vauban#227): a
 * {@link Class} at run time and in the Maven plugin ({@link ClassTypeView}), a {@code TypeElement} in the annotation
 * processor. A rule written once over this view gives the same verdict on every build path. It holds declarations
 * only: no generic types, no nameability, no annotations — the front-ends keep those.
 */
public interface TypeView {

    /** The kind of declaration. */
    enum Kind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }

    /** The modifiers the rules read. */
    enum Flag { PUBLIC, PROTECTED, PRIVATE, STATIC, FINAL, ABSTRACT, SEALED }

    Kind kind();

    Set<Flag> flags();

    /** Whether the type is declared inside another one. */
    boolean isNested();

    /** The direct superclass, or {@code null} when it is {@code java.lang.Object} or there is none. */
    TypeView superclass();

    /** The methods this type declares, bridges included where the model has them. */
    List<Member> methods();

    /** The constructors this type declares, the implicit default one included. */
    List<Member> constructors();

    /** A method or a constructor. */
    interface Member {

        String name();

        Set<Flag> flags();

        int parameterCount();

        /** A bridge method a compiler generated: it stands for the method it calls and overrides like it. */
        boolean isBridge();

        /** Whether this constructor is the opt-in {@code (ProxyLink)} client-proxy entry constructor. */
        boolean isProxyLinkConstructor();

        /**
         * Name and erased parameter types, as the type the walk started from sees the member: two members with the
         * same key override one another. A bridge has the key of the signature it stands for.
         */
        String overrideKey();

        default boolean is(Flag flag) {
            return flags().contains(flag);
        }
    }

    default boolean is(Flag flag) {
        return flags().contains(flag);
    }
}
