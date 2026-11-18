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

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invoker's method by names, read from the language model as the build reads a class it compiles, names the
 * method reflection finds, and the start finds it back (BUG-20261117-48).
 */
@DisplayName("BUG-20261117-48 - an invoker's method by names")
class InvokerTargetTest {

    /** Each kind of parameter a method may declare. */
    @SuppressWarnings({"unused", "java:S1172"}) // the parameters are the shapes under test
    public static class Shapes<N extends Number> {
        public void primitive(int value, boolean flag, char letter) {
            // no behaviour needed
        }

        public void arrays(String[] texts, int[][] grid, N[] numbers, List<String>[] lists) {
            // no behaviour needed
        }

        public void generic(List<String> texts, Map<String, ? extends N> map, N number) {
            // no behaviour needed
        }

        public <T> void unbounded(T value) {
            // no behaviour needed
        }

        public static void nested(Inner inner, long count, double ratio, float part, byte bits, short small) {
            // no behaviour needed
        }

        @Override
        public String toString() {
            return "shapes";
        }

        public static class Inner {
        }
    }

    @Test
    @DisplayName("each parameter is the erasure reflection gives, and the start finds the method back")
    void eachParameterIsTheErasureReflectionGives() throws IOException {
        var loader = getClass().getClassLoader();
        var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for (var method : methods()) {
            if (method.isConstructor()) {
                continue;
            }
            var target = InvokerTarget.of(Shapes.class.getName(), method);
            checks.add(() -> {
                var reflected = target.resolve(loader);
                assertEquals(InvokerTarget.of(Shapes.class, reflected), target, method.name());
            });
        }
        assertAll(checks);
        assertEquals(6, checks.size(), "the methods checked");
    }

    @Test
    @DisplayName("a method of Object is one, and a method that no longer exists is refused")
    void anObjectMethodAndAMissingOne() {
        var toString = new InvokerTarget(Shapes.class.getName(), Object.class.getName(), "toString", List.of());
        var missing = new InvokerTarget(Shapes.class.getName(), Shapes.class.getName(), "gone", List.of("int"));
        var loader = getClass().getClassLoader();

        assertTrue(toString.isObjectMethod(), "toString of Object");
        var refused = assertThrows(IllegalArgumentException.class, () -> missing.resolve(loader));
        assertTrue(refused.getMessage().contains("gone[int] no longer exists"), refused::getMessage);
    }

    private static List<MethodInfo> methods() throws IOException {
        var resource = "/" + Shapes.class.getName().replace('.', '/') + ".class";
        try (var in = Shapes.class.getResourceAsStream(resource)) {
            var scanned = ClassFileScanner.scan(java.util.Objects.requireNonNull(in).readAllBytes());
            var lookup = new IndexLookup(new IndexBuilder().add(scanned).build());
            return List.copyOf(new VaubanClassInfo(scanned, lookup).methods()).stream()
                    .filter(method -> method.declaringClass().name().equals(Shapes.class.getName()))
                    .toList();
        }
    }
}
