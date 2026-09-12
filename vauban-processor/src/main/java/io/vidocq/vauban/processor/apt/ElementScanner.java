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
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Converts javax.lang.model elements to Vauban indexer model.
 */
public final class ElementScanner {

    private final Elements elements;
    private final Types types;

    public ElementScanner(Elements elements, Types types) {
        this.elements = Objects.requireNonNull(elements);
        this.types = Objects.requireNonNull(types);
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

        // Superclass
        DotName superName = null;
        var superMirror = typeElement.getSuperclass();
        if (superMirror.getKind() != TypeKind.NONE) {
            superName = typeMirrorToDotName(superMirror);
        }

        // Interfaces
        var interfaces = typeElement.getInterfaces().stream()
                .map(this::typeMirrorToDotName)
                .toList();

        // Access flags
        int accessFlags = modifiersToFlags(typeElement.getModifiers());
        if (typeElement.getKind() == ElementKind.INTERFACE) accessFlags |= 0x0200; // ACC_INTERFACE
        if (typeElement.getKind() == ElementKind.ANNOTATION_TYPE) accessFlags |= 0x2000 | 0x0200; // ACC_ANNOTATION | ACC_INTERFACE
        if (typeElement.getKind() == ElementKind.ENUM) accessFlags |= 0x4000; // ACC_ENUM

        // Fields
        var fields = typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD)
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

        return new ClassInfo(name, superName, interfaces, accessFlags, fields, methods, annotations, kind);
    }

    private FieldInfo scanField(VariableElement field) {
        var name = field.getSimpleName().toString();
        var type = typeMirrorToTypeInfo(field.asType());
        var accessFlags = modifiersToFlags(field.getModifiers());
        var annotations = scanAnnotations(field);
        return new FieldInfo(name, type, accessFlags, annotations);
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

        return new MethodInfo(name, returnType, parameters, exceptionTypes, accessFlags, annotations);
    }

    private List<AnnotationInfo> scanAnnotations(Element element) {
        var result = new ArrayList<AnnotationInfo>();
        for (var mirror : element.getAnnotationMirrors()) {
            var annName = DotName.of(((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString());
            var members = new LinkedHashMap<String, io.vidocq.vauban.indexer.model.AnnotationValue>();

            for (var entry : mirror.getElementValues().entrySet()) {
                var memberName = entry.getKey().getSimpleName().toString();
                var memberValue = convertAnnotationValue(entry.getValue());
                if (memberValue != null) {
                    members.put(memberName, memberValue);
                }
            }

            result.add(new AnnotationInfo(annName, members));
        }
        return result;
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
                    DotName.of(((TypeElement) ve.getEnclosingElement()).getQualifiedName().toString()),
                    ve.getSimpleName().toString());
            case AnnotationMirror am -> {
                var annName = DotName.of(((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().toString());
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
                            .map(this::typeMirrorToTypeInfo)
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
                yield new TypeInfo.ArrayType(typeMirrorToTypeInfo(component), dims);
            }
            case TYPEVAR -> {
                var tv = (javax.lang.model.type.TypeVariable) mirror;
                var bounds = new ArrayList<TypeInfo>();
                var upper = tv.getUpperBound();
                if (upper != null && upper.getKind() != TypeKind.NONE) {
                    // Skip java.lang.Object bound (implicit)
                    if (!(upper.getKind() == TypeKind.DECLARED
                            && "java.lang.Object".equals(((TypeElement) ((DeclaredType) upper).asElement()).getQualifiedName().toString()))) {
                        bounds.add(typeMirrorToTypeInfo(upper));
                    }
                }
                yield new TypeInfo.TypeVariable(tv.asElement().getSimpleName().toString(), bounds);
            }
            case WILDCARD -> {
                var wt = (javax.lang.model.type.WildcardType) mirror;
                TypeInfo upper = wt.getExtendsBound() != null ? typeMirrorToTypeInfo(wt.getExtendsBound()) : null;
                TypeInfo lower = wt.getSuperBound() != null ? typeMirrorToTypeInfo(wt.getSuperBound()) : null;
                yield new TypeInfo.WildcardType(upper, lower);
            }
            default -> new TypeInfo.ClassType(DotName.of("java.lang.Object")); // fallback
        };
    }

    private DotName typeMirrorToDotName(TypeMirror mirror) {
        if (mirror.getKind() == TypeKind.DECLARED) {
            var element = (TypeElement) ((DeclaredType) mirror).asElement();
            return DotName.of(element.getQualifiedName().toString());
        }
        return DotName.of("java.lang.Object");
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
