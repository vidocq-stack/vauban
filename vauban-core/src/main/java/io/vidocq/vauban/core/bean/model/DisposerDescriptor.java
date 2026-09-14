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
package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.List;
import java.util.Set;

/**
 * Describes a disposer method discovered during bean scanning.
 * A disposer method has exactly one parameter annotated with {@code @Disposes}.
 *
 * @param declaringClass  the class that declares the disposer method
 * @param methodName      the method name
 * @param disposedType    the type of the parameter annotated with @Disposes
 * @param qualifiers      qualifier annotations on the disposed parameter
 * @param parameterIndex  index of the @Disposes parameter in the method signature
 * @param injectionPoints every <em>other</em> parameter of the method. CDI 4.1 §10.4.3 makes them
 *                        injection points like any other, qualifiers included; described here, they
 *                        are validated and resolved on what they declare rather than on their type
 *                        alone, and nothing has to read them off the reflective method (vauban#89)
 */
public record DisposerDescriptor(
        DotName declaringClass,
        String methodName,
        TypeInfo disposedType,
        Set<QualifierInstance> qualifiers,
        int parameterIndex,
        List<InjectionPointInfo> injectionPoints
) {
    public DisposerDescriptor {
        qualifiers = Set.copyOf(qualifiers);
        injectionPoints = List.copyOf(injectionPoints);
    }

    /** A disposer whose other parameters are not described — the pre-vauban#89 shape. */
    public DisposerDescriptor(DotName declaringClass, String methodName, TypeInfo disposedType,
            Set<QualifierInstance> qualifiers, int parameterIndex) {
        this(declaringClass, methodName, disposedType, qualifiers, parameterIndex, List.of());
    }
}
