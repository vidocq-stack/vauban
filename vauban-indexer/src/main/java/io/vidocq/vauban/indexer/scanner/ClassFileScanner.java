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
import java.util.function.IntPredicate;

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
        DotName enclosingClass = null;
        // Read first: the fields and methods resolve their type variables against the class's.
        var signature = classSignature(cm);
        var classParameters = signature.map(ClassSignature::typeParameters).orElse(List.of());
        List<TypeVariable> typeParameters = signature.map(ClassTypeParameters::of).orElse(List.of());
        var members = new Members(name, (cm.flags().flagsMask() & ClassFile.ACC_ENUM) != 0,
                cm.findAttribute(Attributes.enclosingMethod())
                        .map(enclosing -> enclosing.enclosingClass().asSymbol()).orElse(null),
                cm.findAttribute(Attributes.record()).map(RecordAttribute::components).orElse(null),
                classParameters, TypeAnnotationReader.of(cm, ClassFileScanner::buildAnnotationInfo),
                TypeAnnotationReader.receiver(name, typeParameters));

        for (var element : cm) {
            switch (element) {
                case FieldModel fm -> fields.add(buildFieldInfo(fm, members));
                case MethodModel mm -> methods.add(buildMethodInfo(mm, members));
                case RuntimeVisibleAnnotationsAttribute rvaa ->
                        rvaa.annotations().forEach(a -> annotations.add(buildAnnotationInfo(a)));
                case InnerClassesAttribute ica -> {
                    simpleName = innerSimpleName(cm, ica);
                    enclosingClass = enclosingClass(cm, ica);
                }
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
                typeParameters, true, supertypes.superclass(), supertypes.interfaces(), typeAnnotations,
                enclosingClass);
    }

    /**
     * What a field or a method is read against: whether its class is an enum, the class whose method or initializer
     * declares it when it is local or anonymous ({@code null} otherwise), the components of a record ({@code null} for
     * any other class), the type parameters of its class, the reader of its type annotations, and the receiver type of
     * the class's methods.
     */
    private record Members(DotName className, boolean enumClass, ClassDesc enclosingClass,
                           List<RecordComponentInfo> recordComponents, List<Signature.TypeParam> classParameters,
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

    /**
     * The class that encloses an inner member class, from its own entry of the {@code InnerClasses} attribute: an
     * outer class named, {@code ACC_STATIC} clear (the class's own access flags do not keep it). {@code null} for a
     * top-level, a static nested, a local or an anonymous class (BUG-20261118-01).
     */
    private static DotName enclosingClass(ClassModel cm, InnerClassesAttribute attribute) {
        var self = cm.thisClass().asInternalName();
        for (var inner : attribute.classes()) {
            if (inner.innerClass().asInternalName().equals(self)) {
                return (inner.flagsMask() & ClassFile.ACC_STATIC) != 0 ? null
                        : inner.outerClass().map(outer -> DotName.fromInternal(outer.asInternalName())).orElse(null);
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
        var genericParameters = generic.map(ClassTypeParameters.MethodTypes::parameters).orElse(null);
        var layout = ParameterLayout.of(mm, members, genericParameters == null ? -1 : genericParameters.size());
        var parameters = buildParameters(mm, methodType, genericParameters, layout);
        // The thrown types as the method declares them, when its signature names them: a type variable among them,
        // which the Exceptions attribute erases (BUG-20261117-52).
        var exceptionTypes = generic.map(ClassTypeParameters.MethodTypes::exceptions)
                .filter(declared -> !declared.isEmpty())
                .orElseGet(() -> extractExceptionTypes(mm));
        var accessFlags = mm.flags().flagsMask();
        var annotations = extractAnnotations(mm);
        var defaultValue = extractDefaultValue(mm);
        List<TypeAnnotationInfo> typeAnnotations = List.of();
        var components = compactComponents(mm, members);
        if (components.isPresent() || TypeAnnotationReader.hasTypeAnnotations(mm)) {
            var typeParameters = generic.map(ClassTypeParameters.MethodTypes::typeParameters).orElse(List.of());
            var methodParameters = mm.findAttribute(Attributes.signature())
                    .map(signature -> signature.asMethodSignature().typeParameters()).orElse(List.of());
            var shape = new MethodShape(ConstantDescs.INIT_NAME.equals(name) ? members.receiver() : returnType,
                    parameters.stream().map(ParameterInfo::type).toList(), layout.mandated(), exceptionTypes,
                    typeParameters,
                    methodParameters, members.receiver());
            typeAnnotations = components.map(found -> compactTypeAnnotations(mm, members, found, shape))
                    .orElseGet(() -> members.typeAnnotations().read(mm, annotation -> placed(annotation, shape)));
        }
        return new MethodInfo(name, returnType, parameters, exceptionTypes, accessFlags, annotations, defaultValue,
                generic.map(ClassTypeParameters.MethodTypes::typeParameters).orElse(null), typeAnnotations);
    }

    /**
     * The record components of {@code mm} when it is a record's compact canonical constructor: every parameter
     * {@code MethodParameters} marks mandated, the source declaring them implicitly (JLS 8.10.4), and the descriptor
     * that of the components in order; empty for any other method, the explicit and the implicit canonical
     * constructors among them, whose type annotations javac writes right. A record without components has a compact
     * constructor without parameters: its components are an empty list.
     */
    private static Optional<List<RecordComponentInfo>> compactComponents(MethodModel mm, Members members) {
        var components = members.recordComponents();
        var type = mm.methodTypeSymbol();
        if (components == null || !ConstantDescs.INIT_NAME.equals(mm.methodName().stringValue())
                || components.size() != type.parameterCount()) {
            return Optional.empty();
        }
        for (int i = 0; i < components.size(); i++) {
            if (!components.get(i).descriptorSymbol().equals(type.parameterType(i))) {
                return Optional.empty();
            }
        }
        var parameters = mm.findAttribute(Attributes.methodParameters()).map(MethodParametersAttribute::parameters)
                .orElse(List.of());
        return Optional.of(components).filter(_ -> parameters.size() == components.size() && parameters.stream()
                .allMatch(parameter -> (parameter.flagsMask() & ClassFile.ACC_MANDATED) != 0));
    }

    /**
     * The type annotations of a compact canonical constructor: javac 25 writes those of its parameters with a path, on
     * a qualified type or on an array as copies of its record components' with a {@code FIELD} target, which names
     * no parameter (BUG-20261118-34). Each parameter takes its component's instead, path and all, and the constructor
     * keeps its own others; its own parameter entries would count the top-level ones twice.
     */
    private static List<TypeAnnotationInfo> compactTypeAnnotations(MethodModel mm, Members members,
            List<RecordComponentInfo> components, MethodShape shape) {
        var result = new ArrayList<>(members.typeAnnotations().read(mm, annotation -> switch (annotation.targetInfo()) {
            case TypeAnnotation.FormalParameterTarget _ -> null;
            case TypeAnnotation.TargetInfo target when target.targetType() == TypeAnnotation.TargetType.FIELD -> null;
            default -> placed(annotation, shape);
        }));
        for (int i = 0; i < components.size(); i++) {
            var parameter = TypeAnnotationReader.Placed.on(TypeAnnotationInfo.Target.PARAMETER, i,
                    element(shape.parameters(), i));
            result.addAll(members.typeAnnotations().read(components.get(i), annotation ->
                    annotation.targetInfo().targetType() == TypeAnnotation.TargetType.FIELD ? parameter : null));
        }
        return result;
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
     * Where the parameters a method's source declares are among those of its descriptor: after {@code skipped}
     * synthetic ones the index leaves out, the name and the ordinal of an enum's constant (BUG-20261117-58), and
     * {@code mandated} ones it keeps, the outer instance of an inner or a local class's constructor, which the language
     * model counts as implicitly declared; and before {@code trailing} synthetic ones it leaves out, the values a local
     * or anonymous class captures (BUG-20261118-32). A {@code Signature}, a type annotation and, as javac writes them,
     * the parameter annotations number the declared parameters only.
     */
    private record ParameterLayout(int skipped, int mandated, int trailing) {

        private static final ParameterLayout DECLARED = new ParameterLayout(0, 0, 0);

        /** The parameters the index records: from {@code skipped} to {@code count - trailing}. */
        int end(int count) {
            return count - trailing;
        }

        /** The descriptor position of the first parameter the source declares. */
        int firstDeclared() {
            return skipped + mandated;
        }

        /**
         * The layout of {@code mm}'s parameters, whose {@code Signature} names {@code declared} of them, or {@code -1}
         * without one. The {@code MethodParameters} attribute marks the parameters javac adds, where javac writes it:
         * for the constructors of enums, inner and local classes since Java 21. Without it, an enum's constructor
         * takes the name and the ordinal first, an inner member class's the outer instance, as javac has always
         * compiled them; a local class's takes the outer instance when its first parameter is of the class that
         * declares it, and the captured values last, those its {@code Signature} does not name. Without a
         * {@code Signature} either, its captured values cannot be told from its parameters, as reflection cannot:
         * they are read as declared.
         */
        static ParameterLayout of(MethodModel mm, Members members, int declared) {
            int count = mm.methodTypeSymbol().parameterCount();
            var attribute = mm.findAttribute(Attributes.methodParameters())
                    .filter(parameters -> parameters.parameters().size() == count);
            if (attribute.isPresent()) {
                return marked(attribute.get().parameters().stream().mapToInt(MethodParameterInfo::flagsMask)
                        .toArray(), position -> isOuterInstance(mm, members, position));
            }
            if (!ConstantDescs.INIT_NAME.equals(mm.methodName().stringValue())) {
                return DECLARED;
            }
            if (members.enumClass()) {
                return new ParameterLayout(Math.min(ENUM_CONSTRUCTOR_PREFIX, count), 0, 0);
            }
            int added = declared < 0 ? 0 : count - declared;
            if (members.typeAnnotations().isInnerMember(members.className())) {
                return new ParameterLayout(0, Math.min(1, count), Math.max(0, added - 1));
            }
            if (members.enclosingClass() != null) {
                int outer = isOuterInstance(mm, members, 0) && (declared < 0 || added > 0) ? 1 : 0;
                return new ParameterLayout(0, outer, Math.max(0, added - outer));
            }
            return new ParameterLayout(0, Math.max(0, added), 0);
        }

        /**
         * The layout {@code MethodParameters} flags give: the synthetic parameters first and last, and the outer
         * instance after the first ones, mandated. Javac marks other parameters mandated too, which the source
         * declares implicitly and a {@code Signature} names: every parameter of a record's compact constructor, the
         * name of an enum's {@code valueOf}. Those are the method's own, numbered from 0 by the {@code Signature} and
         * the type annotations, so only a mandated parameter {@code outer} tells the outer instance counts
         * (BUG-20261118-32).
         */
        private static ParameterLayout marked(int[] flags, IntPredicate outer) {
            int skipped = 0;
            while (skipped < flags.length && isOnly(flags[skipped], ClassFile.ACC_SYNTHETIC)) {
                skipped++;
            }
            int mandated = skipped < flags.length && (flags[skipped] & ClassFile.ACC_MANDATED) != 0
                    && outer.test(skipped) ? 1 : 0;
            int trailing = 0;
            while (trailing < flags.length - skipped - mandated
                    && isOnly(flags[flags.length - 1 - trailing], ClassFile.ACC_SYNTHETIC)) {
                trailing++;
            }
            return new ParameterLayout(skipped, mandated, trailing);
        }

        /**
         * Whether the parameter at {@code position} of a constructor is the outer instance: that of an inner member
         * class, or of a local or anonymous class whose descriptor names the class that declares it there. A record
         * has none, local or not: it is implicitly static (JLS 8.10), and its compact constructor's parameters, all
         * mandated, are its components, the first of which may be of that class.
         *
         * <p>Without {@code MethodParameters}, the check on the descriptor is a guess: a local class declared in a
         * static method has no outer instance, yet a first parameter of the class that declares it reads as one.
         * Javac has written {@code MethodParameters} for those constructors since Java 21, so only older class files
         * meet it.
         */
        private static boolean isOuterInstance(MethodModel mm, Members members, int position) {
            var type = mm.methodTypeSymbol();
            return ConstantDescs.INIT_NAME.equals(mm.methodName().stringValue()) && position < type.parameterCount()
                    && mm.parent().flatMap(owner -> owner.findAttribute(Attributes.record())).isEmpty()
                    && (members.typeAnnotations().isInnerMember(members.className())
                            || type.parameterType(position).equals(members.enclosingClass()));
        }

        /** Whether {@code flags} has {@code flag} among the synthetic and mandated flags, and not the other. */
        private static boolean isOnly(int flags, int flag) {
            return (flags & IMPLICIT) == flag;
        }
    }

    /** The flags of a parameter javac adds to those the source declares. */
    private static final int IMPLICIT = ClassFile.ACC_SYNTHETIC | ClassFile.ACC_MANDATED;

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
     * The parameters of a method the index records, as {@code layout} places them; {@code generic}: the generic types
     * of those its source declares, or {@code null} to take the descriptor's. A
     * {@code RuntimeVisibleParameterAnnotations} attribute that counts fewer parameters than the descriptor, as javac
     * writes it for the constructor of an enum, an inner or a local class, annotates those the source declares.
     */
    private static List<ParameterInfo> buildParameters(MethodModel mm, MethodTypeDesc methodType,
            List<TypeInfo> generic, ParameterLayout layout) {
        var paramNames = extractParameterNames(mm);
        var paramAnnotations = extractParameterAnnotations(mm);
        int count = methodType.parameterCount();
        int first = layout.firstDeclared();
        int annotated = paramAnnotations.size() == count ? 0 : first;
        var params = new ArrayList<ParameterInfo>();
        for (int i = layout.skipped(); i < layout.end(count); i++) {
            var type = generic != null && i >= first && i - first < generic.size()
                    ? generic.get(i - first)
                    : parseTypeFromDesc(methodType.parameterType(i));
            // A name the class file does not give is argN, N the position among the recorded parameters, as the
            // language model asks: a MethodParameters attribute may give flags without names (BUG-20261118-31).
            var named = i < paramNames.size() ? paramNames.get(i) : null;
            var paramName = named != null ? named : "arg" + (i - layout.skipped());
            var annots = element(paramAnnotations, i - annotated);
            params.add(new ParameterInfo(paramName, type, annots != null ? annots : List.of()));
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
