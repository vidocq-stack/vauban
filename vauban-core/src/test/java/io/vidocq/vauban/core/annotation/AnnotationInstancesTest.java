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

import io.vidocq.vauban.core.annotation.AnnotationFixture.Full;
import io.vidocq.vauban.core.annotation.AnnotationFixture.Leaf;
import io.vidocq.vauban.core.annotation.AnnotationFixture.Mood;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.IncompleteAnnotationException;
import java.util.Map;

import static io.vidocq.vauban.core.annotation.AnnotationFixture.Defaulted;
import static io.vidocq.vauban.core.annotation.AnnotationFixture.Other;
import static io.vidocq.vauban.core.annotation.AnnotationFixture.OtherNote;
import static io.vidocq.vauban.core.annotation.AnnotationFixture.Written;
import static io.vidocq.vauban.core.annotation.AnnotationFixture.jdk;
import static io.vidocq.vauban.core.annotation.AnnotationFixture.loader;
import static io.vidocq.vauban.core.annotation.AnnotationFixture.scanned;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An annotation instance the container builds from index data is handed to application code through
 * {@code Bean#getQualifiers()} and the other CDI API getters, so it must be indistinguishable from the
 * one the JDK builds from the same declaration: same member values with their declared types, and the
 * {@link java.lang.annotation.Annotation#equals} and {@link java.lang.annotation.Annotation#hashCode}
 * contract, {@code @Nonbinding} members included — non-binding is a CDI resolution rule, not part of
 * the annotation contract.
 *
 * <p>Each test compares with the instance the JDK builds for the same class, so neither side is
 * Vauban's own code.
 */
@DisplayName("vauban#70: a container-built annotation instance honours java.lang.annotation.Annotation")
class AnnotationInstancesTest {

    private static Full built(Class<?> carrier) throws IOException {
        return AnnotationInstances.create(Full.class, scanned(carrier), loader());
    }

    @Test
    @DisplayName("equal to the JDK instance, both ways, with the same hash code")
    void equalsTheJdkInstance() throws IOException {
        var built = built(Written.class);

        assertEquals(jdk(Written.class), built, "the JDK instance must accept ours");
        assertEquals(built, jdk(Written.class), "ours must accept the JDK instance");
        assertEquals(jdk(Written.class).hashCode(), built.hashCode());
    }

    @Test
    @DisplayName("every member keeps its declared type")
    void memberValuesKeepTheirDeclaredType() throws IOException {
        var built = built(Written.class);

        assertTrue(built.flag());
        assertEquals((byte) 1, built.octet());
        assertEquals('c', built.letter());
        assertEquals((short) 2, built.small());
        assertEquals(3, built.number());
        assertEquals(4L, built.big());
        assertEquals(5.5f, built.ratio());
        assertEquals(6.5, built.weight());
        assertEquals("t", built.text());
        assertEquals(Mood.class, built.type());
        assertEquals(int.class, built.primitive(), "a primitive class literal");
        assertEquals(String[].class, built.array(), "an array class literal");
        assertEquals(Mood.KEEN, built.mood());
        assertEquals(jdk(Written.class).leaf(), built.leaf(), "a nested annotation");
        assertArrayEquals(new int[] {1, 2}, built.numbers());
        assertArrayEquals(new String[] {"x", "y"}, built.texts());
        assertArrayEquals(jdk(Written.class).leaves(), built.leaves());
        assertEquals("written", built.note());
        assertEquals("written", built.optional());
    }

    @Test
    @DisplayName("a member the annotation does not write takes its default")
    void defaultsFillMembersThatWereNotWritten() throws IOException {
        var built = built(Defaulted.class);

        assertEquals("none", built.note());
        assertEquals("default", built.optional());
        assertEquals(jdk(Defaulted.class), built);
        assertEquals(built, jdk(Defaulted.class));
        assertEquals(jdk(Defaulted.class).hashCode(), built.hashCode());
    }

    @Test
    @DisplayName("a @Nonbinding member takes part in equals and hashCode")
    void nonbindingMembersTakePartInTheContract() throws IOException {
        // OtherNote differs from Written by its @Nonbinding member and by nothing else.
        assertNotEquals(jdk(Written.class), jdk(OtherNote.class), "guard: the JDK compares that member too");

        assertNotEquals(built(Written.class), jdk(OtherNote.class));
        assertNotEquals(jdk(OtherNote.class), built(Written.class));
        assertNotEquals(built(Written.class).hashCode(), built(OtherNote.class).hashCode());
    }

    @Test
    @DisplayName("a differing binding member makes the instances unequal, both ways")
    void differentMemberValuesAreNotEqual() throws IOException {
        assertNotEquals(built(Written.class), jdk(Other.class));
        assertNotEquals(jdk(Other.class), built(Written.class));
        assertFalse(built(Written.class).equals(built(Other.class)));
    }

    @Test
    @DisplayName("an array member hands out a copy, as the JDK does")
    void arrayMembersAreCopies() throws IOException {
        var built = built(Written.class);

        built.numbers()[0] = 99;
        built.texts()[0] = "mutated";

        assertArrayEquals(new int[] {1, 2}, built.numbers());
        assertArrayEquals(new String[] {"x", "y"}, built.texts());
    }

    @Test
    @DisplayName("annotationType() and toString() name the annotation type")
    void annotationTypeAndToString() throws IOException {
        var built = built(Written.class);

        assertEquals(Full.class, built.annotationType());
        assertTrue(built.toString().contains(Full.class.getSimpleName()), built.toString());
    }

    @Test
    @DisplayName("the members it was built from are readable back, without reading the instance")
    void membersAreReadableBack() throws IOException {
        assertEquals(scanned(Written.class), AnnotationInstances.infoOf(built(Written.class)).orElseThrow());
        assertTrue(AnnotationInstances.infoOf(jdk(Written.class)).isEmpty(), "a JDK instance carries no index data");
    }

    @Test
    @DisplayName("a member with neither a value nor a default fails on access, as the JDK does")
    void memberWithoutValueOrDefault() {
        var empty = AnnotationInstances.create(Full.class,
                new AnnotationInfo(DotName.of(Full.class.getName()), Map.of()), loader());

        assertEquals("none", empty.note(), "a member with a default still answers");
        assertThrows(IncompleteAnnotationException.class, empty::text);
    }

    @Test
    @DisplayName("a nested annotation instance honours the contract too")
    void nestedAnnotationInstance() throws IOException {
        var built = built(Written.class);

        assertEquals(jdk(Written.class).leaf(), built.leaf());
        assertEquals(built.leaf(), jdk(Written.class).leaf());
        assertEquals(jdk(Written.class).leaf().hashCode(), built.leaf().hashCode());
        assertEquals(Leaf.class, built.leaf().annotationType());
    }
}
