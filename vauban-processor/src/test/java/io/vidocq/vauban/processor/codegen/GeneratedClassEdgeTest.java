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
package io.vidocq.vauban.processor.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** A generated class without bytes, one whose name matches but whose bytes differ, and another kind of object. */
class GeneratedClassEdgeTest {

    @Test
    void aClassWithoutBytesSaysSo() {
        assertEquals("GeneratedClass[className=app.Bean_Factory, bytecode=null]",
                new GeneratedClass("app.Bean_Factory", null).toString());
    }

    @Test
    void theSameNameWithOtherBytesIsAnotherClass() {
        var named = new GeneratedClass("app.Bean_Factory", new byte[] {1});

        assertNotEquals(named, new GeneratedClass("app.Bean_Factory", null));
        assertNotEquals(named, (Object) "app.Bean_Factory");
        assertEquals(new GeneratedClass("app.Bean_Factory", null), new GeneratedClass("app.Bean_Factory", null));
    }
}
