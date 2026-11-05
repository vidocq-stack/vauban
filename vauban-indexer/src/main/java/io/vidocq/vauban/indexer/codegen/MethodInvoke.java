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
package io.vidocq.vauban.indexer.codegen;

import java.util.List;

/**
 * Describes a method (producer, observer, disposer, lifecycle callback, initializer) that the
 * generated {@code _VaubanComponents} provider can invoke in-module without reflection.
 *
 * <p>The method identity key ({@link #methodId()}) is {@code methodName(paramErasure0,…)},
 * using binary class names (dots for top-level, {@code $} for nested, {@code []} per array
 * dimension), exactly matching the format produced by
 * {@code io.vidocq.vauban.core.container.VaubanLookup#methodId(Method)}.
 *
 * @param declaringClassFqn       binary name of the class declaring the method ({@code app.Outer$Inner} for a nested
 *                                class): what the container asks the provider for
 * @param methodName              simple name of the method
 * @param paramErasures           erased parameter type names in declared order (empty for no-arg)
 * @param isStatic                {@code true} for a static method (no target cast needed)
 * @param isVoid                  {@code true} when the return type is {@code void}
 * @param returnErasure           erased return type FQN, or {@code null} when {@code isVoid} is true
 * @param declaringClassSourceFqn canonical name of the declaring class ({@code app.Outer.Inner}): the only one a
 *                                generated source can cast to or call a static method on
 */
public record MethodInvoke(
        String declaringClassFqn,
        String methodName,
        List<String> paramErasures,
        boolean isStatic,
        boolean isVoid,
        String returnErasure,
        String declaringClassSourceFqn) {

    public MethodInvoke {
        paramErasures = List.copyOf(paramErasures);
    }

    /** For a declaring class whose source name is its binary name: every top-level class. */
    public MethodInvoke(String declaringClassFqn, String methodName, List<String> paramErasures,
            boolean isStatic, boolean isVoid, String returnErasure) {
        this(declaringClassFqn, methodName, paramErasures, isStatic, isVoid, returnErasure, declaringClassFqn);
    }

    /** The runtime-compatible method identity key: {@code name(erasure0,erasure1,…)}. */
    public String methodId() {
        return methodName + "(" + String.join(",", paramErasures) + ")";
    }
}
