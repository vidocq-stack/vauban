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

import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.Set;

/**
 * Describes a single injection point (field, constructor parameter, or method parameter).
 *
 * @param qualifiers         what resolution and validation compare: the qualifiers completed as CDI
 *                           requires — {@code @Default} when none is written, and always {@code @Any}
 * @param declaredQualifiers exactly what the annotations say, before that completion. Injection
 *                           reads these instead of the member's annotations (vauban#70), and the two
 *                           are not interchangeable: an event fired with the completed set would
 *                           reach observers the declaration never asked for
 * @param description        for error messages, and to find the point again from the member it
 *                           describes — build it with {@link #parameterDescription} or
 *                           {@link #fieldDescription}, never by hand
 */
public record InjectionPointInfo(
        TypeInfo requiredType,
        Set<QualifierInstance> qualifiers,
        Set<QualifierInstance> declaredQualifiers,
        InjectionKind kind,
        String description
) {

    public enum InjectionKind {
        FIELD, CONSTRUCTOR_PARAMETER, METHOD_PARAMETER
    }

    public InjectionPointInfo {
        qualifiers = Set.copyOf(qualifiers);
        declaredQualifiers = Set.copyOf(declaredQualifiers);
    }

    /** For a caller that knows only one set — an extension that replaced it, or a synthetic point. */
    public InjectionPointInfo(TypeInfo requiredType, Set<QualifierInstance> qualifiers,
            InjectionKind kind, String description) {
        this(requiredType, qualifiers, qualifiers, kind, description);
    }

    /**
     * How a parameter injection point is described. Discovery writes it and injection reads it back
     * to find the qualifiers of a parameter it holds reflectively, so both go through here rather
     * than spelling the same format twice.
     *
     * @param declaringSimpleName the simple name of the class declaring the member
     * @param methodName          the method's name, or {@code null} for a constructor
     * @param index               the parameter's position
     */
    public static String parameterDescription(String declaringSimpleName, String methodName, int index) {
        return "parameter " + index + " of " + declaringSimpleName
                + (methodName == null ? "" : "." + methodName) + "()";
    }

    /** How a field injection point is described. */
    public static String fieldDescription(String declaringSimpleName, String fieldName) {
        return "field " + declaringSimpleName + "." + fieldName;
    }
}
