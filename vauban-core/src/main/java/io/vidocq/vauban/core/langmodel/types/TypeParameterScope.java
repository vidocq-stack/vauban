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
package io.vidocq.vauban.core.langmodel.types;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Target;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The type parameters a class or a method declares, with the annotations on the types the declaration declares
 * (BUG-20261117-60), inside the scope of the enclosing declaration: a method's type parameters shadow its class's.
 * It gives the use of each type the declaration declares, and the type parameters as the language model shows them.
 */
public final class TypeParameterScope {

    /** No type parameter, no annotation. */
    public static final TypeParameterScope EMPTY = new TypeParameterScope(List.of(), List.of(), null);

    private static final TypeInfo OBJECT = new TypeInfo.ClassType(DotName.of("java.lang.Object"));

    private final List<TypeInfo.TypeVariable> parameters;
    private final List<TypeAnnotationInfo> annotations;
    private final TypeParameterScope enclosing;

    private TypeParameterScope(List<TypeInfo.TypeVariable> parameters, List<TypeAnnotationInfo> annotations,
            TypeParameterScope enclosing) {
        this.parameters = parameters;
        this.annotations = annotations;
        this.enclosing = enclosing;
    }

    /**
     * The scope of a declaration: the type parameters it declares as the index records them, the type annotations of
     * the declaration, and the scope it sits in ({@code null} for a top-level one).
     */
    public static TypeParameterScope of(List<TypeInfo.TypeVariable> parameters, List<TypeAnnotationInfo> annotations,
            TypeParameterScope enclosing) {
        return new TypeParameterScope(List.copyOf(parameters), List.copyOf(annotations), enclosing);
    }

    /** The use of the type the declaration declares at {@code target} and {@code index}. */
    public TypeUse use(Target target, int index) {
        return use(annotation -> annotation.isOn(target, index));
    }

    /**
     * The type parameters of the declaration, as the language model shows them: each with its annotations, and with
     * the bounds it declares, or {@code java.lang.Object}, which the index leaves out.
     */
    public List<TypeVariable> typeParameters(IndexLookup lookup) {
        var result = new ArrayList<TypeVariable>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            var parameter = parameters.get(i);
            var bounds = parameter.bounds().isEmpty() ? List.of(OBJECT) : parameter.bounds();
            var mapped = new ArrayList<Type>(bounds.size());
            for (int position = 0; position < bounds.size(); position++) {
                mapped.add(TypeMapper.map(bounds.get(position), lookup, bound(i, position)));
            }
            result.add(new VaubanTypeVariable(parameter.name(), mapped,
                    use(Target.TYPE_PARAMETER, i).annotations(lookup)));
        }
        return result;
    }

    /** The bound at {@code position} of the type variable {@code name}, in this scope or an enclosing one. */
    TypeUse bound(String name, int position) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).name().equals(name)) {
                return bound(i, position);
            }
        }
        return enclosing == null ? TypeUse.NONE : enclosing.bound(name, position);
    }

    private TypeUse bound(int parameter, int position) {
        return use(annotation -> annotation.isOn(Target.TYPE_PARAMETER_BOUND, parameter)
                && annotation.boundIndex() == position);
    }

    private TypeUse use(Predicate<TypeAnnotationInfo> selected) {
        return new TypeUse(annotations.stream().filter(selected).toList(), this);
    }
}
