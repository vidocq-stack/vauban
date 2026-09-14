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
package io.vidocq.vauban.core.annotation;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.util.Nonbinding;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One annotation carrying every member kind, on three classes: everything written, the members that
 * have a default left out, and one binding value changed. Tests that compare what Vauban builds with
 * what the JDK builds share it, so both sides start from the same declaration.
 */
public final class AnnotationFixture {

    private AnnotationFixture() {
    }

    public enum Mood {
        CALM,
        KEEN
    }

    /** Only ever a member value, never written on a declaration. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public @interface Leaf {
        String value();
    }

    /** One class member, so a test can build it without writing every other member. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public @interface Kind {
        Class<?> value();
    }

    /** No member at all. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public @interface Marker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Full {
        boolean flag();

        byte octet();

        char letter();

        short small();

        int number();

        long big();

        float ratio();

        double weight();

        String text();

        Class<?> type();

        Class<?> primitive();

        Class<?> array();

        Mood mood();

        Leaf leaf();

        int[] numbers();

        String[] texts();

        Leaf[] leaves();

        @Nonbinding String note() default "none";

        String optional() default "default";
    }

    @Full(flag = true, octet = 1, letter = 'c', small = 2, number = 3, big = 4L, ratio = 5.5f, weight = 6.5,
            text = "t", type = Mood.class, primitive = int.class, array = String[].class, mood = Mood.KEEN,
            leaf = @Leaf("l"), numbers = {1, 2}, texts = {"x", "y"}, leaves = {@Leaf("m"), @Leaf("n")},
            note = "written", optional = "written")
    public static final class Written {
    }

    /** The same binding values as {@link Written}, with both defaulted members left out. */
    @Full(flag = true, octet = 1, letter = 'c', small = 2, number = 3, big = 4L, ratio = 5.5f, weight = 6.5,
            text = "t", type = Mood.class, primitive = int.class, array = String[].class, mood = Mood.KEEN,
            leaf = @Leaf("l"), numbers = {1, 2}, texts = {"x", "y"}, leaves = {@Leaf("m"), @Leaf("n")})
    public static final class Defaulted {
    }

    /** {@link Written} with another value for the {@code @Nonbinding} member, and nothing else. */
    @Full(flag = true, octet = 1, letter = 'c', small = 2, number = 3, big = 4L, ratio = 5.5f, weight = 6.5,
            text = "t", type = Mood.class, primitive = int.class, array = String[].class, mood = Mood.KEEN,
            leaf = @Leaf("l"), numbers = {1, 2}, texts = {"x", "y"}, leaves = {@Leaf("m"), @Leaf("n")},
            note = "other", optional = "written")
    public static final class OtherNote {
    }

    /** {@link Written} with one binding value changed. */
    @Full(flag = true, octet = 1, letter = 'c', small = 2, number = 99, big = 4L, ratio = 5.5f, weight = 6.5,
            text = "t", type = Mood.class, primitive = int.class, array = String[].class, mood = Mood.KEEN,
            leaf = @Leaf("l"), numbers = {1, 2}, texts = {"x", "y"}, leaves = {@Leaf("m"), @Leaf("n")},
            note = "written", optional = "written")
    public static final class Other {
    }

    /** What the bytecode scan records for {@code @Full} on {@code carrier} — read without reflection. */
    public static AnnotationInfo scanned(Class<?> carrier) throws IOException {
        try (var in = carrier.getClassLoader().getResourceAsStream(carrier.getName().replace('.', '/') + ".class")) {
            assertNotNull(in, "class bytes of " + carrier.getName());
            return ClassFileScanner.scan(in.readAllBytes()).annotations().stream()
                    .filter(annotation -> annotation.name().value().equals(Full.class.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no @Full on " + carrier.getName()));
        }
    }

    /** What the JDK builds for {@code @Full} on {@code carrier}. */
    public static Full jdk(Class<?> carrier) {
        return carrier.getAnnotation(Full.class);
    }

    public static ClassLoader loader() {
        return AnnotationFixture.class.getClassLoader();
    }
}
