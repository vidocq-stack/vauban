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
package io.vidocq.vauban.core.bean.plan;

import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The creation plan of a bean class worked out from the index, by the rules the container applies by reflection
 * (VAU-PERF-003 step 2): the injection order ({@code BeanInjector}) and the lifecycle callbacks
 * ({@code BeanLifecycle}). Empty when a superclass is not in the index: the container then reads the class by
 * reflection, as before.
 */
public final class IndexCreationPlans {

    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final DotName INJECT = DotName.of("jakarta.inject.Inject");
    private static final int STATIC = 0x0008;
    private static final int PRIVATE = 0x0002;
    private static final int PUBLIC = 0x0001;
    private static final int PROTECTED = 0x0004;

    /** A member of a plan: a field, or a method with its erased parameter descriptor ({@code (Ljava/lang/String;I)}). */
    public record Member(String declaringClass, boolean field, String name, String parameters) {}

    private IndexCreationPlans() {
    }

    /**
     * Supertypes first; within a class, the non-static {@code @Inject} fields, then the non-static {@code @Inject}
     * methods no subclass overrides (JLS 8.4.8.1).
     */
    public static Optional<List<Member>> injectionOrder(ClassInfo leaf, VaubanIndex index) {
        var hierarchy = hierarchySuperFirst(leaf, index);
        if (hierarchy.isEmpty()) return Optional.empty();
        var members = new ArrayList<Member>();
        for (int i = 0; i < hierarchy.get().size(); i++) {
            var type = hierarchy.get().get(i);
            for (var field : type.fields()) {
                if (has(field.annotations(), INJECT) && !field.isStatic()) {
                    members.add(new Member(type.name().value(), true, field.name(), null));
                }
            }
            var subclasses = hierarchy.get().subList(i + 1, hierarchy.get().size());
            for (var method : methods(type)) {
                if (has(method.annotations(), INJECT) && !method.isStatic()
                        && !overriddenIn(type, method, subclasses)) {
                    members.add(method(type, method));
                }
            }
        }
        return Optional.of(List.copyOf(members));
    }

    /**
     * The {@code annotation} callbacks, superclass first: a method of a subclass with the same name and parameter
     * types — annotated or not — takes the place of the superclass's.
     */
    public static Optional<List<Member>> lifecycleCallbacks(ClassInfo leaf, VaubanIndex index, DotName annotation) {
        var hierarchy = hierarchySuperFirst(leaf, index);
        if (hierarchy.isEmpty()) return Optional.empty();
        var result = new ArrayList<Member>();
        for (var type : hierarchy.get()) {
            for (var method : methods(type)) {
                var member = method(type, method);
                result.removeIf(m -> m.name().equals(member.name()) && m.parameters().equals(member.parameters()));
                if (has(method.annotations(), annotation)) {
                    result.add(member);
                }
            }
        }
        return Optional.of(List.copyOf(result));
    }

    private static Optional<List<ClassInfo>> hierarchySuperFirst(ClassInfo leaf, VaubanIndex index) {
        var list = new ArrayList<ClassInfo>();
        for (var type = leaf; type != null; ) {
            list.add(type);
            var superName = type.superName();
            if (superName == null || superName.equals(OBJECT)) break;
            var next = index.getClassByName(superName);
            if (next.isEmpty()) return Optional.empty();
            type = next.get();
        }
        Collections.reverse(list);
        return Optional.of(list);
    }

    private static boolean overriddenIn(ClassInfo declaring, MethodInfo method, List<ClassInfo> subclasses) {
        var parameters = parameters(method);
        for (var subclass : subclasses) {
            for (var candidate : methods(subclass)) {
                if (candidate.name().equals(method.name()) && parameters(candidate).equals(parameters)
                        && overrides(subclass, candidate, declaring, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The modifier rules of {@code BeanInjector.overrides}: neither static nor private, same package if package-private. */
    private static boolean overrides(ClassInfo subType, MethodInfo sub, ClassInfo supType, MethodInfo sup) {
        if ((sub.accessFlags() & STATIC) != 0 || (sup.accessFlags() & STATIC) != 0) return false;
        if ((sub.accessFlags() & PRIVATE) != 0 || (sup.accessFlags() & PRIVATE) != 0) return false;
        boolean packagePrivate = (sup.accessFlags() & (PUBLIC | PROTECTED)) == 0;
        return !packagePrivate || packageOf(subType).equals(packageOf(supType));
    }

    private static String packageOf(ClassInfo type) {
        var name = type.name().value();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    private static List<MethodInfo> methods(ClassInfo type) {
        return type.methods().stream().filter(m -> !m.isConstructor() && !m.isStaticInitializer()).toList();
    }

    private static Member method(ClassInfo type, MethodInfo method) {
        return new Member(type.name().value(), false, method.name(), parameters(method));
    }

    /** The erased parameter types, as {@code Method.getParameterTypes()} gives them, in descriptor form. */
    static String parameters(MethodInfo method) {
        var sb = new StringBuilder("(");
        method.parameters().forEach(p -> sb.append(descriptor(p.type())));
        return sb.append(')').toString();
    }

    private static String descriptor(TypeInfo type) {
        return switch (type) {
            case TypeInfo.PrimitiveType(var kind) -> switch (kind) {
                case BOOLEAN -> "Z";
                case BYTE -> "B";
                case CHAR -> "C";
                case SHORT -> "S";
                case INT -> "I";
                case LONG -> "J";
                case FLOAT -> "F";
                case DOUBLE -> "D";
            };
            case TypeInfo.ArrayType(var component, var dimensions) -> "[".repeat(dimensions) + descriptor(component);
            case TypeInfo.ClassType(var name) -> "L" + name.value().replace('.', '/') + ";";
            case TypeInfo.ParameterizedType(var raw, _) -> "L" + raw.value().replace('.', '/') + ";";
            case TypeInfo.TypeVariable(_, var bounds) ->
                    bounds.isEmpty() ? "Ljava/lang/Object;" : descriptor(bounds.getFirst());
            case TypeInfo.WildcardType _, TypeInfo.VoidType _ -> "Ljava/lang/Object;";
        };
    }

    private static boolean has(List<io.vidocq.vauban.indexer.model.AnnotationInfo> annotations, DotName name) {
        return annotations.stream().anyMatch(a -> a.name().equals(name));
    }
}
