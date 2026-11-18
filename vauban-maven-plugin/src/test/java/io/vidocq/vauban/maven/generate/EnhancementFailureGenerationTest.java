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
package io.vidocq.vauban.maven.generate;

import io.vidocq.vauban.maven.generate.outside.Ledger;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.spi.DefinitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vauban:generate} fails when an {@code @Enhancement} it runs throws, as the start does: the exception is a
 * definition error (BUG-20261118-57), no longer dropped with the archive enhanced as if nothing happened
 * (BUG-20261118-05).
 */
@DisplayName("VaubanGenerator - an exception of @Enhancement fails the generation")
class EnhancementFailureGenerationTest {

    @TempDir
    Path tempDir;

    /** Throws in {@code @Enhancement}, as an extension that meets a class it cannot handle. */
    public static class ThrowingEnhancement implements BuildCompatibleExtension {

        static final String CAUSE = "no rule for this ledger";

        @Enhancement(types = Ledger.class, withSubtypes = false)
        public void enhance(ClassConfig clazz) {
            throw new IllegalStateException(CAUSE);
        }
    }

    /** Declares an {@code @Enhancement} parameter of a type the phase does not support, and records each call. */
    public static class InvalidParameter implements BuildCompatibleExtension {

        static final List<String> CALLED = new CopyOnWriteArrayList<>();

        @Enhancement(types = Ledger.class, withSubtypes = false)
        public void enhance(ClassConfig clazz, BeanInfo bean) {
            CALLED.add("enhance " + clazz + bean);
        }
    }

    @Test
    @DisplayName("an exception of @Enhancement fails vauban:generate with a definition error, the exception its cause")
    void anExceptionOfEnhancementFailsTheGeneration() throws IOException, URISyntaxException {
        try (var loader = loaderFor(ThrowingEnhancement.class)) {
            var config = configWith(loader);

            var thrown = assertThrows(DefinitionException.class, () -> VaubanGenerator.generate(config));

            assertAll(() -> assertTrue(thrown.getMessage().contains(ThrowingEnhancement.CAUSE), thrown.getMessage()),
                    () -> assertEquals(ThrowingEnhancement.CAUSE, thrown.getCause().getMessage()));
        }
    }

    /**
     * An {@code @Enhancement} parameter of an unsupported type fails {@code vauban:generate} before the call, with the
     * definition error the start reports (BUG-20261118-22).
     */
    @Test
    @DisplayName("an unsupported @Enhancement parameter fails vauban:generate before the call")
    void anUnsupportedParameterOfEnhancementFailsTheGeneration() throws IOException, URISyntaxException {
        InvalidParameter.CALLED.clear();
        var expected = "@Enhancement method " + InvalidParameter.class.getName()
                + ".enhance has invalid parameter type: " + BeanInfo.class.getName();
        try (var loader = loaderFor(InvalidParameter.class)) {
            var config = configWith(loader);

            var thrown = assertThrows(DefinitionException.class, () -> VaubanGenerator.generate(config));

            List<String> called = InvalidParameter.CALLED;
            assertAll(() -> assertTrue(thrown.getMessage().contains(expected), thrown.getMessage()),
                    () -> assertEquals(List.of(), called, "the method is not called"));
        }
    }

    /** A loader that finds {@code extension} as the one build compatible extension. */
    private URLClassLoader loaderFor(Class<?> extension) throws IOException {
        var services = tempDir.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve("META-INF/services/" + BuildCompatibleExtension.class.getName()),
                extension.getName());
        return new URLClassLoader(new URL[] {services.toUri().toURL()},
                EnhancementFailureGenerationTest.class.getClassLoader());
    }

    /** The generation of the classes directory that holds {@code Ledger}, with {@code loader}. */
    private VaubanGenerator.Config configWith(URLClassLoader loader) throws IOException, URISyntaxException {
        var classesDir = tempDir.resolve("classes");
        var ledger = Path.of(Ledger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(Ledger.class.getName().replace('.', '/') + ".class");
        var target = classesDir.resolve(Ledger.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.copy(ledger, target);
        return new VaubanGenerator.Config(List.of(), classesDir, tempDir.resolve("output"), loader);
    }
}
