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
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.model.TypeInfo.*;

import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

public final class ClassFileScanner {

    private ClassFileScanner() {}

    public static ClassInfo scan(byte[] classBytes) {
        var cm = ClassFile.of().parse(classBytes);
        return buildClassInfo(cm);
    }

    private static ClassInfo buildClassInfo(ClassModel cm) {
        var name = DotName.fromInternal(cm.thisClass().asInternalName());
        var superName = cm.superclass()
                .map(ce -> DotName.fromInternal(ce.asInternalName()))
                .orElse(null);
        var interfaces = cm.interfaces().stream()
                .map(ce -> DotName.fromInternal(ce.asInternalName()))
                .toList();
        var accessFlags = cm.flags().flagsMask();

        var fields = new ArrayList<FieldInfo>();
        var methods = new ArrayList<MethodInfo>();
        var annotations = new ArrayList<AnnotationInfo>();
        String simpleName = null;
        List<TypeVariable> typeParameters = List.of();
        // Read first: the fields and methods resolve their type variables against the class's.
        var classParameters = classTypeParameters(cm);

        for (var element : cm) {
            switch (element) {
                case FieldModel fm -> fields.add(buildFieldInfo(fm, classParameters));
                case MethodModel mm -> methods.add(buildMethodInfo(mm, classParameters));
                case RuntimeVisibleAnnotationsAttribute rvaa ->
                        rvaa.annotations().forEach(a -> annotations.add(buildAnnotationInfo(a)));
                case InnerClassesAttribute ica -> simpleName = innerSimpleName(cm, ica);
                case SignatureAttribute sa -> typeParameters = typeParameters(sa);
                default -> {
                    // other class elements carry nothing the index records
                }
            }
        }

        var kind = determineKind(cm, superName);
        return new ClassInfo(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName,
                typeParameters, true);
    }

    /**
     * The type parameters the class signature declares. A signature that does not parse names no type parameter:
     * the JVM itself only reads it on demand, through reflection, so a malformed one must not fail the scan.
     */
    private static List<TypeVariable> typeParameters(SignatureAttribute attribute) {
        try {
            return ClassTypeParameters.of(attribute.asClassSignature());
        } catch (IllegalArgumentException _) {
            return List.of();
        }
    }

    /**
     * The simple name a nested or local class has in source, from its own entry of the {@code InnerClasses}
     * attribute: {@code ReportService} for {@code Outer$ReportService}, {@code ""} for an anonymous class, as
     * {@link Class#getSimpleName()} gives it. {@code null} for a top-level class, which has no such entry.
     */
    private static String innerSimpleName(ClassModel cm, InnerClassesAttribute attribute) {
        var self = cm.thisClass().asInternalName();
        for (var inner : attribute.classes()) {
            if (inner.innerClass().asInternalName().equals(self)) {
                return inner.innerName().map(Utf8Entry::stringValue).orElse("");
            }
        }
        return null;
    }

    private static ClassKind determineKind(ClassModel cm, DotName superName) {
        int flags = cm.flags().flagsMask();
        boolean isInterface = (flags & ClassFile.ACC_INTERFACE) != 0;
        boolean isAnnotation = (flags & ClassFile.ACC_ANNOTATION) != 0;
        boolean isEnum = (flags & ClassFile.ACC_ENUM) != 0;

        if (isAnnotation) return ClassKind.ANNOTATION;
        if (isInterface) return ClassKind.INTERFACE;
        if (isEnum) return ClassKind.ENUM;

        if (superName != null && "java.lang.Record".equals(superName.value())) {
            return ClassKind.RECORD;
        }

        return ClassKind.CLASS;
    }

    /** The type parameters of the class signature, or none when it has none or it does not parse. */
    private static List<Signature.TypeParam> classTypeParameters(ClassModel cm) {
        try {
            return cm.findAttribute(Attributes.signature())
                    .map(sa -> sa.asClassSignature().typeParameters())
                    .orElse(List.of());
        } catch (IllegalArgumentException _) {
            return List.of();
        }
    }

    /**
     * The generic type a field's {@code Signature} declares (VAU-PERF-007), or its erasure when it has none or it
     * does not parse: reflection then fails on it too, and the container falls back to the same erasure.
     */
    private static TypeInfo fieldType(FieldModel fm, List<Signature.TypeParam> classParameters) {
        var signature = fm.findAttribute(Attributes.signature());
        if (signature.isPresent()) {
            try {
                return ClassTypeParameters.fieldType(classParameters, signature.get().asTypeSignature());
            } catch (IllegalArgumentException _) {
                // erased below
            }
        }
        return parseTypeFromDesc(fm.fieldTypeSymbol());
    }

