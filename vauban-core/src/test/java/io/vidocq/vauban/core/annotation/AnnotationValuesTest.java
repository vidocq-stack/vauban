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

import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A live annotation, read member by member, must give exactly what the bytecode scan records for the
 * same annotation: same member names, same value kinds, same binary type names. The JDK builds the
 * live instance and the Class-File API reads the class file, so neither side is Vauban's own code.
 */
@DisplayName("vauban#70: AnnotationValues reads a live annotation losslessly, as the bytecode scan does")
class AnnotationValuesTest {

    public enum Mood {
        CALM,
        ANGRY {
            @Override
            public String toString() {
                return "grr";
            }
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Leaf {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Everything {
        boolean z();
        byte b();
        char c();
        short s();
        int i();
        long j();
        float f();
        double d();
        String text();
        Class<?> type();
        Class<?> primitive();
        Class<?> array();
        Mood mood();
        Mood body();
        Leaf leaf();
        boolean[] zs();
        byte[] bs();
        char[] cs();
        short[] ss();
        int[] is();
        long[] js();
        float[] fs();
        double[] ds();
        String[] texts();
        Class<?>[] types();
        Mood[] moods();
        Leaf[] leaves();
    }

    @Everything(z = true, b = 1, c = 'c', s = 2, i = 3, j = 4L, f = 5.5f, d = 6.5,
            text = "t", type = Map.Entry.class, primitive = int.class, array = String[].class,
            mood = Mood.CALM, body = Mood.ANGRY, leaf = @Leaf("l"),
            zs = {true, false}, bs = {1, 2}, cs = {'a'}, ss = {3}, is = {4, 5}, js = {6L}, fs = {7.5f}, ds = {8.5},
            texts = {"x", "y"}, types = {Integer.class, long[].class}, moods = {Mood.ANGRY},
            leaves = {@Leaf("m"), @Leaf("n")})
    static final class Carrier {
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (var in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            assertNotNull(in, "class bytes of " + type.getName());
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("every member kind converts to the value the bytecode scan records")
    void matchesTheBytecodeScan() throws IOException {
        var scanned = ClassFileScanner.scan(bytesOf(Carrier.class)).annotations().stream()
                .filter(annotation -> annotation.name().value().equals(Everything.class.getName()))
                .findFirst()
                .orElseThrow();
        var converted = AnnotationValues.toAnnotationInfo(Carrier.class.getAnnotation(Everything.class));

        assertEquals(scanned.name(), converted.name());
        assertEquals(scanned.members().keySet(), converted.members().keySet());
        for (var member : scanned.members().keySet()) {
            assertEquals(scanned.members().get(member), converted.members().get(member), member);
        }
    }

    @Test
    @DisplayName("an enum constant with a body keeps its enum type, not the constant's own class")
    void enumConstantWithBody() {
        assertEquals(new AnnotationValue.EnumVal(DotName.of(Mood.class.getName()), "ANGRY"), AnnotationValues.of(Mood.ANGRY));
    }
}
