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
package io.vidocq.vauban.core.codegen;

import io.vidocq.vauban.core.proxy.ClientProxyShape;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericSignatureFormatError;
import java.lang.reflect.MalformedParameterizedTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link TypeView} of a loaded {@link Class}: what the run-time fallback and the Maven plugin judge.
 *
 * @param type the type viewed
 * @param root the type the walk started from: override keys are the signatures as a member of it, as in the
 *             processor's view, so an inherited {@code echo(T)} has the key of the {@code echo(String)} that
 *             overrides it
 */
public record ClassTypeView(Class<?> type, Class<?> root) implements TypeView {

    /** The view of {@code type}, the walk starting from it. */
    public static ClassTypeView of(Class<?> type) {
        return new ClassTypeView(type, type);
    }

    @Override
    public Kind kind() {
        if (type.isAnnotation()) return Kind.ANNOTATION;
        if (type.isInterface()) return Kind.INTERFACE;
        if (type.isEnum()) return Kind.ENUM;
        if (type.isRecord()) return Kind.RECORD;
        return Kind.CLASS;
    }

    @Override
    public Set<Flag> flags() {
        var flags = flags(type.getModifiers());
        if (type.isSealed()) {
            flags.add(Flag.SEALED);
        }
        return flags;
    }

    @Override
    public boolean isNested() {
        return type.getEnclosingClass() != null;
    }

    @Override
    public TypeView superclass() {
        Class<?> superclass = type.getSuperclass();
        return superclass == null ? null : new ClassTypeView(superclass, root);
    }

    @Override
    public boolean isObject() {
        return type == Object.class;
    }

    @Override
    public boolean isSameType(TypeView other) {
        return other instanceof ClassTypeView view && view.type == type;
    }

    @Override
    public boolean isSamePackage(TypeView other) {
        return other instanceof ClassTypeView view
                && view.type.getPackageName().equals(type.getPackageName())
                && view.type.getClassLoader() == type.getClassLoader();
    }

    /** In {@link BeanMembers#STABLE_ORDER}: what is generated from them is the same from one build to the next. */
    @Override
    public List<TypeView.Member> methods() {
        var bindings = bindings();
        var members = new ArrayList<TypeView.Member>();
        for (var m : BeanMembers.inStableOrder(type.getDeclaredMethods())) {
            members.add(new MethodMember(m, bindings));
        }
        return List.copyOf(members);
    }

    /**
     * The type variables of the classes above {@code root}, up to {@code type}, each bound to the argument the class
     * below gives it, and those of a class that encloses one through the enclosing type of a parameterized one. A
     * class extended raw stops it: the classes above it are seen erased (JLS 4.8). Empty when a generic signature
     * names a class the loader cannot find: the keys are then the erased declarations.
     */
    private Map<TypeVariable<?>, Type> bindings() {
        var bindings = new HashMap<TypeVariable<?>, Type>();
        try {
            for (Class<?> c = root; c != type && c != null; c = c.getSuperclass()) {
                var above = c.getGenericSuperclass();
                if (above instanceof Class<?> raw && raw.getTypeParameters().length > 0) {
                    break;
                }
                bindArguments(above, bindings);
            }
        } catch (TypeNotPresentException | MalformedParameterizedTypeException | GenericSignatureFormatError _) {
            return Map.of();
        }
        return bindings;
    }

    /** Binds the type variables of {@code supertype}'s class, and of each class enclosing it, to their arguments. */
    private static void bindArguments(Type supertype, Map<TypeVariable<?>, Type> bindings) {
        for (var p = supertype instanceof ParameterizedType parameterized ? parameterized : null; p != null;
                p = p.getOwnerType() instanceof ParameterizedType owner ? owner : null) {
            var variables = ((Class<?>) p.getRawType()).getTypeParameters();
            var arguments = p.getActualTypeArguments();
            for (int i = 0; i < variables.length && i < arguments.length; i++) {
                bindings.put(variables[i], arguments[i]);
            }
        }
    }

    /** {@code method} as a member of the view of its declaring class. */
    public static TypeView.Member member(Method method) {
        return new MethodMember(method, Map.of());
    }

    /** The method {@code member}, a member of a {@code ClassTypeView}, stands for. */
    public static Method method(TypeView.Member member) {
        return ((MethodMember) member).method();
    }

    @Override
    public List<TypeView.Member> constructors() {
        var members = new ArrayList<TypeView.Member>();
        for (var constructor : type.getDeclaredConstructors()) {
            members.add(new ConstructorMember(constructor));
        }
        return List.copyOf(members);
    }

