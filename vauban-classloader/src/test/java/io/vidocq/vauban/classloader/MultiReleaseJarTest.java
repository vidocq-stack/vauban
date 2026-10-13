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
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A multi-release jar read by the Vauban loader as the module path reads it: the entries of
 * {@code META-INF/versions/N/} win for a JDK at least N (vauban#154). Jackson, among others, ships such jars;
 * the loader used to index raw entry names, so the base version always won.
 */
@DisplayName("VaubanClassLoader — multi-release jars")
class MultiReleaseJarTest {

    private static final String VALUE = "com.example.mr.Value";
    private static final String RAW_VERSIONED_VALUE = "META-INF.versions.21.com.example.mr.Value";
    private static final String ONLY_VERSIONED = "com.example.mr.OnlyVersioned";

    @Test
    @DisplayName("the versioned class, the versioned resource and a versions-only class win on this JDK")
    void versionedEntriesWin(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("mr.jar"), true);
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertEquals("versioned", Class.forName(VALUE, true, loader).getMethod("value").invoke(null));
            assertEquals("versioned", Class.forName(ONLY_VERSIONED, true, loader).getMethod("value").invoke(null));
            try (var in = loader.getResourceAsStream("com/example/mr/data.txt")) {
                assertNotNull(in);
                assertEquals("versioned", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(loader.managedClassNames().stream().noneMatch(n -> n.startsWith("META-INF")),
                    "no class named after a META-INF/versions/ entry: " + loader.managedClassNames());
        }
    }

    @Test
    @DisplayName("a jar without Multi-Release: true reads its base entries only")
    void plainJarReadsBaseEntries(@TempDir Path dir) throws Exception {
        var jar = jar(dir.resolve("plain.jar"), false);
        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertEquals("base", Class.forName(VALUE, true, loader).getMethod("value").invoke(null));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(ONLY_VERSIONED, true, loader));
            // Unchanged from before multi-release support: the raw META-INF/versions/ entries stay listed under
            // their raw names, and no class of that raw name is ever defined (the JVM refuses the bytes: wrong name).
            var raw = RAW_VERSIONED_VALUE;
            assertTrue(loader.managedClassNames().contains(raw),
                    "raw entry name stays listed, as before: " + loader.managedClassNames());
            assertThrows(NoClassDefFoundError.class, () -> Class.forName(raw, true, loader),
                    "defining the raw name is refused: the bytes name another class");
        }
    }

    @Test
    @DisplayName("an entry under a version above the running JDK is ignored: the base entry wins")
    void higherVersionIgnored(@TempDir Path dir) throws Exception {
        var above = Runtime.version().feature() + 1;
        var file = dir.resolve("above.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        try (OutputStream out = Files.newOutputStream(file); var jar = new JarOutputStream(out, manifest)) {
            put(jar, "com/example/mr/Value.class", valueClass(VALUE, "base"));
            put(jar, "META-INF/versions/" + above + "/com/example/mr/Value.class", valueClass(VALUE, "future"));
            put(jar, "META-INF/versions/" + above + "/com/example/mr/OnlyVersioned.class",
                    valueClass(ONLY_VERSIONED, "future"));
        }
        try (var loader = VaubanClassLoader.of(List.of(file), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertEquals("base", Class.forName(VALUE, true, loader).getMethod("value").invoke(null));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(ONLY_VERSIONED, true, loader));
        }
    }

    /** A jar with a base {@code Value}, a versioned {@code Value} and resource, and a versions-only class. */
    private static Path jar(Path file, boolean multiRelease) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        }
        try (OutputStream out = Files.newOutputStream(file); var jar = new JarOutputStream(out, manifest)) {
            put(jar, "com/example/mr/Value.class", valueClass(VALUE, "base"));
            put(jar, "com/example/mr/data.txt", "base".getBytes(StandardCharsets.UTF_8));
            put(jar, "META-INF/versions/21/com/example/mr/Value.class", valueClass(VALUE, "versioned"));
            put(jar, "META-INF/versions/21/com/example/mr/data.txt", "versioned".getBytes(StandardCharsets.UTF_8));
            put(jar, "META-INF/versions/21/com/example/mr/OnlyVersioned.class", valueClass(ONLY_VERSIONED, "versioned"));
        }
        return file;
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    /** {@code public class <name> { public static String value() { return "<value>"; } }} */
    private static byte[] valueClass(String binaryName, String value) {
        return ClassFile.of().build(ClassDesc.of(binaryName), clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC)
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody("value", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> cob.ldc(value).areturn()));
    }
}
