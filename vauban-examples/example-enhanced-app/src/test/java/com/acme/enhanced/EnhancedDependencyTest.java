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
package com.acme.enhanced;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code vauban:enhance-dependencies} put inside the dependency jar during this build.
 *
 * <p>{@code FraudScreen} has a package-private member, so its client proxy can only live inside the
 * library's own package. The usual answer is to let the Vauban class loader place it there at run
 * time — nothing is rewritten, the jar keeps its signature. This goal is the other answer, for when
 * there will be no class loader to do it: a GraalVM native image defines its classes at build time.
 *
 * <p>So the proxy has to be <em>in the jar</em> beforehand, and this test reads what the goal wrote:
 * the co-located proxy, the in-package provider, the rewritten descriptor that offers it as a
 * service, and the manifest entries by which the copy declares it is not the original artefact.
 */
@DisplayName("vauban:enhance-dependencies — what ends up inside the rewritten jar")
class EnhancedDependencyTest {

    private static final String PKG = "io/vidocq/vauban/example/cdi1015/lib/";

    /**
     * The enhanced copy this build produced.
     *
     * <p>There is one whenever the dependency was resolved as a jar, which is the case in any real
     * build and in {@code mvn install} here. Under a bare {@code mvn test} the reactor hands the
     * dependency over as a {@code target/classes} directory, and a directory is not a jar to
     * rewrite: there is nothing to assert then, and saying so beats failing over a condition the
     * example does not control.
     */
    private static Path enhancedJar() throws Exception {
        Path dir = Path.of("target", "vauban-enhanced-deps");
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "no enhance output: run `mvn install` — under a bare `mvn test` the reactor "
                        + "dependency is a classes directory, not a jar");
        try (var files = Files.list(dir)) {
            var jar = files.filter(p -> p.getFileName().toString().endsWith(".jar")).findFirst();
            Assumptions.assumeTrue(jar.isPresent(), "the dependency was not resolved as a jar in this run");
            return jar.orElseThrow();
        }
    }

    @Test
    @DisplayName("the proxy is inside the library's own package, where nothing else could put it")
    void theProxyIsCoLocatedWithTheType() throws Exception {
        try (var jar = new JarFile(enhancedJar().toFile())) {
            assertNotNull(jar.getEntry(PKG + "FraudScreen_ClientProxy.class"),
                    "the whole point: a proxy in the library's package, overriding a member that "
                            + "can only be overridden from there");
            assertNotNull(jar.getEntry(PKG + "_VaubanComponents.class"),
                    "and a provider beside it, so the container instantiates both in-module");
            assertNotNull(jar.getEntry(PKG + "FraudScreen.class"),
                    "the original classes are still there — this is a copy of the jar, not a patch file");
        }
    }

    @Test
    @DisplayName("the descriptor is rewritten to offer the generated provider as a service")
    void theDescriptorOffersTheProvider() throws Exception {
        try (var jar = new JarFile(enhancedJar().toFile())) {
            byte[] moduleInfo;
            try (var in = jar.getInputStream(jar.getEntry("module-info.class"))) {
                moduleInfo = in.readAllBytes();
            }
            var descriptor = ModuleDescriptor.read(new java.io.ByteArrayInputStream(moduleInfo));

            assertTrue(descriptor.requires().stream()
                            .anyMatch(r -> r.name().equals("io.vidocq.vauban.api")),
                    "it now reads the module its provider implements: " + descriptor.requires());
            assertTrue(descriptor.provides().stream()
                            .anyMatch(p -> p.service().equals("io.vidocq.vauban.api.VaubanComponentProvider")),
                    "and declares the generated provider, which is how the container finds it "
                            + "without reflecting into the package: " + descriptor.provides());
        }
    }

    @Test
    @DisplayName("the copy says what it is, and drops the signature it can no longer honour")
    void theCopyDeclaresItself() throws Exception {
        Path enhanced = enhancedJar();
        try (var jar = new JarFile(enhanced.toFile())) {
            var attributes = jar.getManifest().getMainAttributes();

            // The coordinates, not the version: a release build renames every module, and
            // pinning the version here would only assert what the build was called.
            String from = attributes.getValue("Vauban-Enhanced-From");
            assertNotNull(from, "the copy must name the artefact it was derived from");
            assertTrue(from.startsWith("io.vidocq.vauban:example-cdi1015-lib:"),
                    "the coordinates of the artefact this was derived from: " + from);
            assertTrue(from.length() > "io.vidocq.vauban:example-cdi1015-lib:".length(),
                    "including its version: " + from);
            assertTrue(attributes.getValue("Vauban-Enhanced-Digest").startsWith("sha256:"),
                    "pinned to the exact bytes it was derived from: "
                            + attributes.getValue("Vauban-Enhanced-Digest"));
            assertNotNull(attributes.getValue("Vauban-Enhanced-By"));

            assertNull(jar.getEntry("META-INF/VENDOR.SF"),
                    "a signature could not survive the rewrite, so it is not carried over");
            assertTrue(jar.getManifest().getEntries().isEmpty(),
                    "nor the per-entry digests that belonged to it");
        }

        assertTrue(enhanced.toAbsolutePath().toString().contains("vauban-enhanced-deps"),
                "and it stays under target/: nothing is installed, deployed or redistributed");
    }
}
