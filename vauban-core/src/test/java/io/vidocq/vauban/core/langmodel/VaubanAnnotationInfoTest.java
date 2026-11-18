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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("An annotation of the language model shows the defaults of its type (BUG-20261117-55)")
class VaubanAnnotationInfoTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Ranked {
        String value();

        int rank() default 3;
    }

    @Ranked("x")
    static class Annotated {
    }

    /** The annotation {@link Annotated} carries, as the index holds it: the member it writes alone. */
    private static VaubanAnnotationInfo ranked(IndexLookup lookup) throws IOException {
        ClassInfo annotated = LangModelTest.scanClass(Annotated.class);
        var annotation = annotated.annotation(DotName.of(Ranked.class.getName())).orElseThrow();
        return new VaubanAnnotationInfo(annotation, lookup);
    }

    @Test
    @DisplayName("a member the annotation does not write shows the default its type declares")
    void aMemberTheAnnotationDoesNotWriteShowsTheDefault() throws IOException {
        var annotation = ranked(LangModelTest.buildLookup(Ranked.class, Annotated.class));

        var rank = annotation.member("rank");
        assertAll(() -> assertTrue(annotation.hasMember("rank"), "hasMember(rank)"),
                () -> assertEquals(3, rank.asInt(), "member(rank)"),
                () -> assertEquals("x", annotation.value().asString(), "the member it writes"),
                () -> assertEquals(new TreeSet<>(List.of("rank", "value")),
                        new TreeSet<>(annotation.members().keySet()), "members()"));
    }

    @Test
    @DisplayName("an annotation whose type nothing describes shows the members it writes alone")
    void anAnnotationWhoseTypeNothingDescribesShowsTheMembersItWrites() throws IOException {
        var annotation = ranked(LangModelTest.buildLookup(Annotated.class));

        assertAll(() -> assertFalse(annotation.hasMember("rank"), "hasMember(rank)"),
                () -> assertNull(annotation.member("rank"), "member(rank)"),
                () -> assertEquals(Map.of("value", new AnnotationValue.StringVal("x")),
                        annotation.indexAnnotation().members(), "the index annotation is left as written"),
                () -> assertEquals(1, annotation.members().size(), "members()"));
    }

    @Test
    @DisplayName("an annotation without a lookup shows the members it writes alone")
    void anAnnotationWithoutALookupShowsTheMembersItWrites() throws IOException {
        var annotation = ranked(null);

        assertAll(() -> assertEquals("x", annotation.value().asString(), "the member it writes"),
                () -> assertFalse(annotation.hasMember("rank"), "hasMember(rank)"));
    }
}
