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
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleExportInfo;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The module-aware lookups of the loader answer for the module they name, as the JDK's own loaders do
 * (BUG-20261114-05). {@code Module.getResourceAsStream}, {@code Module.getResource} and {@code Class.getResource}
 * on a class of a named module all reach {@code findResource(moduleName, name)}; {@code Class.forName(Module, name)},
 * and so {@code ServiceLoader}, reaches {@code findClass(moduleName, name)}. Both used to ignore the module: a
 * resource outside any package, such as a {@code META-INF/} file, came from the first archive that had it, whichever
 * module asked.
 */
@DisplayName("Module-scoped lookups — a module of a Vauban layer answers from its own archive only")
class ModuleScopedLookupTest {

    private static final String ALPHA = "com.example.alpha";
    private static final String BETA = "com.example.beta";
    private static final String ALPHA_TYPE = "com.example.alpha.Alpha";
    private static final String BETA_TYPE = "com.example.beta.Beta";
    /** In both modules, with a content of each. */
    private static final String SHARED = "META-INF/vidocq/extension.properties";
    /** At the root of both archives, with a content of each. */
    private static final String ROOT_FILE = "root.txt";
    /** In the alpha module only. */
    private static final String ALPHA_ONLY = "META-INF/alpha-only.txt";
    /** In the beta module only. */
    private static final String BETA_ONLY = "META-INF/beta-only.txt";

    @Test
    @DisplayName("each module of the layer reads its own copy of a resource outside any package")
    void eachModuleReadsItsOwnCopy(@TempDir Path dir) throws Exception {
        var layer = twoModuleLayer(dir);
        var alpha = layer.layer().findModule(ALPHA).orElseThrow();
        var beta = layer.layer().findModule(BETA).orElseThrow();

        assertEquals("id=alpha", read(alpha.getResourceAsStream(SHARED)));
        assertEquals("id=beta", read(beta.getResourceAsStream(SHARED)));
        assertEquals("alpha root", read(alpha.getResourceAsStream(ROOT_FILE)));
        assertEquals("beta root", read(beta.getResourceAsStream(ROOT_FILE)));
        assertEquals("id=beta", read(layer.loader().findResource(BETA, SHARED)));
    }

    @Test
    @DisplayName("a module that lacks a resource does not find another module's copy")
    void aModuleThatLacksAResourceFindsNothing(@TempDir Path dir) throws Exception {
        var layer = twoModuleLayer(dir);
        var alpha = layer.layer().findModule(ALPHA).orElseThrow();
        var beta = layer.layer().findModule(BETA).orElseThrow();

        assertEquals("alpha only", read(alpha.getResourceAsStream(ALPHA_ONLY)));
        assertNull(beta.getResourceAsStream(ALPHA_ONLY), "beta has no " + ALPHA_ONLY);
        assertNull(layer.loader().findResource(BETA, ALPHA_ONLY), "beta has no " + ALPHA_ONLY);
        assertNull(layer.loader().findResource("com.example.missing", SHARED),
                "no module of that name is defined to the loader");
        assertNotNull(layer.loader().getResource(ALPHA_ONLY), "the loader itself still sees every archive");
    }

    @Test
    @DisplayName("the unnamed module of a layer loader has no class path: it holds no archive resource")
    void theUnnamedModuleOfALayerLoaderHoldsNoResource(@TempDir Path dir) throws Exception {
        var layer = twoModuleLayer(dir);

        assertNull(layer.loader().findResource(null, SHARED), "every archive of the layer is a named module");
        assertNull(layer.loader().getUnnamedModule().getResourceAsStream(SHARED));
        assertNotNull(layer.loader().getResource(SHARED), "the loader itself still sees every archive");
    }

