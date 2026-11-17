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
import jakarta.enterprise.lang.model.declarations.ClassInfo;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code vauban:generate} runs an {@code @Enhancement} with the classes outside the index described from the class
 * files the project's loader finds, as the container does at start: the interface of the JDK above a bean is no longer
 * left out of {@code superInterfacesDeclarations()}, and its declaration has its type parameter (BUG-20261117-31).
 */
@DisplayName("VaubanGenerator - an extension sees a class outside the index described")
class OutsideClassGenerationTest {

    @TempDir
    Path tempDir;

    /** Records the declarations of the interfaces above the type it is given, with their type parameters. */
    public static class InterfaceRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Enhancement(types = Ledger.class, withSubtypes = false)
        public void enhance(ClassConfig clazz) {
            for (ClassInfo declaration : clazz.info().superInterfacesDeclarations()) {
                SEEN.add(declaration.name() + " " + declaration.typeParameters());
            }
            SEEN.add(clazz.info().superInterfacesDeclarations().size() + " of " + clazz.info().superInterfaces().size()
                    + " interfaces");
        }
    }

    @Test
    @DisplayName("an @Enhancement sees the declaration of an interface of the JDK above a bean")
    void anEnhancementSeesTheDeclarationOfAnInterfaceOutsideTheIndex() throws IOException, URISyntaxException {
        var classesDir = tempDir.resolve("classes");
        var ledger = Path.of(Ledger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(Ledger.class.getName().replace('.', '/') + ".class");
        var target = classesDir.resolve(Ledger.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.copy(ledger, target);
        var services = tempDir.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve("META-INF/services/" + BuildCompatibleExtension.class.getName()),
                InterfaceRecorder.class.getName());

        InterfaceRecorder.SEEN.clear();
        try (var loader = new URLClassLoader(new URL[] {services.toUri().toURL()},
                OutsideClassGenerationTest.class.getClassLoader())) {
            VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classesDir, tempDir.resolve("output"),
                    loader));
        }

        assertEquals(List.of("java.util.function.Supplier [T extends java.lang.Object]", "1 of 1 interfaces"),
                List.copyOf(InterfaceRecorder.SEEN));
    }
}
