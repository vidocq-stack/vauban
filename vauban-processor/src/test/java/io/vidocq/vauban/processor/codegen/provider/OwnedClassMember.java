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
package io.vidocq.vauban.processor.codegen.provider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A runtime annotation whose member names an inner class of a generic class, after its parameterized owner. */
@Retention(RetentionPolicy.RUNTIME)
public @interface OwnedClassMember {

    Class<? extends Outer<String>.Inner<Integer>> value();

    /** A generic class with an inner class of its own type parameter. */
    @SuppressWarnings("java:S2326") // the type parameters are what the rendering reads
    class Outer<O> {

        /** An inner class whose type is written after its enclosing type's. */
        public class Inner<I> {
        }
    }
}
