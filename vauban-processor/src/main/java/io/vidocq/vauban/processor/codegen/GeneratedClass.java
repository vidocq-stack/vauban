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

import java.util.Arrays;
import java.util.Objects;

/**
 * A class generated at build time as raw bytecode.
 *
 * @param className fully qualified class name (dot-separated)
 * @param bytecode  the class file bytes
 */
public record GeneratedClass(String className, byte[] bytecode) {

    /**
     * Internal name (slash-separated) for use with ClassFile API.
     */
    public String internalName() {
        return className.replace('.', '/');
    }

    /** Two generated classes are equal when they share the name and the same class file bytes. */
    @Override
    public boolean equals(Object other) {
        return other instanceof GeneratedClass(String otherName, byte[] otherBytecode)
                && Objects.equals(className, otherName)
                && Arrays.equals(bytecode, otherBytecode);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(className) + Arrays.hashCode(bytecode);
    }

    /** The name and the size of the class file; the bytes themselves are not printed. */
    @Override
    public String toString() {
        return "GeneratedClass[className=" + className + ", bytecode="
                + (bytecode == null ? "null" : bytecode.length + " bytes") + "]";
    }
}
