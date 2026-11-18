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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A runtime-retained annotation on a type use (JLS 9.7.4): {@code @A} in {@code List<@A String>}, and where it sits, as
 * a {@code RuntimeVisibleTypeAnnotations} attribute says it (JVMS 4.7.20), in terms of the index's own types
 * (BUG-20261117-60).
 *
 * <p>The {@link TypeInfo}s of the index carry no annotation: the container compares them, and an annotation on a type
 * use changes neither a bean type nor an injection point. The type annotations sit beside them, on the class, the
 * field or the method that declares the type, and the language model puts them back on the types it shows.
 *
 * @param target     which type of the declaration the annotation is on
 * @param index      for {@link Target#PARAMETER}, the position of the parameter in {@link MethodInfo#parameters()};
 *                   for {@link Target#THROWS}, in {@link MethodInfo#exceptionTypes()}; for {@link Target#INTERFACE}, in
 *                   {@link ClassInfo#genericInterfaces()}; for {@link Target#TYPE_PARAMETER} and
 *                   {@link Target#TYPE_PARAMETER_BOUND}, of the type parameter in the declaration's type parameters;
 *                   {@code 0} for the others
 * @param boundIndex for {@link Target#TYPE_PARAMETER_BOUND}, the position of the bound in the bounds the index records
 *                   for that type parameter, where the implicit {@code java.lang.Object} bound is left out: an
 *                   annotated {@code Object} a type parameter names as its only bound is at {@code 0}, where the
 *                   language model shows it; {@code 0} for the others
 * @param path       the steps from the type of the target to the annotated type, outermost first: empty for the type
 *                   itself
 * @param annotation the annotation
 */
public record TypeAnnotationInfo(Target target, int index, int boundIndex, List<Step> path,
                                 AnnotationInfo annotation) {

    /** Which type of a class, a field or a method an annotation is on. */
    public enum Target {
        /** The type of a field. */
        FIELD,
        /** The return type of a method, or the type a constructor builds. */
        RETURN,
        /** The receiver type of a method: {@code @A Foo this}. */
        RECEIVER,
        /** The type of a parameter. */
        PARAMETER,
        /** A type of the {@code throws} clause. */
        THROWS,
        /** A type parameter itself: {@code <@A T>}. */
        TYPE_PARAMETER,
        /** A bound of a type parameter: {@code <T extends @A Number>}. */
        TYPE_PARAMETER_BOUND,
        /** The superclass, as the class declares it. */
        SUPERCLASS,
        /** An interface, as the class declares it. */
        INTERFACE
    }

    /**
     * One step into a type: into the component of an array, the bound of a wildcard, or a type argument of a
     * parameterized type. A step into a nested type has no counterpart: the index names a nested type by its binary
     * name, and shows only the annotations on its innermost name ({@code Outer.@A Inner}).
     *
     * @param kind              where the step goes
     * @param typeArgumentIndex for {@link Kind#TYPE_ARGUMENT}, which type argument; {@code 0} for the others
     */
    public record Step(Kind kind, int typeArgumentIndex) {

        /** Where a step goes. */
        public enum Kind {
            ARRAY, WILDCARD, TYPE_ARGUMENT
        }

        /** Into the component type of an array. */
        public static final Step ARRAY = new Step(Kind.ARRAY, 0);

        /** Into the bound of a wildcard, upper or lower. */
        public static final Step WILDCARD = new Step(Kind.WILDCARD, 0);

        public Step {
            Objects.requireNonNull(kind);
        }

        /** Into the type argument at {@code index}. */
        public static Step typeArgument(int index) {
            return new Step(Kind.TYPE_ARGUMENT, index);
        }
    }

    public TypeAnnotationInfo {
        Objects.requireNonNull(target);
        Objects.requireNonNull(annotation);
        path = List.copyOf(path);
    }

    /** An annotation on the type of {@code target} itself, or inside it along {@code path}. */
    public static TypeAnnotationInfo of(Target target, int index, AnnotationInfo annotation, Step... path) {
        return new TypeAnnotationInfo(target, index, 0, List.of(path), annotation);
    }

    /**
     * {@code annotations} in the order the index keeps them, whichever way they were read: by target, index, bound
     * index and path, and in the order they were read for the same type. The class file and the compiler's model list
     * them in orders of their own; the index, and the fingerprint of the frozen record, must not depend on it.
     */
    public static List<TypeAnnotationInfo> canonical(List<TypeAnnotationInfo> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return List.of();
        }
        return annotations.stream().sorted(Comparator.comparing(TypeAnnotationInfo::target)
                .thenComparingInt(TypeAnnotationInfo::index)
                .thenComparingInt(TypeAnnotationInfo::boundIndex)
                .thenComparing(TypeAnnotationInfo::path, TypeAnnotationInfo::comparePaths)).toList();
    }

    private static int comparePaths(List<Step> first, List<Step> second) {
        for (int i = 0; i < Math.min(first.size(), second.size()); i++) {
            int compared = compareSteps(first.get(i), second.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    private static int compareSteps(Step first, Step second) {
        int kinds = first.kind().compareTo(second.kind());
        return kinds != 0 ? kinds : Integer.compare(first.typeArgumentIndex(), second.typeArgumentIndex());
    }

    /** Whether the annotation is on the type {@code target} and {@code index} name, or inside it. */
    public boolean isOn(Target target, int index) {
        return this.target == target && this.index == index;
    }

    /** The same annotation, one step further from the type of its target: {@code null} when it is not that way. */
    public TypeAnnotationInfo after(Step step) {
        return !path.isEmpty() && path.getFirst().equals(step)
                ? new TypeAnnotationInfo(target, index, boundIndex, path.subList(1, path.size()), annotation)
                : null;
    }
}
