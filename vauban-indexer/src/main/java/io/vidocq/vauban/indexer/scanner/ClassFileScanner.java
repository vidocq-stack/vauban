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
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.model.TypeInfo.*;

import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

public final class ClassFileScanner {

    /** The {@code supertype_index} of a type annotation on the superclass (JVMS 4.7.20.1). */
    private static final int SUPERCLASS_INDEX = 65535;

    /** No generic supertype: {@link ClassInfo} names the supertypes of the class file raw. */
    private static final ClassTypeParameters.Supertypes RAW = new ClassTypeParameters.Supertypes(null, null);

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
        // Read first: the fields and methods resolve their type variables against the class's.
        var signature = classSignature(cm);
        var classParameters = signature.map(ClassSignature::typeParameters).orElse(List.of());
        List<TypeVariable> typeParameters = signature.map(ClassTypeParameters::of).orElse(List.of());
        var members = new Members(name, (cm.flags().flagsMask() & ClassFile.ACC_ENUM) != 0, classParameters,
                TypeAnnotationReader.of(cm, ClassFileScanner::buildAnnotationInfo),
                TypeAnnotationReader.receiver(name, typeParameters));

        for (var element : cm) {
            switch (element) {
                case FieldModel fm -> fields.add(buildFieldInfo(fm, members));
                case MethodModel mm -> methods.add(buildMethodInfo(mm, members));
                case RuntimeVisibleAnnotationsAttribute rvaa ->
                        rvaa.annotations().forEach(a -> annotations.add(buildAnnotationInfo(a)));
                case InnerClassesAttribute ica -> simpleName = innerSimpleName(cm, ica);
                default -> {
                    // other class elements carry nothing the index records
                }
            }
        }

