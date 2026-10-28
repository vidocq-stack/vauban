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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The {@link TypeView} of a loaded {@link Class}: what the run-time fallback and the Maven plugin judge. */
public record ClassTypeView(Class<?> type) implements TypeView {

    public static ClassTypeView of(Class<?> type) {
        return new ClassTypeView(type);
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
        return superclass == null || superclass == Object.class ? null : new ClassTypeView(superclass);
    }

    @Override
    public List<TypeView.Member> methods() {
        return Arrays.stream(type.getDeclaredMethods()).<TypeView.Member>map(MethodMember::new).toList();
    }

    @Override
    public List<TypeView.Member> constructors() {
        return Arrays.stream(type.getDeclaredConstructors()).<TypeView.Member>map(ConstructorMember::new).toList();
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

    /** Name plus erased parameter types: a bridge carries the erased types of the signature it stands for. */
    private static String key(Executable executable) {
        return executable.getName() + Arrays.toString(executable.getParameterTypes());
    }

    private record MethodMember(Method method) implements TypeView.Member {
        @Override public String name() { return method.getName(); }
        @Override public Set<Flag> flags() { return ClassTypeView.flags(method.getModifiers()); }
        @Override public int parameterCount() { return method.getParameterCount(); }
        @Override public boolean isBridge() { return method.isBridge(); }
        @Override public boolean isProxyLinkConstructor() { return false; }
        @Override public String overrideKey() { return key(method); }
    }

    private record ConstructorMember(Constructor<?> constructor) implements TypeView.Member {
        @Override public String name() { return "<init>"; }
        @Override public Set<Flag> flags() { return ClassTypeView.flags(constructor.getModifiers()); }
        @Override public int parameterCount() { return constructor.getParameterCount(); }
        @Override public boolean isBridge() { return false; }
        @Override public boolean isProxyLinkConstructor() { return ClientProxyShape.isProxyLinkConstructor(constructor); }
        @Override public String overrideKey() { return "<init>" + Arrays.toString(constructor.getParameterTypes()); }
    }
}
