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

import io.vidocq.vauban.maven.enhance.fixture.Widget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 2 (issue #42): enhancing a modular dependency jar adds a co-located proxy, a provider, and a rewritten module-info. */
class DependencyEnhancerTest {

    private static final String PKG = "io/vidocq/vauban/maven/enhance/fixture/";

    @Test
    @DisplayName("enhances a modular jar: co-located proxy + provider + module-info provides")
    void enhancesModularJar(@TempDir Path tmp) throws Exception {
        var fqn = Widget.class.getName();
        var srcJar = tmp.resolve("libwidget.jar");
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("fixture.mod"),
                mb -> mb.requires(ModuleRequireInfo.of(
                        ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
        byte[] widgetBytes;
        try (var is = getClass().getResourceAsStream("/" + PKG + "Widget.class")) {
            widgetBytes = is.readAllBytes();
        }
        try (var out = new JarOutputStream(Files.newOutputStream(srcJar))) {
            put(out, "module-info.class", moduleInfo);
            put(out, PKG + "Widget.class", widgetBytes);
        }

        var warnings = new ArrayList<String>();
        var result = DependencyEnhancer.enhance(
                srcJar, tmp.resolve("enhanced"), List.of(fqn), getClass().getClassLoader(), warnings);

        assertTrue(warnings.isEmpty(), "no warnings expected, got: " + warnings);
        assertNotNull(result.enhancedJar());
        assertEquals(List.of(fqn), result.enhancedTypes());

        try (var jar = new JarFile(result.enhancedJar().toFile())) {
            assertNotNull(jar.getEntry(PKG + "Widget_ClientProxy.class"), "co-located client proxy");
            assertNotNull(jar.getEntry(PKG + "_VaubanComponents.class"), "in-package provider");
            assertNotNull(jar.getEntry("META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider"),
                    "class-path service file");
            byte[] mi;
            try (var is = jar.getInputStream(jar.getEntry("module-info.class"))) {
                mi = is.readAllBytes();
            }
            var attr = ClassFile.of().parse(mi).findAttribute(Attributes.module()).orElseThrow();
            assertTrue(attr.requires().stream().anyMatch(
                            r -> r.requires().name().stringValue().equals("io.vidocq.vauban.api")),
                    "module-info must require io.vidocq.vauban.api");
            assertTrue(attr.provides().stream().anyMatch(p -> p.provides().asSymbol().descriptorString()
                            .equals("Lio/vidocq/vauban/api/VaubanComponentProvider;")),
                    "module-info must provide VaubanComponentProvider");

            // Strong check: the JDK's own module reader accepts the rewritten descriptor.
            var descriptor = java.lang.module.ModuleDescriptor.read(new java.io.ByteArrayInputStream(mi));
            assertTrue(descriptor.requires().stream()
                            .anyMatch(r -> r.name().equals("io.vidocq.vauban.api")),
                    "JDK ModuleDescriptor must see requires io.vidocq.vauban.api");
            assertTrue(descriptor.provides().stream()
                            .anyMatch(p -> p.service().equals("io.vidocq.vauban.api.VaubanComponentProvider")
                                    && p.providers().contains(
                                            "io.vidocq.vauban.maven.enhance.fixture._VaubanComponents")),
                    "JDK ModuleDescriptor must see the provides with the generated provider");
        }
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
