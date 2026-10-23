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
package io.vidocq.vauban.maven.enhance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The types {@code vauban:enhance-dependencies} refuses, and the jar it writes for a type it can proxy. */
class DependencyEnhancerProxyabilityTest {

    private static final String SERVICE_FILE = "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";
    private static final String COORDINATES = "com.acme:lib:1.0";

    @TempDir
    Path tmp;

    public static final class FinalType {
        public String name() {
            return "final";
        }
    }

    public abstract static class AbstractType {
        public abstract String name();
    }

    public static class PrivateConstructorOnly {
        private PrivateConstructorOnly() {
        }

        public static PrivateConstructorOnly create() {
            return new PrivateConstructorOnly();
        }
    }

    public static class FinalMethod {
        public final String name() {
            return "fixed";
        }
    }

    /** Proxyable from anywhere: a static method and a private one are no obstacle. */
    public static class Open {
        public static Open create() {
            return new Open();
        }

        public String name() {
            return decorate("open");
        }

        private String decorate(String value) {
            return "[" + value + "]";
        }
    }

    static Stream<Arguments> unproxyableTypes() {
        return Stream.of(
                Arguments.of(Runnable.class, " is not a proxyable class"),
                Arguments.of(FinalType.class, " is final (unproxyable)"),
                Arguments.of(AbstractType.class, " is abstract (unproxyable)"),
                Arguments.of(PrivateConstructorOnly.class, " has no non-private constructor (unproxyable)"),
                Arguments.of(FinalMethod.class, " has a non-static final method name (unproxyable per CDI 4.1 §3.10)"));
    }

    @ParameterizedTest
    @MethodSource("unproxyableTypes")
    void anUnproxyableTypeIsLeftAloneWithItsReason(Class<?> type, String reason) throws IOException {
        var warnings = new ArrayList<String>();

        var result = DependencyEnhancer.enhance(tmp.resolve("absent.jar"), COORDINATES, tmp.resolve("out"),
                List.of(type.getName()), getClass().getClassLoader(), warnings);

        assertNull(result.enhancedJar());
        assertEquals(List.of(), result.enhancedTypes());
        assertEquals(List.of("enhance: " + type.getName() + reason), warnings);
    }

    @Test
    void aTypeThatCannotBeLoadedIsLeftAloneWithTheError() throws IOException {
        var warnings = new ArrayList<String>();

        var result = DependencyEnhancer.enhance(tmp.resolve("absent.jar"), COORDINATES, tmp.resolve("out"),
                List.of("com.acme.Missing"), getClass().getClassLoader(), warnings);

        assertNull(result.enhancedJar());
        assertEquals(List.of("enhance: cannot load com.acme.Missing (java.lang.ClassNotFoundException:"
                + " com.acme.Missing)"), warnings);
    }

    @Test
    void aProxyableTypeGetsItsProxyAndTheJarItsOwnServiceFileOnly() throws IOException {
        var source = tmp.resolve("lib.jar");
        var entry = Open.class.getName().replace('.', '/') + ".class";
        try (var out = new JarOutputStream(Files.newOutputStream(source));
                var in = getClass().getClassLoader().getResourceAsStream(entry)) {
            out.putNextEntry(new JarEntry(entry));
            out.write(in.readAllBytes());
            out.closeEntry();
            out.putNextEntry(new JarEntry(SERVICE_FILE));
            out.write("com.stale._VaubanComponents\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        var warnings = new ArrayList<String>();

        var result = DependencyEnhancer.enhance(source, COORDINATES, tmp.resolve("out"), List.of(Open.class.getName()),
                getClass().getClassLoader(), warnings);

        assertEquals(List.of(), warnings, "a type proxyable from any package needs no placement hint");
        assertEquals(List.of(Open.class.getName()), result.enhancedTypes());
        var provider = Open.class.getPackageName() + "._VaubanComponents";
        assertEquals(List.of(provider), result.providerFqns());
        try (var enhanced = new JarFile(result.enhancedJar().toFile());
                var service = enhanced.getInputStream(enhanced.getEntry(SERVICE_FILE))) {
            assertEquals(provider + "\n", new String(service.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(enhanced.getEntry(Open.class.getName().replace('.', '/') + "_ClientProxy.class") != null);
        }
    }
}
