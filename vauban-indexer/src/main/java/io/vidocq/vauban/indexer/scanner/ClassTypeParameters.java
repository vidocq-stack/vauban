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
import java.lang.classfile.Signature;
import java.util.ArrayList;
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

    private ClassTypeParameters(ClassSignature signature) {
        signature.typeParameters().forEach(parameter -> declared.put(parameter.identifier(), parameter));
    }

    /** The type parameters the signature declares, in declaration order. */
    static List<TypeInfo.TypeVariable> of(ClassSignature signature) {
        var reader = new ClassTypeParameters(signature);
        return reader.declared.keySet().stream().map(reader::typeVariable).toList();
    }

    private TypeInfo.TypeVariable typeVariable(String name) {
        var parameter = declared.get(name);
        // A variable of an enclosing class or method, or one met again while its own bounds are read.
        if (parameter == null || !reading.add(name)) {
            return new TypeInfo.TypeVariable(name, List.of());
        }
        var bounds = new ArrayList<TypeInfo>();
        parameter.classBound()
                .filter(bound -> !isObject(bound))
                .ifPresent(bound -> bounds.add(type(bound)));
        parameter.interfaceBounds().forEach(bound -> bounds.add(type(bound)));
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
        if (type.typeArgs().isEmpty()) {
            return new TypeInfo.ClassType(name);
        }
        return new TypeInfo.ParameterizedType(name, type.typeArgs().stream().map(this::typeArgument).toList());
    }

    private TypeInfo typeArgument(Signature.TypeArg argument) {
        return switch (argument) {
            case Signature.TypeArg.Unbounded _ -> new TypeInfo.WildcardType(null, null);
            case Signature.TypeArg.Bounded bounded -> switch (bounded.wildcardIndicator()) {
                case NONE -> type(bounded.boundType());
                case EXTENDS -> new TypeInfo.WildcardType(type(bounded.boundType()), null);
                case SUPER -> new TypeInfo.WildcardType(null, type(bounded.boundType()));
            };
        };
    }

    private static boolean isObject(Signature bound) {
        return bound instanceof Signature.ClassTypeSig type
                && type.outerType().isEmpty()
                && type.typeArgs().isEmpty()
                && OBJECT.equals(type.className());
    }
}
