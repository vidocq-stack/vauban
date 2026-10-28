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
package io.vidocq.vauban.processor.codegen;

import io.vidocq.vauban.core.codegen.TypeView;
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedShapeFromElements;
import io.vidocq.vauban.processor.codegen.proxy.ClientProxyShapeFromElements;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The {@link TypeView} of a {@code TypeElement}: what the annotation processor judges, through the same rules as the
 * run-time and Maven plugin paths (vauban#227).
 *
 * @param type  the type viewed
 * @param root  the type the walk started from: override keys are the signatures as a member of it, so an inherited
 *              {@code echo(T)} has the key of the {@code echo(String)} that overrides it
 * @param types the processing environment's type utilities
 */
public record ElementTypeView(TypeElement type, TypeElement root, Types types) implements TypeView {

    /** The view of {@code type}, the walk starting from it. */
    public static ElementTypeView of(TypeElement type, Types types) {
        return new ElementTypeView(type, type, types);
    }

    @Override
    public Kind kind() {
        return switch (type.getKind()) {
            case INTERFACE -> Kind.INTERFACE;
            case ENUM -> Kind.ENUM;
            case RECORD -> Kind.RECORD;
            case ANNOTATION_TYPE -> Kind.ANNOTATION;
            default -> Kind.CLASS;
        };
    }

    @Override
    public Set<Flag> flags() {
        return flags(type.getModifiers());
    }

    @Override
    public boolean isNested() {
        return type.getNestingKind() != NestingKind.TOP_LEVEL;
    }

    @Override
    public TypeView superclass() {
        if (type.getSuperclass() instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement superclass
                && !superclass.getQualifiedName().contentEquals("java.lang.Object")) {
            return new ElementTypeView(superclass, root, types);
        }
        return null;
    }

    @Override
    public List<TypeView.Member> methods() {
        return ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .<TypeView.Member>map(m -> new MethodMember(m, InterceptedShapeFromElements.memberSignatureKey(root, m, types), types))
                .toList();
    }

    @Override
    public List<TypeView.Member> constructors() {
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .<TypeView.Member>map(c -> new ConstructorMember(c, types)).toList();
    }

    /**
     * The constructor a client proxy of this type chains to ({@link ClientProxyShape#superConstructor}), or
     * {@code null} when every constructor is private.
     */
    public ExecutableElement superConstructor() {
        return ClientProxyShape.superConstructor(this) instanceof ConstructorMember member ? member.constructor() : null;
    }

    /** Erased canonical names: an erased type variable is its bound, a nested type is dotted, an array ends in []. */
    private static List<String> names(ExecutableElement executable, Types types) {
        return executable.getParameters().stream().map(p -> types.erasure(p.asType()).toString()).toList();
    }

    private static Set<Flag> flags(Set<Modifier> modifiers) {
        var flags = EnumSet.noneOf(Flag.class);
        for (var modifier : modifiers) {
            switch (modifier) {
                case PUBLIC -> flags.add(Flag.PUBLIC);
                case PROTECTED -> flags.add(Flag.PROTECTED);
                case PRIVATE -> flags.add(Flag.PRIVATE);
                case STATIC -> flags.add(Flag.STATIC);
                case FINAL -> flags.add(Flag.FINAL);
                case ABSTRACT -> flags.add(Flag.ABSTRACT);
                case SEALED -> flags.add(Flag.SEALED);
                default -> {
                    // Not read by the rules.
                }
            }
        }
        return flags;
    }

    /** A method; the language model has no bridge. */
    private record MethodMember(ExecutableElement method, String overrideKey, Types types) implements TypeView.Member {
        @Override public String name() { return method.getSimpleName().toString(); }
        @Override public Set<Flag> flags() { return ElementTypeView.flags(method.getModifiers()); }
        @Override public List<String> parameterTypes() { return names(method, types); }
        @Override public boolean isBridge() { return false; }
        @Override public boolean isProxyLinkConstructor() { return false; }
    }

    private record ConstructorMember(ExecutableElement constructor, Types types) implements TypeView.Member {
        @Override public String name() { return "<init>"; }
        @Override public Set<Flag> flags() { return ElementTypeView.flags(constructor.getModifiers()); }
        @Override public List<String> parameterTypes() { return names(constructor, types); }
        @Override public boolean isBridge() { return false; }
        @Override public boolean isProxyLinkConstructor() {
            return ClientProxyShapeFromElements.isProxyLinkConstructor(constructor);
        }
        @Override public String overrideKey() { return "<init>" + parameterTypes(); }
    }
}
