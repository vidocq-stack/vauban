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

import io.vidocq.vauban.sjar.SjarEncryptor;
import io.vidocq.vauban.sjar.SjarKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * An encrypted jar as a module of the application layer: the launch of a Vidocq application that depends on one.
 * Its exported package stays in clear; its internal package exists only as encrypted blobs under opaque names.
 */
@DisplayName("VaubanLayerFactory — an encrypted module in the application layer")
class EncryptedModuleInLayerTest {

    private static final String MODULE = "com.acme.secretlib";

    @Test
    @DisplayName("an exported class reaches a class of the encrypted internal package")
    void anExportedClassReachesAnEncryptedInternalClass(@TempDir Path dir) throws Exception {
        Path jar = secretLibrary(dir);
        SecretKey key = SjarKeyProvider.generateKey();
        SjarEncryptor.encryptJar(jar, key, "test");

        var appLayer = VaubanLayerFactory.createAppLayer(List.of(jar), ModuleLayer.boot(),
                getClass().getClassLoader(), SjarKeyProvider.withKey(key));

        var module = appLayer.layer().findModule(MODULE).orElseThrow();
        var api = Class.forName(module, MODULE + ".Secret");
        assertEquals("classified", api.getMethod("reveal").invoke(null));
        var vault = appLayer.loader().loadClass(MODULE + ".internal.Vault");
        assertSame(module, vault.getModule(), "the internal class belongs to the module, not to the unnamed one");
    }

    /** {@code module com.acme.secretlib { exports com.acme.secretlib; }}, with an internal package. */
    private static Path secretLibrary(Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        args.add(write(src, "module-info.java", "module " + MODULE + " { exports " + MODULE + "; }\n"));
        args.add(write(src, "com/acme/secretlib/Secret.java", "package " + MODULE + ";\n"
                + "public final class Secret {\n"
                + "  public static String reveal() { return " + MODULE + ".internal.Vault.value(); }\n}\n"));
        args.add(write(src, "com/acme/secretlib/internal/Vault.java", "package " + MODULE + ".internal;\n"
                + "public final class Vault {\n  public static String value() { return \"classified\"; }\n}\n"));
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                args.toArray(String[]::new)));

        Path jar = dir.resolve("secretlib.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String write(Path src, String name, String content) throws Exception {
        Path file = src.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file.toString();
    }
}