    @Test
    @DisplayName("a module finds its own classes, never another module's")
    void aModuleFindsItsOwnClassesOnly(@TempDir Path dir) throws Exception {
        var layer = twoModuleLayer(dir);
        var loader = layer.loader();
        var alpha = layer.layer().findModule(ALPHA).orElseThrow();

        assertNull(loader.findClass(BETA, ALPHA_TYPE), ALPHA_TYPE + " is not in " + BETA);
        assertNull(loader.findClass(null, ALPHA_TYPE), "the unnamed module of a layer loader holds no archive class");
        assertNull(loader.findClass("com.example.missing", ALPHA_TYPE));
        var type = loader.findClass(ALPHA, ALPHA_TYPE);
        assertNotNull(type);
        assertSame(alpha, type.getModule());
        assertSame(type, Class.forName(alpha, ALPHA_TYPE));
    }

    @Test
    @DisplayName("the unnamed module of a class-path loader holds every archive's resources and classes")
    void theUnnamedModuleOfAClassPathLoaderHoldsTheArchives(@TempDir Path dir) throws Exception {
        var archives = archives(dir);
        try (var loader = VaubanClassLoader.of(List.of(archives.get(ALPHA), archives.get(BETA)),
                getClass().getClassLoader(), PluginContext.empty())) {
            assertEquals("id=alpha", read(loader.findResource(null, SHARED)), "first archive first");
            assertEquals("id=alpha", read(loader.getUnnamedModule().getResourceAsStream(SHARED)));
            assertEquals("id=beta", read(loader.findResource(null, BETA_ONLY)), "from any archive of the class path");
            assertNull(loader.findResource(ALPHA, SHARED), "a class-path loader defines no named module");

            assertNull(loader.findClass(ALPHA, ALPHA_TYPE), "a class-path loader defines no named module");
            var type = loader.findClass(null, BETA_TYPE);
            assertNotNull(type);
            assertSame(loader.getUnnamedModule(), type.getModule());
        }
    }

    /** The modules {@value #ALPHA} (a jar) and {@value #BETA} (a directory), resolved in a layer of their own. */
    private VaubanLayerFactory.AppLayer twoModuleLayer(Path dir) throws IOException {
        var archives = archives(dir);
        return VaubanLayerFactory.createAppLayer(List.of(archives.get(ALPHA), archives.get(BETA)),
                ModuleLayer.boot(), getClass().getClassLoader(), PluginContext.empty());
    }

    private static Map<String, Path> archives(Path dir) throws IOException {
        var alpha = dir.resolve("alpha.jar");
        try (OutputStream out = Files.newOutputStream(alpha); var jar = new JarOutputStream(out)) {
            entry(jar, "module-info.class", moduleInfo(ALPHA));
            entry(jar, ALPHA_TYPE.replace('.', '/') + ".class", VaubanClassLoaderTest.simpleClass(ALPHA_TYPE));
            entry(jar, SHARED, "id=alpha".getBytes(StandardCharsets.UTF_8));
            entry(jar, ROOT_FILE, "alpha root".getBytes(StandardCharsets.UTF_8));
            entry(jar, ALPHA_ONLY, "alpha only".getBytes(StandardCharsets.UTF_8));
        }
        var beta = dir.resolve("beta");
        write(beta.resolve("module-info.class"), moduleInfo(BETA));
        write(beta.resolve(BETA_TYPE.replace('.', '/') + ".class"), VaubanClassLoaderTest.simpleClass(BETA_TYPE));
        write(beta.resolve(SHARED), "id=beta".getBytes(StandardCharsets.UTF_8));
        write(beta.resolve(ROOT_FILE), "beta root".getBytes(StandardCharsets.UTF_8));
        write(beta.resolve(BETA_ONLY), "id=beta".getBytes(StandardCharsets.UTF_8));
        return Map.of(ALPHA, alpha, BETA, beta);
    }

    /** {@code module <name> { exports <name>; }}: the module's one package is named after it. */
    private static byte[] moduleInfo(String name) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(name), mb -> mb
                .requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)
                .exports(ModuleExportInfo.of(PackageDesc.of(name), 0))));
    }

    private static void entry(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static String read(URL url) throws IOException {
        assertNotNull(url);
        try (var in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String read(InputStream stream) throws IOException {
        assertNotNull(stream);
        try (var in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
