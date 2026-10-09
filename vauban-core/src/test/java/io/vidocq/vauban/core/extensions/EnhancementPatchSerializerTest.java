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

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link EnhancementPatchSerializer}: a build-time @Enhancement
 * result ({@code target -> [added annotation...]}) must survive write→read so the
 * runtime can apply it without re-instantiating the BCE.
 */
@DisplayName("EnhancementPatchSerializer")
class EnhancementPatchSerializerTest {

    private static AnnotationInfo ann(String name) {
        return new AnnotationInfo(DotName.of(name), Map.of());
    }

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("single target with one added annotation (the @Path -> @RequestScoped case)")
        void singleTargetSingleAnnotation() throws IOException {
            var patch = Map.of(
                    "com.example.HelloResource",
                    List.of(ann("jakarta.enterprise.context.RequestScoped")));

            assertEquals(patch, roundTrip(patch));
        }

        @Test
        @DisplayName("multiple targets, multiple annotations each")
        void multipleTargets() throws IOException {
            var patch = Map.of(
                    "com.example.HelloResource",
                    List.of(ann("jakarta.enterprise.context.RequestScoped")),
                    "com.example.Repo$Nested",
                    List.of(ann("jakarta.enterprise.context.ApplicationScoped"),
                            ann("jakarta.inject.Singleton")));

            assertEquals(patch, roundTrip(patch));
        }

        @Test
        @DisplayName("empty patch yields an empty map")
        void emptyPatch() throws IOException {
            assertEquals(Map.of(), roundTrip(Map.of()));
        }

        @Test
        @DisplayName("annotation order per target is preserved")
        void orderPreserved() throws IOException {
            var patch = Map.of("com.example.A",
                    List.of(ann("a.B"), ann("a.A"), ann("a.C"))); // deliberately non-sorted
            assertEquals(List.of(ann("a.B"), ann("a.A"), ann("a.C")),
                    roundTrip(patch).get("com.example.A"));
        }

        @Test
        @DisplayName("member values survive (BUG-20261008-05)")
        void membersSurvive() throws IOException {
            var named = new AnnotationInfo(DotName.of("jakarta.inject.Named"),
                    Map.of("value", new AnnotationValue.StringVal("a=b, c:d\n#é")));
            var channel = new AnnotationInfo(DotName.of("com.example.Channel"), Map.of(
                    "value", new AnnotationValue.StringVal("beta"),
                    "weights", new AnnotationValue.ArrayVal(List.of(new AnnotationValue.IntVal(1)))));
            var patch = Map.of("com.example.Bean", List.of(named, channel));
            assertEquals(patch, roundTrip(patch));
        }

        @Test
        void memberTargetsAndOverloadDescriptorsSurvive() throws IOException {
            var named = new AnnotationInfo(DotName.of("jakarta.inject.Named"),
                    Map.of("value", new AnnotationValue.StringVal("selected")));
            var patch = Map.of(
                    "com.example.Bean#field#dependency", List.of(named, ann("jakarta.inject.Inject")),
                    "com.example.Bean#method#initialize(Ljava/lang/String;)V", List.of(ann("jakarta.inject.Inject")),
                    "com.example.Bean#parameter#<init>([Ljava/lang/String;)V#0", List.of(named));
            var read = roundTrip(patch);
            assertEquals(patch, read);
            read.keySet().forEach(target -> assertEquals(DotName.of("com.example.Bean"),
                    EnhancementPatchSerializer.owner(DotName.of(target))));
        }
    }

    @Test
    @DisplayName("the form earlier versions wrote still reads, members empty")
    void earlierFormReads() throws IOException {
        var text = "# old\ncom.example.A=jakarta.enterprise.context.RequestScoped, jakarta.inject.Named\n";
        var patch = EnhancementPatchSerializer.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Map.of("com.example.A",
                        List.of(ann("jakarta.enterprise.context.RequestScoped"), ann("jakarta.inject.Named"))),
                patch);
    }

    @Test
    @DisplayName("written form is UTF-8 text naming the target and the annotation")
    void writtenFormIsText() throws IOException {
        var baos = new ByteArrayOutputStream();
        EnhancementPatchSerializer.write(
                Map.of("com.example.HelloResource",
                        List.of(ann("jakarta.enterprise.context.RequestScoped"))),
                baos);
        var text = baos.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("com.example.HelloResource"), text);
        assertTrue(text.contains("jakarta.enterprise.context.RequestScoped"), text);
    }

    private static Map<String, List<AnnotationInfo>> roundTrip(Map<String, List<AnnotationInfo>> patch)
            throws IOException {
        var baos = new ByteArrayOutputStream();
        EnhancementPatchSerializer.write(patch, baos);
        return EnhancementPatchSerializer.read(new ByteArrayInputStream(baos.toByteArray()));
    }
}
