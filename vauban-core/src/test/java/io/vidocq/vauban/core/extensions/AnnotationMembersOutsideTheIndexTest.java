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

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.util.Nonbinding;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261118-02: the members of an annotation type the index does not hold, which {@code MetaAnnotations} gives an
 * extension in {@code @Discovery}, answer as those of an indexed annotation type do: the type a member returns, its
 * receiver type (the annotation type: a member is an instance method), its declaring class and its annotations. The
 * reflective fallback gave no return type, no receiver type, no declaring class and no annotation.
 */
@DisplayName("BUG-20261118-02 - the members of an annotation type outside the index")
class AnnotationMembersOutsideTheIndexTest {

    public enum Level {
        LOW, HIGH
    }

    /** An annotation type no bean class names: the index does not hold it unless a test adds it. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Graded {
        String value() default "";

        @Nonbinding
        int weight() default 0;

        Class<?> type() default Object.class;

        Level[] levels() default {};
    }

    /** Records, in {@code @Discovery}, each member of {@link Graded} as the qualifier's {@code ClassInfo} gives it. */
    public static class QualifierExtension implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void qualifiers(MetaAnnotations meta) {
            for (var member : meta.addQualifier(Graded.class).info().methods()) {
                SEEN.add(described(member));
            }
        }
    }

    /** Records the same as {@link QualifierExtension}, from the methods of its {@code ClassConfig}. */
    public static class ConfigExtension implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void qualifiers(MetaAnnotations meta) {
            for (var member : meta.addQualifier(Graded.class).methods()) {
                SEEN.add(described(member.info()));
            }
        }
    }

    private static String described(MethodInfo member) {
        var receiver = member.receiverType();
        return member.name() + ": returns " + named(member.returnType())
                + ", receiver " + (receiver == null ? "null" : named(receiver))
                + ", declared by " + (member.declaringClass() == null ? "null" : member.declaringClass().simpleName())
                + ", annotations " + member.annotations().stream().map(a -> a.declaration().simpleName()).toList()
                + ", abstract " + member.isAbstract() + ", parameters " + member.parameters().size();
    }

    private static String named(Type type) {
        if (type == null) {
            return "null";
        }
        return switch (type.kind()) {
            case CLASS -> type.asClass().declaration().simpleName();
            case PARAMETERIZED_TYPE -> type.asParameterizedType().genericClass().declaration().simpleName()
                    + type.asParameterizedType().typeArguments().stream().map(t -> t.kind().name()).toList();
            case ARRAY -> named(type.asArray().componentType()) + "[]";
            default -> type.kind().name() + (type.isPrimitive() ? " " + type.asPrimitive().name() : "");
        };
    }

    private static final List<String> EXPECTED = List.of(
            "levels: returns Level[], receiver Graded, declared by Graded, annotations [], abstract true, parameters 0",
            "type: returns Class[WILDCARD_TYPE], receiver Graded, declared by Graded, annotations [], abstract true,"
                    + " parameters 0",
            "value: returns String, receiver Graded, declared by Graded, annotations [], abstract true, parameters 0",
            "weight: returns PRIMITIVE int, receiver Graded, declared by Graded, annotations [Nonbinding],"
                    + " abstract true, parameters 0");

    /** What {@code extension} records of the members of {@link Graded}, sorted; {@code indexed} adds it first. */
    private static List<String> seen(Class<?> extension, List<String> seen, boolean indexed) {
        seen.clear();
        var builder = VaubanContainer.builder().addBeanClass(extension);
        if (indexed) {
            builder.addBeanClass(Graded.class);
        }
        try (var _ = builder.build()) {
            return seen.stream().sorted().toList();
        }
    }

    /**
     * The same members, reached through {@code ClassConfig.info()}: it is {@code null} for an annotation type outside
     * the index, and the extension records nothing (BUG-20261118-03).
     */
    @Test
    @Disabled("BUG-20261118-03: ClassConfig.info() is null for an annotation type outside the index")
    @DisplayName("ClassInfo.methods() of an annotation type outside the index answers as for an indexed one")
    void theMembersOfAnAnnotationOutsideTheIndex() {
        assertEquals(EXPECTED, seen(QualifierExtension.class, QualifierExtension.SEEN, true), "indexed");
        assertEquals(EXPECTED, seen(QualifierExtension.class, QualifierExtension.SEEN, false), "outside the index");
    }

    @Test
    @DisplayName("ClassConfig.methods() of an annotation type outside the index answers as for an indexed one")
    void theMethodConfigsOfAnAnnotationOutsideTheIndex() {
        assertEquals(EXPECTED, seen(ConfigExtension.class, ConfigExtension.SEEN, true), "indexed");
        assertEquals(EXPECTED, seen(ConfigExtension.class, ConfigExtension.SEEN, false), "outside the index");
    }
}
