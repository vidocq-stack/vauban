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

import io.vidocq.vauban.core.annotation.AnnotationFixture;
import io.vidocq.vauban.core.annotation.AnnotationFixture.Full;
import io.vidocq.vauban.core.annotation.AnnotationFixture.Leaf;
import io.vidocq.vauban.core.annotation.AnnotationFixture.Mood;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An extension writes annotations through {@link AnnotationBuilder}, and Vauban has to compare them
 * with the annotations the scan read from a class file: a qualifier added by an {@code @Enhancement},
 * or the qualifier of a synthetic bean, matches an injection point only when both sides record the
 * same values. The reference is the bytecode scan of a class that declares the same annotation in
 * source.
 */
@DisplayName("vauban#70: a lang-model annotation converts to what the bytecode scan records")
class LangModelAnnotationsTest {

    /** The same annotation as {@link AnnotationFixture.Written}, written through the builder. */
    private static jakarta.enterprise.lang.model.AnnotationInfo builtLikeWritten() {
        return AnnotationBuilder.of(Full.class)
                .member("flag", true)
                .member("octet", (byte) 1)
                .member("letter", 'c')
                .member("small", (short) 2)
                .member("number", 3)
                .member("big", 4L)
                .member("ratio", 5.5f)
                .member("weight", 6.5)
                .member("text", "t")
                .member("type", Mood.class)
                .member("primitive", int.class)
                .member("array", String[].class)
                .member("mood", Mood.KEEN)
                .member("leaf", AnnotationBuilder.of(Leaf.class).member("value", "l").build())
                .member("numbers", new int[] {1, 2})
                .member("texts", new String[] {"x", "y"})
                .member("leaves", new jakarta.enterprise.lang.model.AnnotationInfo[] {
                        AnnotationBuilder.of(Leaf.class).member("value", "m").build(),
                        AnnotationBuilder.of(Leaf.class).member("value", "n").build()})
                .member("note", "written")
                .member("optional", "written")
                .build();
    }

    @Test
    @DisplayName("every member kind of a built annotation converts to the value the scan records")
    void builtAnnotationMatchesTheBytecodeScan() throws IOException {
        assertEquals(AnnotationFixture.scanned(AnnotationFixture.Written.class),
                LangModelAnnotations.toIndex(builtLikeWritten()));
    }

    @Test
    @DisplayName("a class member records the class, not its name")
    void classMemberIsAClassValue() {
        var built = AnnotationBuilder.of(AnnotationFixture.Kind.class).member("value", Mood.class).build();

        assertEquals(new AnnotationValue.ClassVal(DotName.of(Mood.class.getName())),
                LangModelAnnotations.toIndex(built).members().get("value"));
    }

    @Test
    @DisplayName("a primitive and an array class member keep the names the class file gives them")
    void primitiveAndArrayClassMembers() {
        assertEquals(new AnnotationValue.ClassVal(DotName.of("int")),
                LangModelAnnotations.toIndex(
                        AnnotationBuilder.of(AnnotationFixture.Kind.class).member("value", int.class).build())
                        .members().get("value"));
        assertEquals(new AnnotationValue.ClassVal(DotName.of("[Ljava.lang.String;")),
                LangModelAnnotations.toIndex(
                        AnnotationBuilder.of(AnnotationFixture.Kind.class).member("value", String[].class).build())
                        .members().get("value"));
    }

    @Test
    @DisplayName("an index-backed annotation gives back its own data")
    void indexBackedAnnotationIsUnwrapped() throws IOException {
        var scanned = AnnotationFixture.scanned(AnnotationFixture.Written.class);

        assertEquals(scanned, LangModelAnnotations.toIndex(new VaubanAnnotationInfo(scanned, null)));
    }

    @Test
    @DisplayName("an index-backed member gives back its own value")
    void indexBackedMemberIsUnwrapped() {
        var value = new AnnotationValue.EnumVal(DotName.of(Mood.class.getName()), "KEEN");

        assertEquals(value, LangModelAnnotations.toIndex(new VaubanAnnotationMember(value, null)));
    }

    @Test
    @DisplayName("an annotation with no member converts to an annotation with no member")
    void markerAnnotation() {
        assertEquals(new AnnotationInfo(DotName.of(AnnotationFixture.Marker.class.getName()), Map.of()),
                LangModelAnnotations.toIndex(AnnotationBuilder.of(AnnotationFixture.Marker.class).build()));
    }
}
