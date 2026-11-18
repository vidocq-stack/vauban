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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ClassInfo(
        DotName name,
        DotName superName,
        List<DotName> interfaces,
        int accessFlags,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        List<AnnotationInfo> annotations,
        ClassKind kind,
        String simpleName,
        List<TypeInfo.TypeVariable> typeParameters,
        boolean genericMembers,
        TypeInfo genericSuperclass,
        List<TypeInfo> genericInterfaces,
        List<TypeAnnotationInfo> typeAnnotations,
        DotName enclosingClass,
        int modifiers
) {

    public enum ClassKind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    }

    /**
     * @param simpleName        the unqualified class name as {@link Class#getSimpleName()} gives it:
     *                          {@code ReportService} for the nested class {@code Outer$ReportService}, where
     *                          {@link DotName#simpleName()} keeps {@code Outer$ReportService}. {@code null} falls back
     *                          to {@link DotName#simpleName()}, which is right for a top-level class only.
     * @param typeParameters    the type parameters the class declares, in declaration order, each with the bounds it
     *                          declares; like every {@link TypeInfo.TypeVariable} of the index, the implicit
     *                          {@code java.lang.Object} bound is left out. {@code null} means none.
     * @param genericMembers    whether the types of the fields, parameters and return types are the generic ones the
     *                          source declares ({@code List<String>}), as reflection gives them, rather than their
     *                          erasures: true when the index read them from the {@code Signature} attributes or from
     *                          the compiler's elements (VAU-PERF-007).
     * @param genericSuperclass the superclass {@code superName} names, as the class declares it, with its type
     *                          arguments ({@code Base<List<String>>}), its type variables read like those of
     *                          {@code typeParameters}. It follows {@code superName}, not
     *                          {@link Class#getGenericSuperclass()}: for an interface, the class file names
     *                          {@code java.lang.Object}, which the bytecode scan records, where reflection gives
     *                          {@code null}, as the compiler does. {@code null} stands for {@code superName} raw
     *                          (BUG-20261117-22).
     * @param genericInterfaces the interfaces as the class declares them, with their type arguments, in the order of
     *                          {@code interfaces}, as {@link Class#getGenericInterfaces()} gives them. {@code null}
     *                          gives each of {@code interfaces} raw (BUG-20261117-22).
     * @param typeAnnotations   the runtime-retained annotations on the types the class declares: its supertypes,
     *                          its type parameters and their bounds (BUG-20261117-60). {@code null} means none.
     * @param enclosingClass    the class that encloses an inner member class, which is not {@code static}: its
     *                          constructors take an instance of it first, and its type variables are in scope in the
     *                          class (BUG-20261118-01). {@code null} for any other class.
     * @param modifiers         the class's modifiers as {@link Class#getModifiers()} gives them: a nested class's from
     *                          its own entry of the {@code InnerClasses} attribute, which keeps its {@code static},
     *                          {@code private} and {@code protected}, and never {@code ACC_SUPER}, which reads as
     *                          {@code synchronized}. {@code accessFlags} stays the class file's, which the bean
     *                          discovery reads (BUG-20261117-37).
     * @throws IllegalArgumentException when {@code genericSuperclass} is given without a {@code superName}, or when
     *                                  {@code genericInterfaces} does not name as many interfaces as
     *                                  {@code interfaces}: each says how the class declares a supertype the class
     *                                  file names
     */
    public ClassInfo {
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        annotations = List.copyOf(annotations);
        if (simpleName == null) {
            simpleName = name.simpleName();
        }
        typeParameters = typeParameters == null ? List.of() : List.copyOf(typeParameters);
        if (superName == null && genericSuperclass != null) {
            throw new IllegalArgumentException(name + " has no superclass, but a generic superclass");
        }
        genericSuperclass = superName == null ? null : Objects.requireNonNullElse(genericSuperclass,
                new TypeInfo.ClassType(superName));
        genericInterfaces = genericInterfaces == null ? raw(interfaces) : List.copyOf(genericInterfaces);
        if (genericInterfaces.size() != interfaces.size()) {
            throw new IllegalArgumentException(name + " declares " + interfaces.size() + " interfaces, not "
                    + genericInterfaces.size());
        }
        typeAnnotations = TypeAnnotationInfo.canonical(typeAnnotations);
    }

    /**
     * A class info whose modifiers are its access flags but {@code ACC_SUPER}: a top-level class read from its class
     * file, or a class the compiler describes.
     */
    public ClassInfo(DotName name, DotName superName, List<DotName> interfaces, int accessFlags,
                     List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations,
                     ClassKind kind, String simpleName, List<TypeInfo.TypeVariable> typeParameters,
                     boolean genericMembers, TypeInfo genericSuperclass, List<TypeInfo> genericInterfaces,
                     List<TypeAnnotationInfo> typeAnnotations, DotName enclosingClass) {
        this(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName, typeParameters,
                genericMembers, genericSuperclass, genericInterfaces, typeAnnotations, enclosingClass,
                accessFlags & ~ACC_SUPER);
    }

    /** {@code ACC_SUPER}, which javac sets on every class it compiles; {@code synchronized} for a modifier. */
    private static final int ACC_SUPER = 0x0020;

    /** A class info that no class encloses as an inner member class. */
    public ClassInfo(DotName name, DotName superName, List<DotName> interfaces, int accessFlags,
                     List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations,
                     ClassKind kind, String simpleName, List<TypeInfo.TypeVariable> typeParameters,
                     boolean genericMembers, TypeInfo genericSuperclass, List<TypeInfo> genericInterfaces,
                     List<TypeAnnotationInfo> typeAnnotations) {
        this(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName, typeParameters,
                genericMembers, genericSuperclass, genericInterfaces, typeAnnotations, null);
    }

    /** A class info whose types carry no annotation. */
    public ClassInfo(DotName name, DotName superName, List<DotName> interfaces, int accessFlags,
                     List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations,
                     ClassKind kind, String simpleName, List<TypeInfo.TypeVariable> typeParameters,
                     boolean genericMembers, TypeInfo genericSuperclass, List<TypeInfo> genericInterfaces) {
        this(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName, typeParameters,
                genericMembers, genericSuperclass, genericInterfaces, null);
    }

    /** A class info whose supertypes are those {@code superName} and {@code interfaces} name, raw. */
    public ClassInfo(DotName name, DotName superName, List<DotName> interfaces, int accessFlags,
                     List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations,
                     ClassKind kind, String simpleName, List<TypeInfo.TypeVariable> typeParameters,
                     boolean genericMembers) {
        this(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName, typeParameters,
                genericMembers, null, null, null);
    }

    /** A class info whose member types are erased (no {@code Signature} read). */
    public ClassInfo(DotName name, DotName superName, List<DotName> interfaces, int accessFlags,
                     List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations,
                     ClassKind kind, String simpleName, List<TypeInfo.TypeVariable> typeParameters) {
        this(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName, typeParameters,
                false);
    }

    /** A class info that declares no type parameter. */
    public ClassInfo(DotName name, DotName superName, List<DotName> interfaces, int accessFlags,
                     List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations,
                     ClassKind kind, String simpleName) {
        this(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName, null);
    }

    /** A class info whose simple name is derived from its binary name: right for a top-level class only. */
    public ClassInfo(DotName name, DotName superName, List<DotName> interfaces, int accessFlags,
                     List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations,
                     ClassKind kind) {
        this(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, null, null);
    }

    /** The same class with other annotations, as an enrichment step adds them. */
    public ClassInfo withAnnotations(List<AnnotationInfo> newAnnotations) {
        return new ClassInfo(name, superName, interfaces, accessFlags, fields, methods, newAnnotations, kind,
                simpleName, typeParameters, genericMembers, genericSuperclass, genericInterfaces, typeAnnotations,
                enclosingClass, modifiers);
    }

    private static List<TypeInfo> raw(List<DotName> interfaces) {
        var types = new ArrayList<TypeInfo>(interfaces.size());
        for (var name : interfaces) {
            types.add(new TypeInfo.ClassType(name));
        }
        return Collections.unmodifiableList(types);
    }

    public boolean isPublic() {
        return (accessFlags & 0x0001) != 0;
    }

    public boolean isAbstract() {
        return (accessFlags & 0x0400) != 0;
    }

    public boolean isFinal() {
        return (accessFlags & 0x0010) != 0;
    }

    public boolean isInterface() {
        return kind == ClassKind.INTERFACE || kind == ClassKind.ANNOTATION;
    }

    public boolean isAnnotation() {
        return kind == ClassKind.ANNOTATION;
    }

    public boolean isEnum() {
        return kind == ClassKind.ENUM;
    }

    public boolean isRecord() {
        return kind == ClassKind.RECORD;
    }

    public Optional<AnnotationInfo> annotation(DotName annotationName) {
        for (var a : annotations) {
            if (a.name().equals(annotationName)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    public boolean hasAnnotation(DotName annotationName) {
        return annotation(annotationName).isPresent();
    }
}
