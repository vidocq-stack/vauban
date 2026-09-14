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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** Carries the annotation the JDK builds, the reference every other instance is compared with. */
    @Rich(value = "premium", comment = "ignored", codes = {1, 2})
    static final class Carrier {
    }

    private static Rich jdkRich() {
        return Carrier.class.getAnnotation(Rich.class);
    }

    private static final Map<String, AnnotationValue> RICH_WRITTEN = Map.of(
            "value", string("premium"), "comment", string("ignored"),
            "codes", new AnnotationValue.ArrayVal(
                    List.of(new AnnotationValue.IntVal(1), new AnnotationValue.IntVal(2))));

    /** The same annotation as {@link Carrier}'s, written by an application as a literal. */
    static final class RichLiteral implements Rich {
        @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return Rich.class; }

        @Override public String value() { return "premium"; }

        @Override public String comment() { return "ignored"; }

        @Override public String channel() { return "web"; }

        @Override public Inner inner() { return jdkRich().inner(); }

        @Override public int[] codes() { return new int[] {1, 2}; }
    }

    /** Runs {@code action} with the reflection switch set to {@code mode}, and puts it back. */
    private static <T> T withReflection(String mode, java.util.function.Supplier<T> action) {
        var previous = System.getProperty(AnnotationReflection.PROPERTY);
        System.setProperty(AnnotationReflection.PROPERTY, mode);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                System.clearProperty(AnnotationReflection.PROPERTY);
            } else {
                System.setProperty(AnnotationReflection.PROPERTY, previous);
            }
        }
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

    /**
     * Run-time matching is handed annotation instances — the literal of a programmatic lookup, the
     * annotations of an injection point — and reduces each one to the key its written members give.
     */
    @Nested
    @DisplayName("keys of an annotation instance")
    class InstanceKeys {

        @Test
        @DisplayName("an instance the JDK built gives the key of its written members")
        void jdkInstance() {
            var types = fromLoader(LOADER);
            assertEquals(types.key(RICH, RICH_WRITTEN), types.key(jdkRich()));
        }

        @Test
        @DisplayName("an instance the container built gives the same key")
        void containerBuiltInstance() {
            var types = fromLoader(LOADER);
            var built = AnnotationInstances.create(Rich.class, new AnnotationInfo(RICH, RICH_WRITTEN), LOADER);
            assertEquals(types.key(jdkRich()), types.key(built));
        }

        @Test
        @DisplayName("a literal an application wrote gives the same key")
        void applicationLiteral() {
            var types = fromLoader(LOADER);
            assertEquals(types.key(jdkRich()), types.key(new RichLiteral()));
        }

        @Test
        @DisplayName("the built-in qualifiers give their own key")
        void builtInQualifiers() {
            var types = fromLoader(LOADER);
            assertEquals(new AnnotationKey(DotName.of("jakarta.enterprise.inject.Default"), Map.of()),
                    types.key(jakarta.enterprise.inject.Default.Literal.INSTANCE));
            assertEquals(new AnnotationKey(DotName.of("jakarta.enterprise.inject.Any"), Map.of()),
                    types.key(jakarta.enterprise.inject.Any.Literal.INSTANCE));
            assertEquals(new AnnotationKey(DotName.of("jakarta.inject.Named"), Map.of("value", string("report"))),
                    types.key(jakarta.enterprise.inject.literal.NamedLiteral.of("report")));
        }

        @Test
        @DisplayName("every instance of a set converts at once")
        void keysOfSeveralInstances() {
            var types = fromLoader(LOADER);
            assertEquals(Set.of(types.key(jdkRich()),
                            new AnnotationKey(DotName.of("jakarta.enterprise.inject.Any"), Map.of())),
                    types.keys(new java.lang.annotation.Annotation[] {
                            jdkRich(), jakarta.enterprise.inject.Any.Literal.INSTANCE}));
        }
    }

    /**
     * {@code -Dvauban.annotations.reflection=forbid} makes every reflective fallback throw, which is how
     * a test — and a native-image user — sees what still needs reflection.
     */
    @Nested
    @DisplayName("the vauban.annotations.reflection switch")
    class ReflectionSwitch {

        @Test
        @DisplayName("forbid stops the read of a live annotation, naming the type and the switch")
        void forbidStopsReadingAnInstance() {
            var types = fromLoader(LOADER);
            var error = assertThrows(IllegalStateException.class,
                    () -> withReflection("forbid", () -> types.key(jdkRich())));
            assertTrue(error.getMessage().contains(Rich.class.getName()), error.getMessage());
            assertTrue(error.getMessage().contains(AnnotationReflection.PROPERTY), error.getMessage());
        }

        @Test
        @DisplayName("forbid stops the read of a declaration no index and no class file describes")
        void forbidStopsReadingADeclaration() {
            var error = assertThrows(IllegalStateException.class,
                    () -> withReflection("forbid", () -> fromLoader(NO_CLASS_BYTES).type(RICH)));
            assertTrue(error.getMessage().contains(Rich.class.getName()), error.getMessage());
        }

        @Test
        @DisplayName("forbid leaves the paths that read no annotation alone")
        void forbidLeavesTheOtherPathsAlone() throws IOException {
            var types = fromIndex();
            var built = AnnotationInstances.create(Rich.class, new AnnotationInfo(RICH, RICH_WRITTEN), LOADER);
            var expected = types.key(RICH, RICH_WRITTEN);

            withReflection("forbid", () -> {
                assertEquals(expected, types.key(RICH, RICH_WRITTEN), "the index describes the type");
                assertEquals(expected, types.key(built), "a container-built instance carries its members");
                assertEquals(new AnnotationKey(DotName.of("jakarta.enterprise.inject.Default"), Map.of()),
                        types.key(jakarta.enterprise.inject.Default.Literal.INSTANCE), "a built-in qualifier");
                return null;
            });
        }

        @Test
        @DisplayName("warn reads on")
        void warnReadsOn() {
            var types = fromLoader(LOADER);
            assertEquals(types.key(RICH, RICH_WRITTEN), withReflection("warn", () -> types.key(jdkRich())));
        }

        @Test
        @DisplayName("a value that is neither allow, warn nor forbid is refused")
        void unknownMode() {
            var types = fromLoader(LOADER);
            assertThrows(IllegalStateException.class, () -> withReflection("forbidden", () -> types.key(jdkRich())));
        }
    }
}
