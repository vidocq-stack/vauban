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
package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Step;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Target;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Reads the annotations on the types of a declaration from the compiler's model, into the index's
 * {@link TypeAnnotationInfo}s, as the bytecode scan reads them from the class file (BUG-20261117-60): only
 * runtime-retained ones, each with its path along the type, a bound by its position among the bounds the index records,
 * a nested type by its innermost name. The compiler of Java 25 gives them for a class of the class path too, read from
 * its class file: JDK-8225377, which hid them there, no longer shows ({@code TypeAnnotationsProcessorTest}).
 */
final class MirrorTypeAnnotations {

    private static final String RETENTION = "java.lang.annotation.Retention";
    private static final String TARGET = "java.lang.annotation.Target";
    private static final String REPEATABLE = "java.lang.annotation.Repeatable";

    private final Function<AnnotationMirror, AnnotationInfo> converter;
    private final Function<TypeElement, DotName> binaryName;
    private final Predicate<TypeMirror> isObject;
    private final List<TypeAnnotationInfo> found = new ArrayList<>();

    /**
     * @param converter  reads an annotation as the index records it
     * @param binaryName the binary name of a type, {@code Elements.getBinaryName}
     * @param isObject   whether a type is {@code java.lang.Object}, a bound the index leaves out
     */
    MirrorTypeAnnotations(Function<AnnotationMirror, AnnotationInfo> converter,
                          Function<TypeElement, DotName> binaryName, Predicate<TypeMirror> isObject) {
        this.converter = converter;
        this.binaryName = binaryName;
        this.isObject = isObject;
    }

    /** The annotations read so far. */
    List<TypeAnnotationInfo> found() {
        return List.copyOf(found);
    }

    /** Reads the annotations on {@code type} and inside it, the type {@code target} and {@code index} name. */
    MirrorTypeAnnotations type(TypeMirror type, Target target, int index) {
        walk(type, target, index, 0, List.of());
        return this;
    }

    /** Reads each of {@code types}, the type {@code target} names at its position. */
    MirrorTypeAnnotations types(List<? extends TypeMirror> types, Target target) {
        for (int i = 0; i < types.size(); i++) {
            type(types.get(i), target, i);
        }
        return this;
    }

    /**
     * Reads the types of {@code parameters}, each at its position among the parameters the index records, after
     * {@code offset} it adds before them: the outer instance of an inner class's constructor (BUG-20261118-33).
     */
    MirrorTypeAnnotations parameters(List<? extends VariableElement> parameters, int offset) {
        for (int i = 0; i < parameters.size(); i++) {
            type(parameters.get(i).asType(), Target.PARAMETER, i + offset);
        }
        return this;
    }

