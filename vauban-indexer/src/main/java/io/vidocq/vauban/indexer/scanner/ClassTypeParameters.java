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

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.lang.classfile.ClassSignature;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the type parameters a class declares, with their bounds, from the {@code Signature} attribute of its class
 * file, into the index model.
 * <p>
 * The index conventions apply: the implicit {@code java.lang.Object} bound is left out, and a type variable met
 * again while its own bounds are being read ({@code T} in {@code <T extends Comparable<T>>}) is recorded without
 * bounds, so the model stays finite.
 */
final class ClassTypeParameters {

    private static final String OBJECT = "java/lang/Object";

    private final Map<String, Signature.TypeParam> declared = new LinkedHashMap<>();
    private final Set<String> reading = new HashSet<>();

    private ClassTypeParameters(List<Signature.TypeParam> classParameters, List<Signature.TypeParam> methodParameters) {
        for (var parameter : classParameters) {
            declared.put(parameter.identifier(), parameter);
        }
        // A method's type parameter shadows the class's of the same name.
        for (var parameter : methodParameters) {
            declared.put(parameter.identifier(), parameter);
        }
    }

    /** The type parameters the signature declares, in declaration order. */
    static List<TypeInfo.TypeVariable> of(ClassSignature signature) {
        var reader = new ClassTypeParameters(signature.typeParameters(), List.of());
        var variables = new ArrayList<TypeInfo.TypeVariable>();
        for (var name : reader.declared.keySet()) {
            variables.add(reader.typeVariable(name));
        }
        return Collections.unmodifiableList(variables);
    }

    /** The superclass and the interfaces a class signature declares, with their type arguments. */
    record Supertypes(TypeInfo superclass, List<TypeInfo> interfaces) {}

    /**
     * The superclass and the interfaces the signature declares, in declaration order, their type variables resolved
     * against the class's type parameters, as {@link #of} reads them (BUG-20261117-22).
     */
    static Supertypes supertypes(ClassSignature signature) {
        var reader = new ClassTypeParameters(signature.typeParameters(), List.of());
        return new Supertypes(reader.type(signature.superclassSignature()),
                reader.types(signature.superinterfaceSignatures()));
    }

    /**
     * The type a field's {@code Signature} declares, its type variables resolved against the class's type
     * parameters (VAU-PERF-007).
     */
    static TypeInfo fieldType(List<Signature.TypeParam> classParameters, Signature signature) {
        return new ClassTypeParameters(classParameters, List.of()).type(signature);
    }

    /**
     * The generic types a method's {@code Signature} declares. {@code exceptions} is empty when the signature names
     * none: javac writes the thrown types there only when one of them is a type variable (BUG-20261117-52).
     */
    record MethodTypes(List<TypeInfo.TypeVariable> typeParameters, List<TypeInfo> parameters, TypeInfo result,
            List<TypeInfo> exceptions) {}

    /**
     * The type parameters, the parameter types and the return type a method's {@code Signature} declares, its type
     * variables resolved against the method's own type parameters, then the class's, and the types it throws, if it
     * names them. A {@code void} return type is {@link TypeInfo.VoidType}.
     */
    static MethodTypes methodTypes(List<Signature.TypeParam> classParameters, MethodSignature signature) {
        var reader = new ClassTypeParameters(classParameters, signature.typeParameters());
        var typeParameters = new ArrayList<TypeInfo.TypeVariable>();
        for (var parameter : signature.typeParameters()) {
            typeParameters.add(reader.typeVariable(parameter.identifier()));
        }
        var parameters = reader.types(signature.arguments());
        var result = signature.result() instanceof Signature.BaseTypeSig base && base.baseType() == 'V'
                ? new TypeInfo.VoidType()
                : reader.type(signature.result());
        var exceptions = reader.types(signature.throwableSignatures());
        return new MethodTypes(Collections.unmodifiableList(typeParameters), parameters, result, exceptions);
    }

