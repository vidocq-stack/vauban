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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link EnhancementPatchSerializer}: a build-time @Enhancement
 * result ({@code target FQN -> [annotation FQN...]}) must survive write→read so the
 * runtime can apply it without re-instantiating the BCE.
 */
@DisplayName("EnhancementPatchSerializer")
class EnhancementPatchSerializerTest {

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("single target with one added annotation (the @Path -> @RequestScoped case)")
        void singleTargetSingleAnnotation() throws IOException {
            var patch = Map.of(
                    "com.example.HelloResource",
                    List.of("jakarta.enterprise.context.RequestScoped"));

            assertEquals(patch, roundTrip(patch));
        }

        @Test
        @DisplayName("multiple targets, multiple annotations each")
        void multipleTargets() throws IOException {
            var patch = Map.of(
                    "com.example.HelloResource",
                    List.of("jakarta.enterprise.context.RequestScoped"),
                    "com.example.Repo$Nested",
                    List.of("jakarta.enterprise.context.ApplicationScoped",
                            "jakarta.inject.Singleton"));

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
                    List.of("a.B", "a.A", "a.C")); // deliberately non-sorted
            assertEquals(List.of("a.B", "a.A", "a.C"),
                    roundTrip(patch).get("com.example.A"));
        }
    }

    @Test
    @DisplayName("written form is human-readable UTF-8 text")
    void writtenFormIsText() throws IOException {
        var baos = new ByteArrayOutputStream();
        EnhancementPatchSerializer.write(
                Map.of("com.example.HelloResource",
                        List.of("jakarta.enterprise.context.RequestScoped")),
                baos);
        var text = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("com.example.HelloResource"), text);
        assertTrue(text.contains("jakarta.enterprise.context.RequestScoped"), text);
    }

    private static Map<String, List<String>> roundTrip(Map<String, List<String>> patch)
            throws IOException {
        var baos = new ByteArrayOutputStream();
        EnhancementPatchSerializer.write(patch, baos);
        return EnhancementPatchSerializer.read(new ByteArrayInputStream(baos.toByteArray()));
    }
}
