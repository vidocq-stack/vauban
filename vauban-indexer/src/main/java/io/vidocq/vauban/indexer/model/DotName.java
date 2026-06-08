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
package io.vidocq.vauban.indexer.model;

import java.util.Objects;

public record DotName(String value) implements Comparable<DotName> {

    public DotName {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be empty");
        }
    }

    public static DotName of(String fqcn) {
        return new DotName(fqcn);
    }

    public static DotName fromInternal(String internal) {
        return new DotName(internal.replace('/', '.'));
    }

    public static DotName fromDescriptor(String desc) {
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return fromInternal(desc.substring(1, desc.length() - 1));
        }
        throw new IllegalArgumentException("Invalid class descriptor: " + desc);
    }

    public String simpleName() {
        int idx = value.lastIndexOf('.');
        return idx < 0 ? value : value.substring(idx + 1);
    }

    public String packageName() {
        int idx = value.lastIndexOf('.');
        return idx < 0 ? "" : value.substring(0, idx);
    }

    public String toInternal() {
        return value.replace('.', '/');
    }

    public String toDescriptor() {
        return "L" + toInternal() + ";";
    }

    @Override
    public int compareTo(DotName other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