        var kind = determineKind(cm, superName);
        // The supertypes as the class declares them, with their type arguments (BUG-20261117-22), or raw, as the
        // class file names them, when the signature does not name as many interfaces, or the class file no superclass.
        var supertypes = signature.map(ClassTypeParameters::supertypes)
                .filter(declared -> superName != null && declared.interfaces().size() == interfaces.size())
                .orElse(RAW);
        var typeAnnotations = classTypeAnnotations(cm, members, typeParameters,
                superName == null ? null
                        : Objects.requireNonNullElse(supertypes.superclass(), new ClassType(superName)),
                supertypes.interfaces() == null ? interfaces.stream().<TypeInfo>map(ClassType::new).toList()
                        : supertypes.interfaces());
        return new ClassInfo(name, superName, interfaces, accessFlags, fields, methods, annotations, kind, simpleName,
                typeParameters, true, supertypes.superclass(), supertypes.interfaces(), typeAnnotations);
    }

    /**
     * What a field or a method is read against: the type parameters of its class, the reader of its type annotations,
     * and the receiver type of the class's methods.
     */
    private record Members(DotName className, boolean enumClass, List<Signature.TypeParam> classParameters,
                           TypeAnnotationReader typeAnnotations, TypeInfo receiver) {}

    /** The parameters an enum's constructor takes before those its source declares: the name and the ordinal. */
    private static final int ENUM_CONSTRUCTOR_PREFIX = 2;

    /** The annotations on the supertypes of a class, its type parameters and their bounds (BUG-20261117-60). */
    private static List<TypeAnnotationInfo> classTypeAnnotations(ClassModel cm, Members members,
            List<TypeVariable> typeParameters, TypeInfo superclass, List<TypeInfo> interfaces) {
        return members.typeAnnotations().read(cm, annotation -> switch (annotation.targetInfo()) {
            case TypeAnnotation.SupertypeTarget supertype when supertype.supertypeIndex() == SUPERCLASS_INDEX ->
                    TypeAnnotationReader.Placed.on(TypeAnnotationInfo.Target.SUPERCLASS, 0, superclass);
            case TypeAnnotation.SupertypeTarget supertype -> TypeAnnotationReader.Placed.on(
                    TypeAnnotationInfo.Target.INTERFACE, supertype.supertypeIndex(),
                    element(interfaces, supertype.supertypeIndex()));
            default -> TypeAnnotationReader.typeParameter(annotation, typeParameters,
                    i -> ClassTypeParameters.classBound(members.classParameters().get(i)));
        });
    }

    /**
     * The class's {@code Signature} attribute, parsed once for the type parameters, the supertypes and the types of
     * the members. Empty when the class has none, or when it does not parse: the JVM itself only reads it on demand,
     * through reflection, so a malformed one must not fail the scan; the class then declares no type parameter, and
     * the index names its supertypes raw.
     */
    private static Optional<ClassSignature> classSignature(ClassModel cm) {
        try {
            return cm.findAttribute(Attributes.signature()).map(SignatureAttribute::asClassSignature);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
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
     * The type parameters, generic parameter types and return type a method's {@code Signature} declares; empty
     * when it has none or it does not parse. A constructor of an inner class or an enum has synthetic leading
     * parameters its signature leaves out: the generic types line up with the last parameters.
     */
    private static Optional<ClassTypeParameters.MethodTypes> methodTypes(MethodModel mm,
            List<Signature.TypeParam> classParameters) {
        var signature = mm.findAttribute(Attributes.signature());
        if (signature.isEmpty()) {
            return Optional.empty();
        }
        try {
            var types = ClassTypeParameters.methodTypes(classParameters, signature.get().asMethodSignature());
            return types.parameters().size() <= mm.methodTypeSymbol().parameterCount()
                    ? Optional.of(types) : Optional.empty();
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static FieldInfo buildFieldInfo(FieldModel fm, Members members) {
        var name = fm.fieldName().stringValue();
        var type = fieldType(fm, members.classParameters());
        var accessFlags = fm.flags().flagsMask();
        var annotations = extractAnnotations(fm);
        var typeAnnotations = members.typeAnnotations().read(fm,
                annotation -> annotation.targetInfo().targetType() == TypeAnnotation.TargetType.FIELD
                        ? TypeAnnotationReader.Placed.on(TypeAnnotationInfo.Target.FIELD, 0, type) : null);
        return new FieldInfo(name, type, accessFlags, annotations, typeAnnotations);
    }

    private static MethodInfo buildMethodInfo(MethodModel mm, Members members) {
        var name = mm.methodName().stringValue();
        var methodType = mm.methodTypeSymbol();
        var generic = methodTypes(mm, members.classParameters());
        var returnType = generic.map(ClassTypeParameters.MethodTypes::result)
                .orElseGet(() -> parseTypeFromDesc(methodType.returnType()));
        int implicit = generic.map(types -> methodType.parameterCount() - types.parameters().size())
                .orElseGet(() -> leadingImplicitParameters(mm, members));
        // An enum's constructor takes the name and the ordinal of its constant first, which its source does not
        // declare: the index records the parameters the source declares, as the processor does (BUG-20261117-58).
        int dropped = members.enumClass() && ConstantDescs.INIT_NAME.equals(name) ? implicit : 0;
        var parameters = buildParameters(mm, methodType,
                generic.map(ClassTypeParameters.MethodTypes::parameters).orElse(null), dropped);
        // The thrown types as the method declares them, when its signature names them: a type variable among them,
        // which the Exceptions attribute erases (BUG-20261117-52).
        var exceptionTypes = generic.map(ClassTypeParameters.MethodTypes::exceptions)
                .filter(declared -> !declared.isEmpty())
                .orElseGet(() -> extractExceptionTypes(mm));
        var accessFlags = mm.flags().flagsMask();
        var annotations = extractAnnotations(mm);
        var defaultValue = extractDefaultValue(mm);
        List<TypeAnnotationInfo> typeAnnotations = List.of();
        if (TypeAnnotationReader.hasTypeAnnotations(mm)) {
            var typeParameters = generic.map(ClassTypeParameters.MethodTypes::typeParameters).orElse(List.of());
            var methodParameters = mm.findAttribute(Attributes.signature())
                    .map(signature -> signature.asMethodSignature().typeParameters()).orElse(List.of());
            var shape = new MethodShape(ConstantDescs.INIT_NAME.equals(name) ? members.receiver() : returnType,
                    parameters.stream().map(ParameterInfo::type).toList(), implicit - dropped, exceptionTypes,
                    typeParameters,
                    methodParameters, members.receiver());
            typeAnnotations = members.typeAnnotations().read(mm, annotation -> placed(annotation, shape));
        }
        return new MethodInfo(name, returnType, parameters, exceptionTypes, accessFlags, annotations, defaultValue,
                generic.map(ClassTypeParameters.MethodTypes::typeParameters).orElse(null), typeAnnotations);
    }

    /**
     * The types of a method a type annotation can be on: the type its return type annotations are on (the class for a
     * constructor), its parameter types, the offset of the first one its source declares, its {@code throws} types,
     * its type parameters as the index records them and as its signature declares them, and its receiver type.
     */
    private record MethodShape(TypeInfo returned, List<TypeInfo> parameters, int offset, List<TypeInfo> thrown,
                               List<TypeVariable> typeParameters, List<Signature.TypeParam> declared,
                               TypeInfo receiver) {}

    /** Where a type annotation of a method is; {@code null} for one the index does not keep. */
    private static TypeAnnotationReader.Placed placed(TypeAnnotation annotation, MethodShape shape) {
        return switch (annotation.targetInfo()) {
            case TypeAnnotation.FormalParameterTarget parameter -> TypeAnnotationReader.Placed.on(
                    TypeAnnotationInfo.Target.PARAMETER, parameter.formalParameterIndex() + shape.offset(),
                    element(shape.parameters(), parameter.formalParameterIndex() + shape.offset()));
            case TypeAnnotation.ThrowsTarget thrown -> TypeAnnotationReader.Placed.on(TypeAnnotationInfo.Target.THROWS,
                    thrown.throwsTargetIndex(), element(shape.thrown(), thrown.throwsTargetIndex()));
            case TypeAnnotation.TargetInfo target when target.targetType() == TypeAnnotation.TargetType.METHOD_RETURN ->
                    TypeAnnotationReader.Placed.on(TypeAnnotationInfo.Target.RETURN, 0, shape.returned());
            case TypeAnnotation.TargetInfo target
                    when target.targetType() == TypeAnnotation.TargetType.METHOD_RECEIVER ->
                    TypeAnnotationReader.Placed.on(TypeAnnotationInfo.Target.RECEIVER, 0, shape.receiver());
            default -> TypeAnnotationReader.typeParameter(annotation, shape.typeParameters(),
                    i -> i < shape.declared().size() ? ClassTypeParameters.classBound(shape.declared().get(i)) : -1);
        };
    }

    /** The element at {@code index}, or {@code null} past the end. */
    private static <T> T element(List<T> list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /**
     * How many leading parameters of a method without a {@code Signature} its source does not declare: the outer
     * instance of an inner class's constructor. A type annotation numbers the parameters the source declares. The
     * {@code MethodParameters} attribute marks them, synthetic or mandated, where javac writes it: for an inner
     * class's constructor since Java 21. Without it, a constructor of a class the {@code InnerClasses} attribute names
     * an inner member class takes its outer instance first, as javac has always compiled it, and an enum's constructor
     * the name and the ordinal of its constant (BUG-20261117-58); a local or anonymous class's constructor, whose
     * captured values javac before Java 21 does not mark either, is read as declaring them.
     */
    private static int leadingImplicitParameters(MethodModel mm, Members members) {
        var attribute = mm.findAttribute(Attributes.methodParameters());
        if (attribute.isEmpty()) {
            if (!ConstantDescs.INIT_NAME.equals(mm.methodName().stringValue())) {
                return 0;
            }
            if (members.enumClass()) {
                return Math.min(ENUM_CONSTRUCTOR_PREFIX, mm.methodTypeSymbol().parameterCount());
            }
            return members.typeAnnotations().isInnerMember(members.className()) ? 1 : 0;
        }
        var described = attribute.get().parameters();
        int count = 0;
        while (count < described.size()
                && (described.get(count).flagsMask() & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_MANDATED)) != 0) {
            count++;
        }
        return count;
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

    /**
     * The parameters of a method, from the {@code dropped}-th of its descriptor on; {@code generic}: its generic
     * parameter types, or {@code null} to take the descriptor's. A
     * {@code RuntimeVisibleParameterAnnotations} attribute that counts fewer parameters than the descriptor, as javac
     * writes it for an enum's constructor, annotates the last ones: those the source declares.
     */
    private static List<ParameterInfo> buildParameters(MethodModel mm, MethodTypeDesc methodType,
            List<TypeInfo> generic, int dropped) {
        var paramNames = extractParameterNames(mm);
        var paramAnnotations = extractParameterAnnotations(mm);
        var params = new ArrayList<ParameterInfo>();
        int count = methodType.parameterCount();
        // The signature leaves out a constructor's synthetic leading parameters: its types are the last ones.
        int synthetic = generic == null ? count : count - generic.size();
        int unannotated = Math.max(0, count - paramAnnotations.size());

        for (int i = dropped; i < count; i++) {
            var type = generic == null || i < synthetic
                    ? parseTypeFromDesc(methodType.parameterType(i))
                    : generic.get(i - synthetic);
            // A name the class file does not give is argN, N the position among the declared parameters, as the
            // language model asks: a MethodParameters attribute may give flags without names (BUG-20261118-31).
            var named = i < paramNames.size() ? paramNames.get(i) : null;
            var paramName = named != null ? named : "arg" + (i - dropped);
            var annots = i >= unannotated ? paramAnnotations.get(i - unannotated) : List.<AnnotationInfo>of();
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