    /**
     * The generic parameter types then the return type a method's {@code Signature} declares; empty when it has
     * none or it does not parse. A constructor of an inner class or an enum has synthetic leading
     * parameters its signature leaves out: the generic types line up with the last parameters.
     */
    private static Optional<List<TypeInfo>> methodTypes(MethodModel mm, List<Signature.TypeParam> classParameters) {
        var signature = mm.findAttribute(Attributes.signature());
        if (signature.isEmpty()) {
            return Optional.empty();
        }
        try {
            var types = ClassTypeParameters.methodTypes(classParameters, signature.get().asMethodSignature());
            return types.size() - 1 <= mm.methodTypeSymbol().parameterCount() ? Optional.of(types) : Optional.empty();
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static FieldInfo buildFieldInfo(FieldModel fm, List<Signature.TypeParam> classParameters) {
        var name = fm.fieldName().stringValue();
        var type = fieldType(fm, classParameters);
        var accessFlags = fm.flags().flagsMask();
        var annotations = extractAnnotations(fm);
        return new FieldInfo(name, type, accessFlags, annotations);
    }

    private static MethodInfo buildMethodInfo(MethodModel mm, List<Signature.TypeParam> classParameters) {
        var name = mm.methodName().stringValue();
        var methodType = mm.methodTypeSymbol();
        var generic = methodTypes(mm, classParameters);
        var returnType = generic.map(List::getLast).orElseGet(() -> parseTypeFromDesc(methodType.returnType()));
        var parameters = buildParameters(mm, methodType, generic.orElse(null));
        var exceptionTypes = extractExceptionTypes(mm);
        var accessFlags = mm.flags().flagsMask();
        var annotations = extractAnnotations(mm);
        var defaultValue = extractDefaultValue(mm);
        return new MethodInfo(name, returnType, parameters, exceptionTypes, accessFlags, annotations, defaultValue);
    }

    /** The default an annotation type member declares in its {@code AnnotationDefault} attribute, or {@code null}. */
    private static io.vidocq.vauban.indexer.model.AnnotationValue extractDefaultValue(MethodModel mm) {
        for (var attr : mm.attributes()) {
            if (attr instanceof AnnotationDefaultAttribute ada) {
                return buildAnnotationValue(ada.defaultValue());
            }
        }
        return null;
    }

    /** {@code generic}: the method's generic types, return type last, or {@code null} to take the descriptor's. */
    private static List<ParameterInfo> buildParameters(MethodModel mm, MethodTypeDesc methodType,
            List<TypeInfo> generic) {
        var paramNames = extractParameterNames(mm);
        var paramAnnotations = extractParameterAnnotations(mm);
        var params = new ArrayList<ParameterInfo>();
        // The signature leaves out a constructor's synthetic leading parameters: its types are the last ones.
        int synthetic = generic == null ? methodType.parameterCount() : methodType.parameterCount() - (generic.size() - 1);

        for (int i = 0; i < methodType.parameterCount(); i++) {
            var type = generic == null || i < synthetic
                    ? parseTypeFromDesc(methodType.parameterType(i))
                    : generic.get(i - synthetic);
            var paramName = i < paramNames.size() ? paramNames.get(i) : "arg" + i;
            var annots = i < paramAnnotations.size() ? paramAnnotations.get(i) : List.<AnnotationInfo>of();
            params.add(new ParameterInfo(paramName, type, annots));
        }
        return params;
    }

    private static List<String> extractParameterNames(MethodModel mm) {
        for (var attr : mm.attributes()) {
            if (attr instanceof MethodParametersAttribute mpa) {
                return mpa.parameters().stream()
                        .map(p -> p.name().map(Utf8Entry::stringValue).orElse(null))
                        .toList();
            }
        }
        return List.of();
    }

    private static List<List<AnnotationInfo>> extractParameterAnnotations(MethodModel mm) {
        for (var attr : mm.attributes()) {
            if (attr instanceof RuntimeVisibleParameterAnnotationsAttribute rvpaa) {
                return rvpaa.parameterAnnotations().stream()
                        .map(annots -> annots.stream().map(ClassFileScanner::buildAnnotationInfo).toList())
                        .toList();
            }
        }
        return List.of();
    }

    private static List<TypeInfo> extractExceptionTypes(MethodModel mm) {
        for (var attr : mm.attributes()) {
            if (attr instanceof ExceptionsAttribute ea) {
                return ea.exceptions().stream()
                        .map(ce -> (TypeInfo) new ClassType(DotName.fromInternal(ce.asInternalName())))
                        .toList();
            }
        }
        return List.of();
    }

    private static List<AnnotationInfo> extractAnnotations(FieldModel fm) {
        var annotations = new ArrayList<AnnotationInfo>();
        for (var attr : fm.attributes()) {
            if (attr instanceof RuntimeVisibleAnnotationsAttribute rvaa) {
                rvaa.annotations().forEach(a -> annotations.add(buildAnnotationInfo(a)));
            }
        }
        return annotations;
    }

    private static List<AnnotationInfo> extractAnnotations(MethodModel mm) {
        var annotations = new ArrayList<AnnotationInfo>();
        for (var attr : mm.attributes()) {
            if (attr instanceof RuntimeVisibleAnnotationsAttribute rvaa) {
                rvaa.annotations().forEach(a -> annotations.add(buildAnnotationInfo(a)));
            }
        }
        return annotations;
    }

    private static AnnotationInfo buildAnnotationInfo(java.lang.classfile.Annotation ann) {
        var name = DotName.fromDescriptor(ann.classSymbol().descriptorString());
        var members = new LinkedHashMap<String, io.vidocq.vauban.indexer.model.AnnotationValue>();
        for (var element : ann.elements()) {
            members.put(element.name().stringValue(), buildAnnotationValue(element.value()));
        }
        return new AnnotationInfo(name, members);
    }

    private static io.vidocq.vauban.indexer.model.AnnotationValue buildAnnotationValue(
            java.lang.classfile.AnnotationValue av) {
        return switch (av) {
            case java.lang.classfile.AnnotationValue.OfString s ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.StringVal(s.stringValue());
            case java.lang.classfile.AnnotationValue.OfBoolean b ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal(b.booleanValue());
            case java.lang.classfile.AnnotationValue.OfByte b ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.ByteVal(b.byteValue());
            case java.lang.classfile.AnnotationValue.OfChar c ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.CharVal(c.charValue());
            case java.lang.classfile.AnnotationValue.OfShort s ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.ShortVal(s.shortValue());
            case java.lang.classfile.AnnotationValue.OfInt i ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.IntVal(i.intValue());
            case java.lang.classfile.AnnotationValue.OfLong l ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.LongVal(l.longValue());
            case java.lang.classfile.AnnotationValue.OfFloat f ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.FloatVal(f.floatValue());
            case java.lang.classfile.AnnotationValue.OfDouble d ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.DoubleVal(d.doubleValue());
            case java.lang.classfile.AnnotationValue.OfClass c ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal(
                            DotName.fromDescriptor(c.classSymbol().descriptorString()));
            case java.lang.classfile.AnnotationValue.OfEnum e ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.EnumVal(
                            DotName.fromDescriptor(e.classSymbol().descriptorString()),
                            e.constantName().stringValue());
            case java.lang.classfile.AnnotationValue.OfAnnotation a ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.AnnotationVal(
                            buildAnnotationInfo(a.annotation()));
            case java.lang.classfile.AnnotationValue.OfArray arr ->
                    new io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal(
                            arr.values().stream().map(ClassFileScanner::buildAnnotationValue).toList());
        };
    }

    static TypeInfo parseTypeFromDesc(ClassDesc desc) {
        return parseTypeDescriptor(desc.descriptorString());
    }

    static TypeInfo parseTypeDescriptor(String desc) {
        if (desc.isEmpty()) throw new IllegalArgumentException("Empty descriptor");

        return switch (desc.charAt(0)) {
            case 'V' -> new VoidType();
            case 'Z' -> new PrimitiveType(PrimitiveType.Kind.BOOLEAN);
            case 'B' -> new PrimitiveType(PrimitiveType.Kind.BYTE);
            case 'C' -> new PrimitiveType(PrimitiveType.Kind.CHAR);
            case 'S' -> new PrimitiveType(PrimitiveType.Kind.SHORT);
            case 'I' -> new PrimitiveType(PrimitiveType.Kind.INT);
            case 'J' -> new PrimitiveType(PrimitiveType.Kind.LONG);
            case 'F' -> new PrimitiveType(PrimitiveType.Kind.FLOAT);
            case 'D' -> new PrimitiveType(PrimitiveType.Kind.DOUBLE);
            case 'L' -> {
                int semi = desc.indexOf(';');
                yield new ClassType(DotName.fromInternal(desc.substring(1, semi)));
            }
            case '[' -> {
                int dims = 0;
                int i = 0;
                while (i < desc.length() && desc.charAt(i) == '[') { dims++; i++; }
                yield new ArrayType(parseTypeDescriptor(desc.substring(i)), dims);
            }
            default -> throw new IllegalArgumentException("Unknown descriptor: " + desc);
        };
    }
}
