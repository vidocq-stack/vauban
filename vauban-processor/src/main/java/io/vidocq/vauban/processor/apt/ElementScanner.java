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

import io.vidocq.vauban.core.bean.discovery.PackageInfos;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Target;
import io.vidocq.vauban.indexer.model.TypeInfo;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Converts javax.lang.model elements to Vauban indexer model.
 */
public final class ElementScanner {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    /** The flags javac gives a {@code package-info} class. */
    private static final int PACKAGE_INFO_FLAGS = ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
            | ClassFile.ACC_SYNTHETIC;

    private final Elements elements;

    /**
     * Creates a scanner.
     *
     * @param elements the element utilities of the processing environment
     * @param types    the type utilities of the processing environment; checked, not kept
     *                 (the scanner needs no type operation, the parameter stays for callers)
     */
    public ElementScanner(Elements elements, Types types) {
        this.elements = Objects.requireNonNull(elements);
        Objects.requireNonNull(types);
    }

    /**
     * Converts a TypeElement to a ClassInfo.
     */
    public ClassInfo scan(TypeElement typeElement) {
        // Binary name, for the same reason as in typeMirrorToTypeInfo below: a nested bean's
        // qualified name (app.Holder.Counter) names nothing at runtime — the class is
        // app.Holder$Counter. Every artifact derived from this name (the client proxy, the
        // factory, the bean list) would otherwise be emitted into a class-as-package directory,
        // extending a superclass that does not exist.
        var name = DotName.of(elements.getBinaryName(typeElement).toString());

        // Superclass, and as the class declares it, with its type arguments, as the bytecode scan reads it from the
        // class signature (BUG-20261117-22)
        var typeParameters = new TypeParameterReader(typeElement);
        DotName superName = null;
        TypeInfo genericSuperclass = null;
        var superMirror = typeElement.getSuperclass();
        if (superMirror.getKind() != TypeKind.NONE) {
            superName = typeMirrorToDotName(superMirror);
            genericSuperclass = typeParameters.supertype(superMirror);
        }

        // Interfaces, and as the class declares them
        var interfaces = typeElement.getInterfaces().stream()
                .map(this::typeMirrorToDotName)
                .toList();
        var genericInterfaces = typeElement.getInterfaces().stream()
                .map(typeParameters::supertype)
                .toList();

        // Access flags
        int accessFlags = modifiersToFlags(typeElement.getModifiers());
        if (typeElement.getKind() == ElementKind.INTERFACE) accessFlags |= 0x0200; // ACC_INTERFACE
        if (typeElement.getKind() == ElementKind.ANNOTATION_TYPE) accessFlags |= 0x2000 | 0x0200; // ACC_ANNOTATION | ACC_INTERFACE
        if (typeElement.getKind() == ElementKind.ENUM) accessFlags |= 0x4000; // ACC_ENUM

        // Fields, an enum's constants among them, as the class file has them (BUG-20261117-59)
        var fields = typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT)
                .map(e -> scanField((VariableElement) e))
                .toList();