    private List<TypeInfo> types(List<? extends Signature> signatures) {
        var types = new ArrayList<TypeInfo>(signatures.size());
        for (var signature : signatures) {
            types.add(type(signature));
        }
        return Collections.unmodifiableList(types);
    }

    private TypeInfo.TypeVariable typeVariable(String name) {
        var parameter = declared.get(name);
        // A variable of an enclosing class or method, or one met again while its own bounds are read.
        if (parameter == null || !reading.add(name)) {
            return new TypeInfo.TypeVariable(name, List.of());
        }
        var bounds = new ArrayList<TypeInfo>();
        var classBound = parameter.classBound();
        if (classBound.isPresent() && !isObject(classBound.get())) {
            bounds.add(type(classBound.get()));
        }
        for (var bound : parameter.interfaceBounds()) {
            bounds.add(type(bound));
        }
        reading.remove(name);
        return new TypeInfo.TypeVariable(name, bounds);
    }

    private TypeInfo type(Signature signature) {
        return switch (signature) {
            case Signature.BaseTypeSig base -> TypeInfo.PrimitiveType.fromDescriptor(base.baseType());
            case Signature.TypeVarSig variable -> typeVariable(variable.identifier());
            case Signature.ArrayTypeSig array -> arrayType(array);
            case Signature.ClassTypeSig type -> classType(type);
        };
    }

    private TypeInfo arrayType(Signature.ArrayTypeSig array) {
        int dimensions = 1;
        Signature component = array.componentSignature();
        while (component instanceof Signature.ArrayTypeSig nested) {
            dimensions++;
            component = nested.componentSignature();
        }
        return new TypeInfo.ArrayType(type(component), dimensions);
    }

    private TypeInfo classType(Signature.ClassTypeSig type) {
        // classDesc() joins an inner class to its outer class with '$': the binary name the index keys classes by.
        var name = DotName.fromDescriptor(type.classDesc().descriptorString());
        // The enclosing type, where it carries type arguments the classes above this one may name (BUG-20261117-21)
        var outer = type.outerType();
        TypeInfo owner = outer.isPresent() && classType(outer.get()) instanceof TypeInfo.ParameterizedType enclosing
                ? enclosing : null;
        // Without arguments of its own, a member type of a parameterized type keeps that owner, as reflection gives
        // Outer<String>.Plain: a parameterized type without arguments (BUG-20261118-47)
        if (type.typeArgs().isEmpty() && owner == null) {
            return new TypeInfo.ClassType(name);
        }
        var arguments = new ArrayList<TypeInfo>(type.typeArgs().size());
        for (var argument : type.typeArgs()) {
            arguments.add(typeArgument(argument));
        }
        return new TypeInfo.ParameterizedType(name, arguments, owner);
    }

    private TypeInfo typeArgument(Signature.TypeArg argument) {
        return switch (argument) {
            case Signature.TypeArg.Unbounded _ -> new TypeInfo.WildcardType(null, null);
            case Signature.TypeArg.Bounded bounded -> switch (bounded.wildcardIndicator()) {
                case NONE -> type(bounded.boundType());
                // ? extends Object is the unbounded wildcard: reflection, the index's reference, leaves Object out.
                case EXTENDS -> isObject(bounded.boundType())
                        ? new TypeInfo.WildcardType(null, null)
                        : new TypeInfo.WildcardType(type(bounded.boundType()), null);
                case SUPER -> new TypeInfo.WildcardType(null, type(bounded.boundType()));
            };
        };
    }

    /**
     * The class bound of a type parameter as the index keeps it: {@code 1} for one it records, {@code 0} for a
     * {@code java.lang.Object} it leaves out, {@code -1} for none, the first bound being an interface.
     */
    static int classBound(Signature.TypeParam parameter) {
        var bound = parameter.classBound();
        if (bound.isEmpty()) {
            return -1;
        }
        return isObject(bound.get()) ? 0 : 1;
    }

    private static boolean isObject(Signature bound) {
        return bound instanceof Signature.ClassTypeSig type
                && type.outerType().isEmpty()
                && type.typeArgs().isEmpty()
                && OBJECT.equals(type.className());
    }
}
