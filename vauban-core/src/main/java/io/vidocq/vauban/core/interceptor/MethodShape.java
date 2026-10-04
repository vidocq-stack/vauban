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
package io.vidocq.vauban.core.interceptor;

import java.util.List;

/**
 * Neutral shape for a method that will be intercepted or bridged.
 * Carries name, return type, and parameter types as {@link TypeRef} — no {@link java.lang.reflect.Method}.
 *
 * <p>{@code returnType} and {@code params} are the erased declaration: the descriptor the class
 * file holds, which the bytecode emitter overrides and the {@code $$super$} bridge mirrors.
 * {@code memberReturnType} and {@code memberParams} are the same method as a member of the bean,
 * erased: they differ only when the bean binds a type variable of the declaring supertype
 * ({@code echo(T)} of {@code Base<T>} is {@code echo(String)} in {@code Bean extends Base<String>}).
 * Java source can override only that member signature, so the source renderer declares its
 * override with it (BUG-20261004-06); the bytecode emitter overrides by descriptor and ignores it.</p>
 */
public record MethodShape(String name, TypeRef returnType, List<TypeRef> params,
                          TypeRef memberReturnType, List<TypeRef> memberParams) {

    public MethodShape {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(returnType, "returnType");
        java.util.Objects.requireNonNull(memberReturnType, "memberReturnType");
        params = List.copyOf(params);
        memberParams = List.copyOf(memberParams);
        if (memberParams.size() != params.size()) {
            throw new IllegalArgumentException("member signature of " + name + " has "
                    + memberParams.size() + " parameters, its declaration " + params.size());
        }
    }

    /** A method whose member signature is its erased declaration: no type variable is bound. */
    public MethodShape(String name, TypeRef returnType, List<TypeRef> params) {
        this(name, returnType, params, returnType, params);
    }
}
