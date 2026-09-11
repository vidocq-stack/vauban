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
package io.vidocq.vauban.classloader;

import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which boot-layer modules the Java SE launcher re-layers, and which it must leave where they are.
 * Each kept rule exists because re-layering that module would break the layer: an automatic module
 * would read its own boot twin, a module with a {@code javax.}/{@code sun.}/{@code com.sun.} package
 * would be an empty shell under a loader that refuses to define those, and a module read by a kept
 * module must stay with it or the kept module and the application would see two copies.
 */
@DisplayName("Launch — which boot-layer modules are re-layered")
class LaunchSelectionTest {

    @Test
    @DisplayName("automatic, excluded-package, prefixed modules and what kept modules read stay; the rest moves")
    void keptModulesAndWhatTheyReadStay(@TempDir Path dir) throws Exception {
        var app = explodedModule(dir, "app", List.of("keepme", "auto.lib"), "app.Main");
        var keepme = explodedModule(dir, "keepme", List.of("shared"), "keepme.K");
        var shared = explodedModule(dir, "shared", List.of(), "shared.S");
        var legacy = explodedModule(dir, "legacy", List.of(), "javax.legacyfoo.L");
        var plain = explodedModule(dir, "plain", List.of(), "plain.P");
        var auto = automaticJar(dir, "auto-lib.jar", "autolib.Util");

        var config = ModuleLayer.boot().configuration().resolve(
                ModuleFinder.of(app, keepme, shared, legacy, plain, auto), ModuleFinder.of(),
                Set.of("app", "legacy", "plain"));

        var relayered = Launch.applicationPaths(config, List.of("keepme")).stream()
                .map(LaunchSelectionTest::real)
                .collect(Collectors.toSet());

        assertEquals(Set.of(real(app), real(plain)), relayered,
                "keepme matches a keep prefix; shared is read by keepme (a kept module cannot read a "
                        + "re-layered one); auto.lib is automatic; legacy owns a javax. package. Only app "
                        + "and plain may move — and following the automatic module's reads would have "
                        + "kept app too");
    }

    @Test
    @DisplayName("already in a Vauban layer, run() does nothing and returns false — no recursion")
    void runIsANoOpInsideALayer() throws Throwable {
        var previous = Thread.currentThread().getContextClassLoader();
        try (var layerLoader = VaubanClassLoader.of(List.of(), getClass().getClassLoader(),
                PluginContext.empty())) {
            Thread.currentThread().setContextClassLoader(layerLoader);
            assertFalse(Launch.run("nowhere/nowhere.Main", new String[0]),
                    "re-entry from inside a layer must not invoke the target again: an application "
                            + "calling Launch.run from its own main would recurse forever");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** An exploded explicit module: a module-info requiring {@code requires}, and one class. */
    private static Path explodedModule(Path dir, String name, List<String> requires, String className)
            throws IOException {
        var root = dir.resolve(name);
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(
                ModuleAttribute.of(ModuleDesc.of(name), mb -> {
                    mb.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null));
                    for (var required : requires) {
                        mb.requires(ModuleRequireInfo.of(ModuleDesc.of(required), 0, null));
                    }
                })));
        var classFile = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, VaubanClassLoaderTest.simpleClass(className));
        return root;
    }

    /** A plain jar without module-info: an automatic module named after the file. */
    private static Path automaticJar(Path dir, String fileName, String className) throws IOException {
        var jar = dir.resolve(fileName);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
            out.write(VaubanClassLoaderTest.simpleClass(className));
            out.closeEntry();
        }
        return jar;
    }
}