    /**
     * Reads the type parameters a declaration declares: the annotations on each, and on each of its bounds, at its
     * position among the bounds the index records, the implicit {@code java.lang.Object} left out. An annotated
     * {@code Object} a type parameter names as its only bound is at {@code 0}, where the language model shows it.
     */
    MirrorTypeAnnotations typeParameters(List<? extends TypeParameterElement> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            var parameter = parameters.get(i);
            add(parameter.getAnnotationMirrors(), Target.TYPE_PARAMETER, i, 0, List.of());
            var bounds = parameter.getBounds();
            var kept = bounds.stream().filter(isObject.negate()).toList();
            if (kept.isEmpty() && !bounds.isEmpty()) {
                walk(bounds.getFirst(), Target.TYPE_PARAMETER_BOUND, i, 0, List.of());
            }
            for (int position = 0; position < kept.size(); position++) {
                walk(kept.get(position), Target.TYPE_PARAMETER_BOUND, i, position, List.of());
            }
        }
        return this;
    }

    /**
     * Reads the annotations on what a constructor builds: those of its annotations that apply to a type use, which
     * the class file records on its return type, where the compiler's model gives a constructor a {@code void} one.
     */
    MirrorTypeAnnotations constructor(ExecutableElement constructor) {
        add(constructor.getAnnotationMirrors().stream().filter(MirrorTypeAnnotations::appliesToTypeUse).toList(),
                Target.RETURN, 0, 0, List.of());
        return this;
    }

    private static boolean appliesToTypeUse(AnnotationMirror annotation) {
        var type = (TypeElement) annotation.getAnnotationType().asElement();
        return type.getAnnotationMirrors().stream()
                .filter(meta -> named(meta, TARGET))
                .flatMap(meta -> meta.getElementValues().values().stream())
                .map(value -> value.getValue() instanceof List<?> targets ? targets : List.of(value))
                .flatMap(List::stream)
                .anyMatch(target -> target instanceof AnnotationValue value
                        && value.getValue() instanceof VariableElement constant
                        && constant.getSimpleName().contentEquals("TYPE_USE"));
    }

    private static boolean named(AnnotationMirror annotation, String name) {
        return ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().contentEquals(name);
    }

    private void walk(TypeMirror type, Target target, int index, int boundIndex, List<Step> path) {
        add(type.getAnnotationMirrors(), target, index, boundIndex, path);
        switch (type.getKind()) {
            case DECLARED -> {
                var arguments = ((DeclaredType) type).getTypeArguments();
                for (int i = 0; i < arguments.size(); i++) {
                    walk(arguments.get(i), target, index, boundIndex, append(path, Step.typeArgument(i)));
                }
            }
            case ARRAY -> walk(((ArrayType) type).getComponentType(), target, index, boundIndex,
                    append(path, Step.ARRAY));
            case WILDCARD -> {
                var wildcard = (WildcardType) type;
                var bound = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
                if (bound != null) {
                    walk(bound, target, index, boundIndex, append(path, Step.WILDCARD));
                }
            }
            default -> {
                // a primitive, a type variable, whose bounds its declaration annotates, void, none
            }
        }
    }

    private void add(List<? extends AnnotationMirror> annotations, Target target, int index, int boundIndex,
            List<Step> path) {
        var retained = annotations.stream().filter(MirrorTypeAnnotations::isRuntimeRetained).toList();
        var written = new LinkedHashMap<TypeElement, List<AnnotationMirror>>();
        retained.forEach(annotation -> written.computeIfAbsent(typeOf(annotation), _ -> new ArrayList<>())
                .add(annotation));
        written.forEach((type, repeated) -> found.add(new TypeAnnotationInfo(target, index, boundIndex, path,
                repeated.size() == 1 ? converter.apply(repeated.getFirst()) : contained(type, repeated))));
    }

    /**
     * The container of an annotation written more than once on a type, holding them in the order written: the class
     * file records it so, and the start reads it so, where the compiler's model gives each on its own
     * (BUG-20261118-26). A type that names no container is the compiler's error, never seen here.
     */
    private AnnotationInfo contained(TypeElement type, List<AnnotationMirror> repeated) {
        var held = repeated.stream()
                .<io.vidocq.vauban.indexer.model.AnnotationValue>map(
                        annotation -> new io.vidocq.vauban.indexer.model.AnnotationValue.AnnotationVal(
                                converter.apply(annotation)))
                .toList();
        return new AnnotationInfo(binaryName.apply(containerOf(type)),
                Map.of("value", new io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal(held)));
    }

    /** The container type {@code type}'s {@code @Repeatable} names. */
    private static TypeElement containerOf(TypeElement type) {
        return type.getAnnotationMirrors().stream().filter(meta -> named(meta, REPEATABLE))
                .flatMap(meta -> meta.getElementValues().values().stream())
                .map(value -> (TypeElement) ((DeclaredType) value.getValue()).asElement())
                .findFirst().orElseThrow(() -> new IllegalStateException(type + " is repeated but not @Repeatable"));
    }

    private static TypeElement typeOf(AnnotationMirror annotation) {
        return (TypeElement) annotation.getAnnotationType().asElement();
    }

    private static List<Step> append(List<Step> path, Step step) {
        var longer = new ArrayList<>(path);
        longer.add(step);
        return longer;
    }

    /** Whether the annotation's type is meta-annotated {@code @Retention(RUNTIME)}: the class file keeps no other. */
    static boolean isRuntimeRetained(AnnotationMirror annotation) {
        var type = (TypeElement) annotation.getAnnotationType().asElement();
        for (var meta : type.getAnnotationMirrors()) {
            if (named(meta, RETENTION)) {
                return meta.getElementValues().values().stream()
                        .anyMatch(value -> value.getValue() instanceof VariableElement policy
                                && policy.getSimpleName().contentEquals("RUNTIME"));
            }
        }
        return false;
    }

    /** Whether {@code type} is a type the walk reads annotations on: not {@code NONE}, which has none. */
    static boolean isPresent(TypeMirror type) {
        return type != null && type.getKind() != TypeKind.NONE;
    }
}
