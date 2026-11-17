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
package io.vidocq.vauban.indexer.scanner;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Step;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Target;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.TypeAnnotation.TypePathComponent;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Reads the {@code RuntimeVisibleTypeAnnotations} attributes of a class file into the index's
 * {@link TypeAnnotationInfo}s (BUG-20261117-60): only those on the types a declaration declares, not those in the code
 * of a method.
 *
 * <p>A type path (JVMS 4.7.20.2) is read along the index's own type: a step into a nested type has no place there, the
 * index naming a nested type by its binary name. An annotation on the innermost name of a nested type
 * ({@code Outer.@A Inner}) is kept on the type; one on an enclosing type ({@code @A Outer.Inner}), which the language
 * model has no type to show on, is left out. How deep the innermost name of an inner class is comes from the
 * {@code InnerClasses} attribute, which names every nested class the class file refers to (JVMS 4.7.6). An annotation
 * whose path the type does not have is left out too.
 */
final class TypeAnnotationReader {

    private static final TypeInfo OBJECT = new TypeInfo.ClassType(DotName.of("java.lang.Object"));

    private final ClassModel model;
    private final Function<java.lang.classfile.Annotation, AnnotationInfo> annotations;
    /** The {@code InnerClasses} entries of the class file by binary name, read the first time a type needs them. */
    private Map<DotName, InnerClassInfo> entries;

    private TypeAnnotationReader(ClassModel model,
            Function<java.lang.classfile.Annotation, AnnotationInfo> annotations) {
        this.model = model;
        this.annotations = annotations;
    }

    /**
     * A reader for the members of {@code model}, which reads an annotation with {@code annotations}. It reads nothing
     * until an element with type annotations asks.
     */
    static TypeAnnotationReader of(ClassModel model,
            Function<java.lang.classfile.Annotation, AnnotationInfo> annotations) {
        return new TypeAnnotationReader(model, annotations);
    }

    /** Whether {@code element} has type annotations: an element without them needs no reading at all. */
    static boolean hasTypeAnnotations(AttributedElement element) {
        return element.findAttribute(Attributes.runtimeVisibleTypeAnnotations()).isPresent();
    }

    private Map<DotName, InnerClassInfo> entries() {
        if (entries == null) {
            entries = new HashMap<>();
            model.findAttribute(Attributes.innerClasses()).ifPresent(attribute -> attribute.classes().forEach(
                    inner -> entries.put(DotName.fromInternal(inner.innerClass().asInternalName()), inner)));
        }
        return entries;
    }

    /**
     * Whether the class file names {@code name} an inner class, not static, a member of another class: its
     * constructors take the outer instance first.
     */
    boolean isInnerMember(DotName name) {
        var entry = entries().get(name);
        return entry != null && entry.outerClass().isPresent() && !isStatic(entry);
    }

    /**
     * How many inner type steps lead to the name of {@code name}: one per enclosing class an inner (not static) class
     * has, up to the first static one. Entries that name each other as outer classes, which the JVM does not check,
     * count each class once.
     */
    private int depth(DotName name) {
        var seen = new HashSet<DotName>();
        var entry = entries().get(name);
        while (entry != null && !isStatic(entry) && entry.outerClass().isPresent() && seen.add(entryName(entry))) {
            entry = entry.outerClass().map(outer -> entries().get(DotName.fromInternal(outer.asInternalName())))
                    .orElse(null);
        }
        return seen.size();
    }

    private static DotName entryName(InnerClassInfo entry) {
        return DotName.fromInternal(entry.innerClass().asInternalName());
    }

    private static boolean isStatic(InnerClassInfo entry) {
        return (entry.flagsMask() & java.lang.classfile.ClassFile.ACC_STATIC) != 0;
    }

    /** The type annotations of an element, each placed by {@code place}; empty when it has none. */
    List<TypeAnnotationInfo> read(AttributedElement element, Function<TypeAnnotation, Placed> place) {
        var found = element.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
                .map(RuntimeVisibleTypeAnnotationsAttribute::annotations).orElse(List.of());
        var result = new ArrayList<TypeAnnotationInfo>();
        for (var annotation : found) {
            var placed = place.apply(annotation);
            if (placed != null) {
                path(annotation.targetPath(), placed.type()).ifPresent(path -> result.add(new TypeAnnotationInfo(
                        placed.target(), placed.index(), placed.boundIndex(), path,
                        annotations.apply(annotation.annotation()))));
            }
        }
        return result;
    }

    /**
     * Where an annotation is, in terms of the index: its target, its indexes and the type its path starts from.
     * {@code null} stands for an annotation the index does not keep.
     */
    record Placed(Target target, int index, int boundIndex, TypeInfo type) {

        static Placed on(Target target, int index, TypeInfo type) {
            return type == null ? null : new Placed(target, index, 0, type);
        }
    }

