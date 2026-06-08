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

import java.util.List;

public record FieldInfo(String name, TypeInfo type, int accessFlags, List<AnnotationInfo> annotations) {

    public FieldInfo {
        annotations = List.copyOf(annotations);
    }

    public boolean isStatic() {
        return (accessFlags & 0x0008) != 0;
    }

    public boolean isFinal() {
        return (accessFlags & 0x0010) != 0;
    }

    public boolean isPublic() {
        return (accessFlags & 0x0001) != 0;
    }

    public boolean isPrivate() {
        return (accessFlags & 0x0002) != 0;
    }

    public boolean isProtected() {
        return (accessFlags & 0x0004) != 0;
    }
}
