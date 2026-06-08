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
package io.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class SjarPluginTest {

    @TempDir
    Path tempDir;

    @Test
    void handlesEncryptedJar() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var jarPath = createModularJar();
        SjarEncryptor.encryptJar(jarPath, key, "test");

        var plugin = new SjarPlugin();
        assertTrue(plugin.handles(jarPath));
    }

    @Test
    void doesNotHandlePlainJar() throws Exception {
        var jarPath = createModularJar();

        var plugin = new SjarPlugin();
        assertFalse(plugin.handles(jarPath));
    }

    @Test
    void protocol() {
        assertEquals("vauban-encrypted", new SjarPlugin().protocol());
    }

    private Path createModularJar() throws Exception {
        var jarPath = tempDir.resolve("test.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jos.putNextEntry(new JarEntry("module-info.class"));
            jos.write(ClassFile.of().buildModule(
                    java.lang.classfile.attribute.ModuleAttribute.of(
                            java.lang.constant.ModuleDesc.of("test.module"),
                            mb -> mb.requires(java.lang.constant.ModuleDesc.of("java.base"), 0, null)),
                    mb -> {}));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("com/test/Foo.class"));
            jos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 0x41});
            jos.closeEntry();
        }
        return jarPath;
    }
}
