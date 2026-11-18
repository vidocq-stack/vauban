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
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.spi.DeploymentException;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vauban:generate} fails when an {@code @Enhancement} it runs throws, as the start does: the exception is a
 * deployment problem, no longer dropped with the archive enhanced as if nothing happened (BUG-20261118-05).
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

    @Test
    @DisplayName("an exception of @Enhancement fails vauban:generate with the exception as its cause")
    void anExceptionOfEnhancementFailsTheGeneration() throws IOException, URISyntaxException {
        var classesDir = tempDir.resolve("classes");
        var ledger = Path.of(Ledger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(Ledger.class.getName().replace('.', '/') + ".class");
        var target = classesDir.resolve(Ledger.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.copy(ledger, target);
        var services = tempDir.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve("META-INF/services/" + BuildCompatibleExtension.class.getName()),
                ThrowingEnhancement.class.getName());

        try (var loader = new URLClassLoader(new URL[] {services.toUri().toURL()},
                EnhancementFailureGenerationTest.class.getClassLoader())) {
            var config = new VaubanGenerator.Config(List.of(), classesDir, tempDir.resolve("output"), loader);

            var thrown = assertThrows(DeploymentException.class, () -> VaubanGenerator.generate(config));

            assertAll(() -> assertTrue(thrown.getMessage().contains(ThrowingEnhancement.CAUSE), thrown.getMessage()),
                    () -> assertEquals(ThrowingEnhancement.CAUSE, thrown.getCause().getMessage()));
        }
    }
}
