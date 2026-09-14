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

import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnnotationTypes} answers what an annotation type declares, and reduces an annotation to the
 * {@link AnnotationKey} CDI resolution compares. The rules pinned here are the ones the reflective
 * matching applied inconsistently: defaults count as written values, {@code @Nonbinding} members and
 * members an extension made non-binding do not take part, and a nested annotation compares like
 * {@link java.lang.annotation.Annotation#equals}, with every member and its defaults.
 */
@DisplayName("vauban#70: AnnotationTypes resolves annotation type metadata and builds matching keys")
class AnnotationTypesTest {

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Inner {
        String value() default "inner";

        @Nonbinding String note() default "";
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Rich {
        String value() default "standard";

        @Nonbinding String comment() default "";

        String channel() default "web";

        Inner inner() default @Inner;

        int[] codes() default {};
    }

    private static final DotName RICH = DotName.of(Rich.class.getName());
    private static final DotName INNER = DotName.of(Inner.class.getName());
    private static final ClassLoader LOADER = AnnotationTypesTest.class.getClassLoader();

    /** Delegates class loading, but hides every resource, so no class bytes can be read through it. */
    private static final ClassLoader NO_CLASS_BYTES = new ClassLoader(LOADER) {
        @Override
        public URL getResource(String name) {
            return null;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return null;
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            return Collections.emptyEnumeration();
        }
    };

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (var in = LOADER.getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            assertNotNull(in, "class bytes of " + type.getName());
            return in.readAllBytes();
        }
    }

    private static AnnotationTypes fromIndex() throws IOException {
        var index = new IndexBuilder()
                .add(ClassFileScanner.scan(bytesOf(Rich.class)))
                .add(ClassFileScanner.scan(bytesOf(Inner.class)))
                .build();
        return new AnnotationTypes(index, List.of(NO_CLASS_BYTES), Map.of());
    }

    private static AnnotationTypes fromLoader(ClassLoader loader) {
        return new AnnotationTypes(null, List.of(loader), Map.of());
    }

    private static AnnotationValue.StringVal string(String value) {
        return new AnnotationValue.StringVal(value);
    }

    private static AnnotationValue.AnnotationVal inner(Map<String, AnnotationValue> members) {
        return new AnnotationValue.AnnotationVal(new AnnotationInfo(INNER, members));
    }

    @Nested
    @DisplayName("type metadata")
    class TypeMetadata {

        @Test
        @DisplayName("comes from the index when the index has the type")
        void fromTheIndex() throws IOException {
            var rich = fromIndex().type(RICH).orElseThrow();
            assertEquals(string("standard"), rich.member("value").orElseThrow().defaultValue());
            assertTrue(rich.member("comment").orElseThrow().nonbinding(), "comment is @Nonbinding");
            assertFalse(rich.member("channel").orElseThrow().nonbinding(), "channel is binding");
            assertEquals(inner(Map.of()), rich.member("inner").orElseThrow().defaultValue());
        }

        @Test
        @DisplayName("comes from the class bytes when the index does not have the type")
        void fromTheClassBytes() throws IOException {
            assertEquals(fromIndex().type(RICH), fromLoader(LOADER).type(RICH));
        }

        @Test
        @DisplayName("is empty for a type nobody can find")
        void unknownType() {
            assertTrue(fromLoader(LOADER).type(DotName.of("no.such.Annotation")).isEmpty());
        }
    }

    @Nested
    @DisplayName("keys")
    class Keys {

        @Test
        @DisplayName("a defaulted member equals its default written out")
        void defaultsCountAsWritten() {
            var types = fromLoader(LOADER);
            assertEquals(types.key(RICH, Map.of()),
                    types.key(RICH, Map.of("value", string("standard"), "channel", string("web"))));
        }

        @Test
        @DisplayName("a binding member with another value gives another key")
        void bindingMember() {
            var types = fromLoader(LOADER);
            assertNotEquals(types.key(RICH, Map.of("channel", string("web"))),
                    types.key(RICH, Map.of("channel", string("mobile"))));
        }

        @Test
        @DisplayName("a @Nonbinding member does not take part")
        void nonbindingMember() {
            var types = fromLoader(LOADER);
            assertEquals(types.key(RICH, Map.of("comment", string("a"))), types.key(RICH, Map.of("comment", string("b"))));
        }

        @Test
        @DisplayName("a member made non-binding by an extension does not take part")
        void extensionNonbindingMember() {
            var types = new AnnotationTypes(null, List.of(LOADER), Map.of(RICH.value(), Set.of("channel")));
            assertEquals(types.key(RICH, Map.of("channel", string("web"))), types.key(RICH, Map.of("channel", string("mobile"))));
        }

        @Test
        @DisplayName("a nested annotation compares with its own defaults applied")
        void nestedDefaults() {
            var types = fromLoader(LOADER);
            assertEquals(types.key(RICH, Map.of("inner", inner(Map.of()))),
                    types.key(RICH, Map.of("inner", inner(Map.of("value", string("inner"), "note", string(""))))));
        }

        @Test
        @DisplayName("a nested annotation compares every member, @Nonbinding included, as Annotation#equals does")
        void nestedNonbindingStillCounts() {
            var types = fromLoader(LOADER);
            assertNotEquals(types.key(RICH, Map.of("inner", inner(Map.of("note", string("x"))))),
                    types.key(RICH, Map.of("inner", inner(Map.of("note", string("y"))))));
        }

        @Test
        @DisplayName("a type nobody can find keeps its written members")
        void unknownTypeKeepsWrittenMembers() {
            var types = fromLoader(LOADER);
            var unknown = DotName.of("no.such.Annotation");
            assertEquals(types.key(unknown, Map.of("a", string("1"))), types.key(unknown, Map.of("a", string("1"))));
            assertNotEquals(types.key(unknown, Map.of("a", string("1"))), types.key(unknown, Map.of("a", string("2"))));
        }

        @Test
        @DisplayName("the index, the class bytes and reflection give the same key")
        void everySourceAgrees() throws IOException {
            var written = Map.<String, AnnotationValue>of("channel", string("mobile"), "inner", inner(Map.of("note", string("x"))));
            var fromIndex = fromIndex().key(RICH, written);
            assertEquals(fromIndex, fromLoader(LOADER).key(RICH, written), "class bytes");
            assertEquals(fromIndex, fromLoader(NO_CLASS_BYTES).key(RICH, written), "reflection");
        }
    }
}
