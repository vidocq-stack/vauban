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
package io.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The method an invoker calls, by names: the class of its bean, the class that declares it, its name, and the
 * erasures of its parameter types as {@link Class#getName()} writes them. The build describes so a method of a class
 * it compiles, which has no class file to load yet, and records the invoker with these names; the start loads the
 * classes and finds the {@link Method} (BUG-20261117-48).
 */
public record InvokerTarget(String beanClass, String declaringClass, String name, List<String> parameterTypes) {

    private static final String OBJECT = "java.lang.Object";

    public InvokerTarget {
        parameterTypes = List.copyOf(parameterTypes);
    }

    /** The target of an invoker of {@code method}, read from the language model, of a bean of {@code beanClass}. */
    static InvokerTarget of(String beanClass, MethodInfo method) {
        return new InvokerTarget(beanClass, method.declaringClass().name(), method.name(),
                method.parameters().stream().map(parameter -> erasedName(parameter.type())).toList());
    }

    /** The target of an invoker of {@code method}, of a bean of {@code beanClass}. */
    static InvokerTarget of(Class<?> beanClass, Method method) {
        return new InvokerTarget(beanClass.getName(), method.getDeclaringClass().getName(), method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList());
    }

    /** Whether the method is one {@code Object} declares. */
    boolean isObjectMethod() {
        return OBJECT.equals(declaringClass);
    }

    /**
     * The method, its classes loaded through {@code loader}.
     *
     * @throws IllegalArgumentException when a class cannot be loaded, or the class declares no such method
     */
    Method resolve(ClassLoader loader) {
        var declaring = SyntheticParamCodec.load(declaringClass, loader);
        var parameters = parameterTypes.stream().map(type -> SyntheticParamCodec.load(type, loader))
                .toArray(Class<?>[]::new);
        try {
            return declaring.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("the invoked method " + declaringClass + "." + name + parameterTypes
                    + " no longer exists", e);
        }
    }

    /**
     * {@code type} erased, as {@link Class#getName()} writes it: {@code int}, {@code java.lang.String},
     * {@code [Ljava.lang.String;}, {@code [[I}; a type variable is the erasure of its first bound.
     */
    static String erasedName(Type type) {
        return switch (type) {
            case ArrayType array -> "[" + descriptor(array.componentType());
            default -> elementName(type);
        };
    }

    private static String descriptor(Type type) {
        return switch (type) {
            case ArrayType array -> "[" + descriptor(array.componentType());
            case PrimitiveType primitive -> primitiveDescriptor(primitive.primitiveKind());
            default -> "L" + elementName(type) + ";";
        };
    }

    private static String elementName(Type type) {
        return switch (type) {
            case PrimitiveType primitive -> primitive.name();
            case ClassType classType -> classType.declaration().name();
            case ParameterizedType parameterized -> parameterized.genericClass().declaration().name();
            case TypeVariable variable -> variable.bounds().isEmpty() ? OBJECT
                    : erasedName(variable.bounds().getFirst());
            default -> OBJECT;
        };
    }

    private static String primitiveDescriptor(PrimitiveType.PrimitiveKind kind) {
        return switch (kind) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case CHAR -> "C";
        };
    }

    /**
     * An invoker the build made of a method of a class it compiles: it has no {@link Method} yet, and is recorded by
     * {@link SyntheticParamCodec} as a {@link VaubanInvoker} is, for the start to resolve.
     */
    record Described(InvokerTarget target, boolean instanceLookup, Set<Integer> argumentLookups)
            implements InvokerInfo {

        Described {
            argumentLookups = Set.copyOf(argumentLookups);
        }
    }
}
