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
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.net.URL;
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
 * What the Vidocq launchers rely on once the application's libraries live in its layer (vidocq#247): a library
 * that looks resources up through its own class loader sees the application's, as in a servlet container's
 * {@code WEB-INF/lib}; and a library class that is not a bean is defined as read.
 */
@DisplayName("Application libraries in the Vauban layer")
class ApplicationLibrariesInLayerTest {

    private static final String FINDER = "auto.lib.Finder";
    private static final String APP_MODULE = "com.example.app";
    private static final String MARKER = "META-INF/app/marker.txt";

    @Test
    @DisplayName("an automatic module in the layer finds an application resource through its own loader")
    void automaticLibrarySeesApplicationResources(@TempDir Path dir) throws Exception {
        var library = automaticJar(dir.resolve("auto-lib.jar"));
        var app = applicationModule(dir.resolve("app"));

        var layer = VaubanLayerFactory.createAppLayer(List.of(library, app), ModuleLayer.boot(),
                getClass().getClassLoader(), PluginContext.empty());

        var module = layer.layer().findModule("auto.lib").orElseThrow();
        assertTrue(module.getDescriptor().isAutomatic());
        var finder = Class.forName(module, FINDER);
        assertSame(layer.loader(), finder.getClassLoader(), "one loader for the application and its libraries");
        var url = (URL) finder.getMethod("find", String.class).invoke(null, MARKER);
        assertNotNull(url, "the library's own loader must see the application's resource");
        try (var in = url.openStream()) {
            assertEquals("app", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("a library class that is not a bean gains no ProxyLink constructor")
    void plainLibraryClassIsNotWoven(@TempDir Path dir) throws Exception {
        var root = dir.resolve("lib");
        var file = root.resolve("com/example/lib/Plain.class");
        Files.createDirectories(file.getParent());
        Files.write(file, VaubanClassLoaderTest.simpleClass("com.example.lib.Plain"));

        try (var loader = VaubanClassLoader.of(List.of(root), getClass().getClassLoader(),
                PluginContext.empty())) {
            var plain = Class.forName("com.example.lib.Plain", true, loader);
            var proxyLink = Class.forName("io.vidocq.vauban.api.ProxyLink");
            assertThrows(NoSuchMethodException.class, () -> plain.getDeclaredConstructor(proxyLink));
        }
    }

    /** An automatic module {@code auto.lib}: {@code Finder.find(name)} = its own loader's {@code getResource}. */
    private static Path automaticJar(Path file) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "auto.lib");
        try (OutputStream out = Files.newOutputStream(file); var jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("auto/lib/Finder.class"));
            jar.write(finderClass());
            jar.closeEntry();
        }
        return file;
    }

    private static byte[] finderClass() {
        var self = ClassDesc.of(FINDER);
        var loader = ClassDesc.of("java.lang.ClassLoader");
        var url = ClassDesc.of("java.net.URL");
        return ClassFile.of().build(self, clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC)
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody("find", MethodTypeDesc.of(url, ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> cob
                                .ldc(self)
                                .invokevirtual(ConstantDescs.CD_Class, "getClassLoader", MethodTypeDesc.of(loader))
                                .aload(0)
                                .invokevirtual(loader, "getResource", MethodTypeDesc.of(url, ConstantDescs.CD_String))
                                .areturn()));
    }

    /** An explicit module {@code com.example.app} that ships {@value #MARKER}. */
    private static Path applicationModule(Path root) throws IOException {
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(APP_MODULE),
                mb -> mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
        var marker = root.resolve(MARKER);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "app");
        return root;
    }
}