    /**
     * Places a type parameter or one of its bounds. {@code parameters} are the type parameters as the index records
     * them, {@code classBound} tells, for each, whether its class bound is one the index keeps ({@code 1}), an
     * {@code Object} it leaves out ({@code 0}) or none, its first bound being an interface ({@code -1}).
     */
    static Placed typeParameter(TypeAnnotation annotation, List<TypeInfo.TypeVariable> parameters,
            IntFunction<Integer> classBound) {
        return switch (annotation.targetInfo()) {
            case TypeAnnotation.TypeParameterBoundTarget bound when bound.typeParameterIndex() < parameters.size() -> {
                var bounds = parameters.get(bound.typeParameterIndex()).bounds();
                int position = boundPosition(bound.boundIndex(), classBound.apply(bound.typeParameterIndex()),
                        bounds.isEmpty());
                if (position < 0) {
                    yield null;
                }
                var type = bounds.isEmpty() ? OBJECT : bounds.get(position);
                yield new Placed(Target.TYPE_PARAMETER_BOUND, bound.typeParameterIndex(), position, type);
            }
            case TypeAnnotation.TypeParameterTarget parameter when parameter.typeParameterIndex() < parameters.size() ->
                    new Placed(Target.TYPE_PARAMETER, parameter.typeParameterIndex(), 0,
                            parameters.get(parameter.typeParameterIndex()));
            default -> null;
        };
    }

    /**
     * The position in the bounds the index records of the bound a class file numbers {@code boundIndex}: there, the
     * class bound is {@code 0}, even when absent, and the interfaces follow. {@code -1} when the index has no such
     * bound.
     */
    private static int boundPosition(int boundIndex, int classBound, boolean noBound) {
        if (classBound > 0) {
            return boundIndex;
        }
        if (boundIndex == 0) {
            return classBound == 0 && noBound ? 0 : -1;
        }
        return boundIndex - 1;
    }

    /** The steps of {@code path} along {@code type}, or empty when the annotation is not on a type the index has. */
    private Optional<List<Step>> path(List<TypePathComponent> path, TypeInfo type) {
        var steps = new ArrayList<Step>();
        var node = type;
        int next = 0;
        while (true) {
            int depth = depth(node);
            int inner = 0;
            while (next + inner < path.size()
                    && path.get(next + inner).typePathKind() == TypePathComponent.Kind.INNER_TYPE) {
                inner++;
            }
            if (inner != depth) {
                return Optional.empty();
            }
            next += inner;
            if (next == path.size()) {
                return Optional.of(steps);
            }
            var component = path.get(next++);
            node = step(node, component);
            if (node == null) {
                return Optional.empty();
            }
            steps.add(switch (component.typePathKind()) {
                case ARRAY -> Step.ARRAY;
                case WILDCARD -> Step.WILDCARD;
                default -> Step.typeArgument(component.typeArgumentIndex());
            });
        }
    }

    private int depth(TypeInfo node) {
        return switch (node) {
            case TypeInfo.ClassType(var name) -> depth(name);
            case TypeInfo.ParameterizedType(var name, _) -> depth(name);
            default -> 0;
        };
    }

    /** The type one step of a path leads to from {@code node}; {@code null} when {@code node} has no such type. */
    private static TypeInfo step(TypeInfo node, TypePathComponent component) {
        return switch (component.typePathKind()) {
            case ARRAY -> node instanceof TypeInfo.ArrayType array ? component(array) : null;
            case WILDCARD -> node instanceof TypeInfo.WildcardType wildcard ? bound(wildcard) : null;
            case TYPE_ARGUMENT -> node instanceof TypeInfo.ParameterizedType(_, var arguments)
                    && component.typeArgumentIndex() < arguments.size()
                    ? arguments.get(component.typeArgumentIndex()) : null;
            case INNER_TYPE -> null;
        };
    }

    /** The component of an array: an array of one dimension less, or its element type. */
    private static TypeInfo component(TypeInfo.ArrayType array) {
        return array.dimensions() > 1 ? new TypeInfo.ArrayType(array.componentType(), array.dimensions() - 1)
                : array.componentType();
    }

    /** The bound of a wildcard, upper or lower; {@code Object} for {@code ?}, as the language model shows it. */
    private static TypeInfo bound(TypeInfo.WildcardType wildcard) {
        if (wildcard.upperBound() != null) {
            return wildcard.upperBound();
        }
        return wildcard.lowerBound() != null ? wildcard.lowerBound() : OBJECT;
    }

    /** The type of the receiver of the methods of a class: the class, with its type parameters as arguments. */
    static TypeInfo receiver(DotName name, List<TypeInfo.TypeVariable> typeParameters) {
        return typeParameters.isEmpty() ? new TypeInfo.ClassType(name)
                : new TypeInfo.ParameterizedType(name, List.<TypeInfo>copyOf(typeParameters));
    }
}