        // Methods and constructors
        var methods = typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.METHOD || e.getKind() == ElementKind.CONSTRUCTOR)
                .map(e -> scanMethod((ExecutableElement) e))
                .toList();

        // Annotations
        var annotations = scanAnnotations(typeElement);

        // Kind
        var kind = switch (typeElement.getKind()) {
            case INTERFACE -> ClassKind.INTERFACE;
            case ENUM -> ClassKind.ENUM;
            case RECORD -> ClassKind.RECORD;
            case ANNOTATION_TYPE -> ClassKind.ANNOTATION;
            default -> ClassKind.CLASS;
        };

        // The annotations on the types the class declares (BUG-20261117-60)
        var typeAnnotations = typeAnnotations().types(typeElement.getInterfaces(), Target.INTERFACE)
                .typeParameters(typeElement.getTypeParameters());
        if (superMirror.getKind() != TypeKind.NONE) {
            typeAnnotations.type(superMirror, Target.SUPERCLASS, 0);
        }

        return new ClassInfo(name, superName, interfaces, accessFlags, fields, methods, annotations, kind,
                typeElement.getSimpleName().toString(), typeParameters.typeParameters(), false, genericSuperclass,
                genericInterfaces, typeAnnotations.found());
    }

    private MirrorTypeAnnotations typeAnnotations() {
        return new MirrorTypeAnnotations(this::annotationInfo, ElementScanner::isJavaLangObject);
    }

    /**
     * Converts a package to its {@code package-info} class, as the bytecode scan reads it: a synthetic interface named
     * {@code <package>.package-info}, which carries the package's annotations. The index holds a package that way, and
     * discovery reads a package's {@code @Vetoed} from it.
     */
    public ClassInfo scan(PackageElement packageElement) {
        var name = PackageInfos.of(packageElement.getQualifiedName().toString());
        return new ClassInfo(name, DotName.of(JAVA_LANG_OBJECT), List.of(), PACKAGE_INFO_FLAGS, List.of(), List.of(),
                scanAnnotations(packageElement), ClassKind.INTERFACE);
    }

    /**
     * Reads the type parameters of one class the way the bytecode scan reads them from the class signature, so both
     * build the same index: each with the bounds it declares, the implicit {@code java.lang.Object} bound left out;
     * a bound naming another type parameter of the class carries that parameter's bounds, while a type parameter met
     * again while its own bounds are read, or one of an enclosing class, is recorded without bounds.
     */
    private final class TypeParameterReader {

        /** The type parameters the class, or the method, declares. */
        private final List<? extends TypeParameterElement> own;
        /** Those whose bounds a bound carries: the class's, and a method's own. */
        private final List<TypeParameterElement> declared = new ArrayList<>();
        private final Set<Element> reading = new HashSet<>();

        TypeParameterReader(TypeElement typeElement) {
            this.own = typeElement.getTypeParameters();
            declared.addAll(own);
        }

        /**
         * The reader of a method's type parameters, as the bytecode scan reads them from its signature: a bound naming
         * a type parameter of the method or of its class carries that parameter's bounds (BUG-20261117-51).
         */
        TypeParameterReader(ExecutableElement method) {
            this.own = method.getTypeParameters();
            if (method.getEnclosingElement() instanceof TypeElement type) {
                declared.addAll(type.getTypeParameters());
            }
            declared.addAll(own);
        }

        List<TypeInfo.TypeVariable> typeParameters() {
            return own.stream().map(this::typeVariable).toList();
        }

        /**
         * A superclass or an interface as the class declares it, its type variables read as those of
         * {@link #typeParameters()}, as the bytecode scan reads the class signature.
         */
        TypeInfo supertype(TypeMirror supertype) {
            return unresolved(supertype).<TypeInfo>map(TypeInfo.ClassType::new)
                    .orElseGet(() -> typeMirrorToTypeInfo(supertype, this::reference));
        }

        private TypeInfo.TypeVariable typeVariable(TypeParameterElement parameter) {
            var name = parameter.getSimpleName().toString();
            if (!reading.add(parameter)) {
                return new TypeInfo.TypeVariable(name, List.of());
            }
            var bounds = parameter.getBounds().stream()
                    .filter(bound -> !isJavaLangObject(bound))
                    .map(bound -> typeMirrorToTypeInfo(bound, this::reference))
                    .toList();
            reading.remove(parameter);
            return new TypeInfo.TypeVariable(name, bounds);
        }

        private TypeInfo reference(javax.lang.model.type.TypeVariable variable) {
            var element = variable.asElement();
            return declared.contains(element)
                    ? typeVariable((TypeParameterElement) element)
                    : new TypeInfo.TypeVariable(element.getSimpleName().toString(), List.of());
        }
    }

    private FieldInfo scanField(VariableElement field) {
        var name = field.getSimpleName().toString();
        var type = typeMirrorToTypeInfo(field.asType());
        var accessFlags = modifiersToFlags(field.getModifiers())
                | (field.getKind() == ElementKind.ENUM_CONSTANT ? ClassFile.ACC_ENUM : 0);
        var annotations = scanAnnotations(field);
        return new FieldInfo(name, type, accessFlags, annotations,
                typeAnnotations().type(field.asType(), Target.FIELD, 0).found());
    }

    private MethodInfo scanMethod(ExecutableElement method) {
        var name = method.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : method.getSimpleName().toString();
        var returnType = typeMirrorToTypeInfo(method.getReturnType());

        var parameters = new ArrayList<ParameterInfo>();
        for (var param : method.getParameters()) {
            parameters.add(new ParameterInfo(
                    param.getSimpleName().toString(),
                    typeMirrorToTypeInfo(param.asType()),
                    scanAnnotations(param)
            ));
        }

        var exceptionTypes = method.getThrownTypes().stream()
                .map(this::typeMirrorToTypeInfo)
                .toList();

        var accessFlags = modifiersToFlags(method.getModifiers());
        var annotations = scanAnnotations(method);
        var defaultValue = method.getDefaultValue() == null ? null : convertAnnotationValue(method.getDefaultValue());

        var typeAnnotations = typeAnnotations().type(method.getReturnType(), Target.RETURN, 0)
                .parameters(method.getParameters()).types(method.getThrownTypes(), Target.THROWS)
                .typeParameters(method.getTypeParameters());
        if (MirrorTypeAnnotations.isPresent(method.getReceiverType())) {
            typeAnnotations.type(method.getReceiverType(), Target.RECEIVER, 0);
        }
        if (method.getKind() == ElementKind.CONSTRUCTOR) {
            typeAnnotations.constructor(method);
        }

        return new MethodInfo(name, returnType, parameters, exceptionTypes, accessFlags, annotations, defaultValue,
                new TypeParameterReader(method).typeParameters(), typeAnnotations.found());
    }

    private List<AnnotationInfo> scanAnnotations(Element element) {
        var result = new ArrayList<AnnotationInfo>();
        for (var mirror : element.getAnnotationMirrors()) {
            result.add(annotationInfo(mirror));
        }
        return result;
    }

    private AnnotationInfo annotationInfo(AnnotationMirror mirror) {
        var annName = binaryName((TypeElement) mirror.getAnnotationType().asElement());
        var members = new LinkedHashMap<String, io.vidocq.vauban.indexer.model.AnnotationValue>();
        for (var entry : mirror.getElementValues().entrySet()) {
            var memberName = entry.getKey().getSimpleName().toString();
            var memberValue = convertAnnotationValue(entry.getValue());
            if (memberValue != null) {
                members.put(memberName, memberValue);
            }
        }
        return new AnnotationInfo(annName, members);
    }

    private io.vidocq.vauban.indexer.model.AnnotationValue convertAnnotationValue(
            javax.lang.model.element.AnnotationValue value) {
        var v = value.getValue();
        return switch (v) {
            case String s -> new io.vidocq.vauban.indexer.model.AnnotationValue.StringVal(s);
            case Boolean b -> new io.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal(b);
            case Byte b -> new io.vidocq.vauban.indexer.model.AnnotationValue.ByteVal(b);
            case Character c -> new io.vidocq.vauban.indexer.model.AnnotationValue.CharVal(c);
            case Short s -> new io.vidocq.vauban.indexer.model.AnnotationValue.ShortVal(s);
            case Integer i -> new io.vidocq.vauban.indexer.model.AnnotationValue.IntVal(i);
            case Long l -> new io.vidocq.vauban.indexer.model.AnnotationValue.LongVal(l);
            case Float f -> new io.vidocq.vauban.indexer.model.AnnotationValue.FloatVal(f);
            case Double d -> new io.vidocq.vauban.indexer.model.AnnotationValue.DoubleVal(d);
            case TypeMirror tm -> new io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal(typeMirrorToDotName(tm));
            case VariableElement ve -> new io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal(
                    binaryName((TypeElement) ve.getEnclosingElement()),
                    ve.getSimpleName().toString());
            case AnnotationMirror am -> {
                var annName = binaryName((TypeElement) am.getAnnotationType().asElement());
                var members = new LinkedHashMap<String, io.vidocq.vauban.indexer.model.AnnotationValue>();
                for (var entry : am.getElementValues().entrySet()) {
                    members.put(entry.getKey().getSimpleName().toString(), convertAnnotationValue(entry.getValue()));
                }
                yield new io.vidocq.vauban.indexer.model.AnnotationValue.AnnotationVal(new AnnotationInfo(annName, members));
            }
            case List<?> list -> {
                @SuppressWarnings("unchecked")
                var annotValues = (List<? extends javax.lang.model.element.AnnotationValue>) list;
                yield new io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal(
                        annotValues.stream().map(this::convertAnnotationValue).toList());
            }
            default -> null;
        };
    }

    TypeInfo typeMirrorToTypeInfo(TypeMirror mirror) {
        return typeMirrorToTypeInfo(mirror, new MemberTypeVariables()::typeVariable);
    }

    /** Converts a type, mapping each type variable it meets with {@code typeVariables}. */
    private TypeInfo typeMirrorToTypeInfo(TypeMirror mirror,
            Function<javax.lang.model.type.TypeVariable, TypeInfo> typeVariables) {
        return switch (mirror.getKind()) {
            case VOID -> new TypeInfo.VoidType();
            case BOOLEAN -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.BOOLEAN);
            case BYTE -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.BYTE);
            case CHAR -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.CHAR);
            case SHORT -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.SHORT);
            case INT -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT);
            case LONG -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.LONG);
            case FLOAT -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.FLOAT);
            case DOUBLE -> new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.DOUBLE);
            case DECLARED -> {
                var declaredType = (DeclaredType) mirror;
                var element = (TypeElement) declaredType.asElement();
                // Binary name (not qualified): nested classes are joined with '$', not '.', so the
                // generated client-proxy bytecode emits a valid descriptor (e.g. Outer$Nested, not
                // Outer/Nested). getQualifiedName() loses the '$' and breaks proxy method signatures
                // whose return/param type is a nested class. Top-level types are unaffected.
                var dotName = DotName.of(elements.getBinaryName(element).toString());
                if (declaredType.getTypeArguments().isEmpty()) {
                    yield new TypeInfo.ClassType(dotName);
                } else {
                    var typeArgs = declaredType.getTypeArguments().stream()
                            .map(argument -> typeMirrorToTypeInfo(argument, typeVariables))
                            .toList();
                    yield new TypeInfo.ParameterizedType(dotName, typeArgs);
                }
            }
            case ARRAY -> {
                // Count dimensions
                int dims = 0;
                TypeMirror component = mirror;
                while (component.getKind() == TypeKind.ARRAY) {
                    dims++;
                    component = ((javax.lang.model.type.ArrayType) component).getComponentType();
                }
                yield new TypeInfo.ArrayType(typeMirrorToTypeInfo(component, typeVariables), dims);
            }
            case TYPEVAR -> typeVariables.apply((javax.lang.model.type.TypeVariable) mirror);
            case WILDCARD -> {
                var wt = (javax.lang.model.type.WildcardType) mirror;
                // ? extends Object is ?, which the index records without bounds, as the bytecode scan and
                // reflection read both (BUG-20261117-34).
                TypeInfo upper = wt.getExtendsBound() != null && !isJavaLangObject(wt.getExtendsBound())
                        ? typeMirrorToTypeInfo(wt.getExtendsBound(), typeVariables) : null;
                TypeInfo lower = wt.getSuperBound() != null
                        ? typeMirrorToTypeInfo(wt.getSuperBound(), typeVariables) : null;
                yield new TypeInfo.WildcardType(upper, lower);
            }
            default -> new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)); // fallback
        };
    }

    /**
     * The type variables of one field, method or parameter type, each with its upper bound unless that is Object. A
     * type variable met again while its own bound is read ({@code T} in {@code <T extends Comparable<T>>}) has no
     * bound, as the bytecode scan records it, so the model stays finite: its bound was read again and again, until a
     * {@code StackOverflowError} failed the compilation (BUG-20261117-36).
     */
    private final class MemberTypeVariables {

        private final Set<Element> reading = new HashSet<>();

        TypeInfo typeVariable(javax.lang.model.type.TypeVariable variable) {
            var element = variable.asElement();
            var name = element.getSimpleName().toString();
            if (!reading.add(element)) {
                return new TypeInfo.TypeVariable(name, List.of());
            }
            var bounds = new ArrayList<TypeInfo>();
            var upper = variable.getUpperBound();
            // The java.lang.Object bound is implicit: skip it.
            if (upper != null && upper.getKind() != TypeKind.NONE && !isJavaLangObject(upper)) {
                bounds.add(typeMirrorToTypeInfo(upper, this::typeVariable));
            }
            reading.remove(element);
            return new TypeInfo.TypeVariable(name, bounds);
        }
    }

    private static boolean isJavaLangObject(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                && JAVA_LANG_OBJECT.equals(((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString());
    }

    /**
     * The name of a class the compiler cannot find, which a class file names: a supertype of a class of the class path
     * whose class file is missing. The bytecode scan names it, and so does the processor's, so that the language
     * model finds no such class and keeps its old answer, as at start (BUG-20261117-31); it was
     * {@code java.lang.Object}.
     */
    private Optional<DotName> unresolved(TypeMirror mirror) {
        return mirror.getKind() == TypeKind.ERROR && mirror instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element
                ? Optional.of(binaryName(element)) : Optional.empty();
    }

    /**
     * The name the class file gives a class literal: the binary name of a declared type, the keyword of
     * a primitive, the descriptor of an array ({@code [Ljava.lang.String;}), as
     * {@code DotName#fromDescriptor} reads them back.
     */
    private DotName typeMirrorToDotName(TypeMirror mirror) {
        return switch (mirror.getKind()) {
            case DECLARED -> binaryName((TypeElement) ((DeclaredType) mirror).asElement());
            case ERROR -> unresolved(mirror).orElseGet(() -> DotName.of(JAVA_LANG_OBJECT));
            case BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE, VOID, ARRAY -> DotName.fromDescriptor(descriptor(mirror));
            default -> DotName.of(JAVA_LANG_OBJECT);
        };
    }

    private String descriptor(TypeMirror mirror) {
        return switch (mirror.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case CHAR -> "C";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((javax.lang.model.type.ArrayType) mirror).getComponentType());
            case DECLARED -> "L" + binaryName((TypeElement) ((DeclaredType) mirror).asElement()).toInternal() + ";";
            default -> "Ljava/lang/Object;";
        };
    }

    /** Nested types are joined with {@code $}, as in the class file: {@code getQualifiedName()} would use {@code .}. */
    private DotName binaryName(TypeElement element) {
        return DotName.of(elements.getBinaryName(element).toString());
    }

    private static int modifiersToFlags(Set<Modifier> modifiers) {
        int flags = 0;
        for (var mod : modifiers) {
            flags |= switch (mod) {
                case PUBLIC -> 0x0001;
                case PRIVATE -> 0x0002;
                case PROTECTED -> 0x0004;
                case STATIC -> 0x0008;
                case FINAL -> 0x0010;
                case ABSTRACT -> 0x0400;
                default -> 0;
            };
        }
        return flags;
    }
}