    private static EnumSet<Flag> flags(int modifiers) {
        var flags = EnumSet.noneOf(Flag.class);
        if (Modifier.isPublic(modifiers)) flags.add(Flag.PUBLIC);
        if (Modifier.isProtected(modifiers)) flags.add(Flag.PROTECTED);
        if (Modifier.isPrivate(modifiers)) flags.add(Flag.PRIVATE);
        if (Modifier.isStatic(modifiers)) flags.add(Flag.STATIC);
        if (Modifier.isFinal(modifiers)) flags.add(Flag.FINAL);
        if (Modifier.isAbstract(modifiers)) flags.add(Flag.ABSTRACT);
        return flags;
    }

    /**
     * The constructor a client proxy of this type chains to ({@link ClientProxyShape#superConstructor}), or
     * {@code null} when every constructor is private.
     */
    // The constructor of a type known only at run time: Constructor<?> is its only type.
    @SuppressWarnings("java:S1452")
    public Constructor<?> superConstructor() {
        return ClientProxyShape.superConstructor(this) instanceof ConstructorMember(var constructor) ? constructor : null;
    }

    private static List<String> names(Executable executable) {
        var names = new ArrayList<String>();
        for (var parameterType : executable.getParameterTypes()) {
            names.add(typeName(parameterType));
        }
        return List.copyOf(names);
    }

    private static String typeName(Class<?> type) {
        return type.getCanonicalName() != null ? type.getCanonicalName() : type.getName();
    }

    /** Name plus the erased parameter types of the declaration. */
    private static String key(Executable executable) {
        return executable.getName() + Arrays.toString(executable.getParameterTypes());
    }

    /**
     * Name plus the erased parameter types of {@code method} as a member of the walk's root, the type variables of the
     * classes above it bound as {@code bindings} says: in {@code Child extends Base<String>}, {@code echo(T)} of
     * {@code Base<T>} has the key of {@code echo(String)}, the method that overrides it. The key of the declaration
     * when nothing is bound, or when its generic signature names a class the loader cannot find.
     */
    private static String key(Method method, Map<TypeVariable<?>, Type> bindings) {
        if (!bindings.isEmpty()) {
            try {
                var parameters = method.getGenericParameterTypes();
                var erased = new Class<?>[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    erased[i] = erasure(parameters[i], bindings);
                }
                return method.getName() + Arrays.toString(erased);
            } catch (TypeNotPresentException | MalformedParameterizedTypeException | GenericSignatureFormatError _) {
                // The erased declaration, below.
            }
        }
        return key(method);
    }

    /** The erasure of {@code type}, a type variable taken as the argument {@code bindings} gives it, or its bound. */
    private static Class<?> erasure(Type type, Map<TypeVariable<?>, Type> bindings) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> (Class<?>) p.getRawType();
            case GenericArrayType a -> erasure(a.getGenericComponentType(), bindings).arrayType();
            case TypeVariable<?> v -> erasure(bindings.getOrDefault(v, v.getBounds()[0]), bindings);
            // A wildcard is never the type of a parameter.
            default -> Object.class;
        };
    }

    /**
     * @param bindings the type variables the classes between the walk's root and the declaring class bind, which make
     *                 the override key the signature as a member of the root
     */
    private record MethodMember(Method method, Map<TypeVariable<?>, Type> bindings) implements TypeView.Member {
        @Override public String name() { return method.getName(); }
        @Override public Set<Flag> flags() { return ClassTypeView.flags(method.getModifiers()); }
        @Override public List<String> parameterTypes() { return names(method); }
        @Override public String returnType() { return typeName(method.getReturnType()); }
        @Override public boolean isBridge() { return method.isBridge() || method.isSynthetic(); }
        @Override public boolean isProxyLinkConstructor() { return false; }
        @Override public String overrideKey() { return key(method, bindings); }
    }

    private record ConstructorMember(Constructor<?> constructor) implements TypeView.Member {
        @Override public String name() { return "<init>"; }
        @Override public Set<Flag> flags() { return ClassTypeView.flags(constructor.getModifiers()); }
        @Override public List<String> parameterTypes() { return names(constructor); }
        @Override public String returnType() { return "void"; }
        @Override public boolean isBridge() { return false; }
        @Override public boolean isProxyLinkConstructor() { return ClientProxyShape.isProxyLinkConstructor(constructor); }
        @Override public String overrideKey() { return "<init>" + parameterTypes(); }
    }
}
