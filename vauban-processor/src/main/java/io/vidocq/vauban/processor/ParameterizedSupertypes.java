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
package io.vidocq.vauban.processor;

import io.vidocq.vauban.core.codegen.CompiledSupertypes;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The parameterized classes and interfaces above a class or a parameterized type the compilation knows, read from the
 * compiler's model as the
 * container reads them by reflection at start ({@code TypeHierarchyResolver}, then {@code BeanDiscovery}'s conversion
 * to the index's types): the type variables resolved along the way, a raw supertype walked from its declaration, the
 * type itself and {@code Object} left out, a primitive named as a class, a type variable met again inside its own
 * bounds given without them, and a type argument that is not a class as the index wrote it. The processor hands them to
 * the bean discovery through {@link CompiledSupertypes}, for a bean class and for the type a producer produces: the
 * index names a class's supertypes raw, and a raw bean type matches every parameterization (BUG-20261117-14). No class
 * is loaded.
 */
final class ParameterizedSupertypes implements Function<TypeInfo, Optional<List<TypeInfo>>> {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    private final Elements elements;
    private final Types types;
    private final Map<TypeInfo, Optional<List<TypeInfo>>> read = new HashMap<>();

    ParameterizedSupertypes(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /**
     * The parameterized types above {@code type}: a class, walked from its declaration as reflection walks a class, or a
     * parameterized type, its arguments given. Empty for another kind of type, or one the compiler does not know.
     */
    @Override
    public Optional<List<TypeInfo>> apply(TypeInfo type) {
        return read.computeIfAbsent(type, key -> switch (key) {
            case TypeInfo.ClassType(var name) -> element(name).map(element -> above(element.asType(), Map.of()));
            case TypeInfo.ParameterizedType(var raw, var arguments) -> element(raw)
                    .flatMap(element -> above(element, arguments));
            default -> Optional.empty();
        });
    }

    /**
     * The parameterized types above {@code raw} with {@code arguments}. The compiler puts a class argument in place of
     * the type variable it stands for, and the conversion to the index's types puts any other one, a parameterized
     * type, an array, a wildcard or a type variable, as the index wrote it: a type alone does not declare its type
     * variables, and reflection carries such an argument the same way at start (BUG-20261117-17). Empty for a class
     * argument the compiler does not know, and for a member class of a generic class, which the index's types name
     * without the arguments of the class that encloses it.
     */
    private Optional<List<TypeInfo>> above(TypeElement raw, List<TypeInfo> arguments) {
        var parameters = raw.getTypeParameters();
        var asWritten = new HashMap<Element, TypeInfo>();
        var mirrors = new ArrayList<TypeMirror>();
        for (int i = 0; i < arguments.size() && i < parameters.size(); i++) {
            argument(parameters.get(i), arguments.get(i), asWritten).ifPresent(mirrors::add);
        }
        try {
            return mirrors.size() == arguments.size()
                    ? Optional.of(above(types.getDeclaredType(raw, mirrors.toArray(TypeMirror[]::new)), asWritten))
                    : Optional.empty();
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    /**
     * The compiler's type of a class argument, or else the type variable {@code argument} stands for, which
     * {@code asWritten} then maps to it.
     */
    private Optional<TypeMirror> argument(TypeParameterElement parameter, TypeInfo argument,
            Map<Element, TypeInfo> asWritten) {
        if (argument instanceof TypeInfo.ClassType(var name)) {
            return element(name).map(types::getDeclaredType);
        }
        asWritten.put(parameter, argument);
        return Optional.of(parameter.asType());
    }

    private List<TypeInfo> above(TypeMirror type, Map<Element, TypeInfo> asWritten) {
        var climb = new Climb(types.asElement(type), asWritten, new LinkedHashSet<>(), new HashSet<>());
        walk(type, climb);
        return List.copyOf(climb.found());
    }

    private Optional<TypeElement> element(DotName name) {
        return Optional.ofNullable(TypeLookup.typeElementByBinaryName(elements, name.value()));
    }

    /**
     * Adds the parameterized types above {@code type} to the types {@code climb} found, walking each class or interface
     * once, each type variable it maps replaced by its argument.
     */
    private void walk(TypeMirror type, Climb climb) {
        for (var supertype : types.directSupertypes(type)) {
            if (supertype instanceof DeclaredType declared && !isObject(declared)) {
                if (declared.getTypeArguments().isEmpty()) {
                    // Raw, or not generic: reflection walks the class itself, its type variables unresolved
                    var element = declared.asElement();
                    if (climb.walkedRaw().add(element)) {
                        walk(element.asType(), climb);
                    }
                } else if (climb.found().add(supertypeInfo(declared, climb))) {
                    walk(declared, climb);
                }
            }
        }
    }

    /**
     * {@code type}, above the root of {@code climb}, in the index's terms, or its raw class when a type variable of a
     * class above the root is among its type arguments: only a supertype the root names raw leaves one there, and Java
     * erases the types above a raw type (JLS 4.8). {@code Legacy<E> extends ArrayList} is a {@code List}, not a
     * {@code List<E>}, whose {@code E} is {@code ArrayList}'s (BUG-20261117-14).
     */
    private TypeInfo supertypeInfo(DeclaredType type, Climb climb) {
        return hasForeignTypeVariable(type, climb.root())
                ? new TypeInfo.ClassType(DotName.of(elements.getBinaryName((TypeElement) type.asElement()).toString()))
                : typeInfo(type, new Reading(climb.asWritten(), new HashSet<>()));
    }

    /**
     * Whether a type argument of {@code type}, at any depth, a wildcard's bounds included, is a type variable a
     * declaration other than {@code root} declares.
     */
    private static boolean hasForeignTypeVariable(TypeMirror type, Element root) {
        return switch (type.getKind()) {
            case DECLARED -> ((DeclaredType) type).getTypeArguments().stream()
                    .anyMatch(argument -> hasForeignTypeVariable(argument, root));
            case ARRAY -> hasForeignTypeVariable(((ArrayType) type).getComponentType(), root);
            case WILDCARD -> Stream.of(((WildcardType) type).getExtendsBound(), ((WildcardType) type).getSuperBound())
                    .anyMatch(bound -> bound != null && hasForeignTypeVariable(bound, root));
            case TYPEVAR -> !root.equals(
                    ((TypeParameterElement) ((TypeVariable) type).asElement()).getGenericElement());
            default -> false;
        };
    }

    /**
     * {@code type} in the index's terms, as {@code BeanDiscovery} converts the reflective type: {@code null} for a kind
     * reflection does not give, which a parameterized type leaves out of its arguments.
     */
    private TypeInfo typeInfo(TypeMirror type, Reading reading) {
        return switch (type.getKind()) {
            case DECLARED -> declaredTypeInfo((DeclaredType) type, reading);
            case ARRAY -> arrayTypeInfo((ArrayType) type, reading);
            case TYPEVAR -> typeVariableInfo((TypeVariable) type, reading);
            case WILDCARD -> {
                var wildcard = (WildcardType) type;
                yield new TypeInfo.WildcardType(boundInfo(wildcard.getExtendsBound(), reading),
                        boundInfo(wildcard.getSuperBound(), reading));
            }
            // Reflection names the component class of an array of primitives after the primitive
            case BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE -> new TypeInfo.ClassType(
                    DotName.of(type.getKind().name().toLowerCase(Locale.ROOT)));
            default -> null;
        };
    }

    private TypeInfo declaredTypeInfo(DeclaredType type, Reading reading) {
        var name = DotName.of(elements.getBinaryName((TypeElement) type.asElement()).toString());
        var arguments = new ArrayList<TypeInfo>();
        for (var argument : type.getTypeArguments()) {
            var info = typeInfo(argument, reading);
            if (info != null) {
                arguments.add(info);
            }
        }
        return arguments.isEmpty() ? new TypeInfo.ClassType(name) : new TypeInfo.ParameterizedType(name, arguments);
    }

    private TypeInfo arrayTypeInfo(ArrayType type, Reading reading) {
        int dimensions = 1;
        var component = type.getComponentType();
        while (component.getKind() == TypeKind.ARRAY) {
            dimensions++;
            component = ((ArrayType) component).getComponentType();
        }
        // An argument as the index wrote it may be an array itself: reflection gives one array of both dimensions
        var info = typeInfo(component, reading);
        return info instanceof TypeInfo.ArrayType(var inner, var more)
                ? new TypeInfo.ArrayType(inner, dimensions + more) : new TypeInfo.ArrayType(info, dimensions);
    }

    /**
     * The argument a type variable stands for, as the index wrote it, or else the type variable with its bounds but
     * {@code Object}, or without any when met again inside them.
     */
    private TypeInfo typeVariableInfo(TypeVariable variable, Reading reading) {
        var element = variable.asElement();
        var argument = reading.asWritten().get(element);
        if (argument != null) {
            return argument;
        }
        var name = element.getSimpleName().toString();
        if (!reading.visiting().add(element)) {
            return new TypeInfo.TypeVariable(name, List.of());
        }
        var upper = variable.getUpperBound();
        var declared = upper instanceof IntersectionType intersection ? intersection.getBounds() : List.of(upper);
        var bounds = new ArrayList<TypeInfo>();
        for (var bound : declared) {
            var info = boundInfo(bound, reading);
            if (info != null) {
                bounds.add(info);
            }
        }
        reading.visiting().remove(element);
        return new TypeInfo.TypeVariable(name, bounds);
    }

    /** A bound in the index's terms, {@code null} for none or for {@code Object}, which reflection leaves out. */
    private TypeInfo boundInfo(TypeMirror bound, Reading reading) {
        return bound == null || bound instanceof DeclaredType declared && isObject(declared)
                ? null : typeInfo(bound, reading);
    }

    /**
     * A conversion to the index's types: the type variables it replaces with an argument as the index wrote it, and
     * those whose bounds it is inside.
     */
    private record Reading(Map<Element, TypeInfo> asWritten, Set<Element> visiting) {
    }

    /**
     * A walk above {@code root}, the class or the raw class of the parameterized type it starts from: the type
     * variables it replaces with an argument as the index wrote it, the types it found, and the raw classes it walked.
     */
    private record Climb(Element root, Map<Element, TypeInfo> asWritten, Set<TypeInfo> found, Set<Element> walkedRaw) {
    }

    private static boolean isObject(DeclaredType type) {
        return ((TypeElement) type.asElement()).getQualifiedName().contentEquals(JAVA_LANG_OBJECT);
    }
}
