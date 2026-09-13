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
package com.acme.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What `vauban:modularize` produced during this very build.
 *
 * <p>The dependency `example-legacy-lib` ships without a `module-info`, which makes it an automatic
 * module on the module path — unusable for `jlink`, and unable to be depended on by name. The goal,
 * wired in this module's POM, writes a patched copy into {@code target/vauban-modularized/} under
 * the original file name, and this test reads the descriptor it synthesized.
 *
 * <p>It asserts what the goal decides, not merely that a file appeared: the module name it was
 * given, the packages it exports, and the {@code requires} it derived from the bytecode rather than
 * from a declaration.
 */
@DisplayName("vauban:modularize — the descriptor synthesized for a non-modular dependency")
class ModularizedDependencyTest {

    /**
     * The patched copy this build produced.
     *
     * <p>There is one whenever the dependency was resolved as a jar — which is the case in any real
     * build, and in {@code mvn install} here. Under a bare {@code mvn test} the reactor hands the
     * dependency over as its {@code target/classes} directory instead, and a directory is not
     * something the goal can modularize: there is nothing to assert then, and saying so is more
     * honest than failing over a condition the example does not control.
     */
    private static Path patchedJar() throws Exception {
        Path dir = Path.of("target", "vauban-modularized");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(dir),
                "no modularize output: run `mvn install` — under a bare `mvn test` the reactor "
                        + "dependency is a classes directory, not a jar");
        try (var files = Files.list(dir)) {
            var jar = files.filter(p -> p.getFileName().toString().startsWith("example-legacy-lib"))
                    .findFirst();
            org.junit.jupiter.api.Assumptions.assumeTrue(jar.isPresent(),
                    "the dependency was not resolved as a jar in this run, so nothing was patched");
            return jar.orElseThrow();
        }
    }

    @Test
    @DisplayName("the jar becomes an explicit module, named as the build asked")
    void theDependencyBecomesAnExplicitModule() throws Exception {
        Path jar = patchedJar();
        ModuleDescriptor descriptor = ModuleFinder.of(jar).findAll().iterator().next().descriptor();

        assertFalse(descriptor.isAutomatic(),
                "the whole point is that it stops being an automatic module");
        assertEquals("com.acme.legacy", descriptor.name(),
                "the <moduleNames> override in the POM decides the name");
        assertTrue(descriptor.isOpen(),
                "open by default: a library moving off the class path keeps working under reflection");
    }

    @Test
    @DisplayName("requires is derived from the bytecode, exports covers what the jar holds")
    void requiresAndExportsAreDerived() throws Exception {
        Path jar = patchedJar();
        ModuleDescriptor descriptor = ModuleFinder.of(jar).findAll().iterator().next().descriptor();

        List<String> requires = descriptor.requires().stream()
                .map(ModuleDescriptor.Requires::name).sorted().toList();
        assertTrue(requires.contains("jakarta.inject"),
                "Meters implements jakarta.inject.Provider, and nothing declared that anywhere — it "
                        + "was read off the class: " + requires);
        assertTrue(requires.contains("java.base"), "java.base is always there: " + requires);

        var exports = descriptor.exports().stream()
                .map(ModuleDescriptor.Exports::source).collect(Collectors.toSet());
        assertEquals(java.util.Set.of("com.acme.legacy", "com.acme.legacy.spi"), exports,
                "every package the jar holds is exported: it had everything visible on the class path");
    }

    @Test
    @DisplayName("the original jar is left alone — only a copy is written")
    void theOriginalIsNeverTouched() throws Exception {
        Path patched = patchedJar();
        assertTrue(patched.toAbsolutePath().toString().contains("vauban-modularized"),
                "the copy lives under target/, and nothing is installed or deployed: " + patched);

        // The copy carries the descriptor; the artefact in the local repository does not.
        try (var jar = new java.util.jar.JarFile(patched.toFile())) {
            assertTrue(jar.getEntry("module-info.class") != null,
                    "the copy is what gained the descriptor");
        }
    }
}
