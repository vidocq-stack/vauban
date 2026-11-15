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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.classloader.VaubanClassLoader;
import io.vidocq.vauban.classloader.spi.PluginContext;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An archive the Vauban processor handled at compile ({@code META-INF/vauban-bce-processed}) is recognised as such
 * whatever loader defines its classes, so that a deployment whose archives were all processed does not run its
 * build compatible extensions at start (BUG-20261115-07). The marker is found as a resource, the class's archive
 * through its code source, and the two name one location in different spellings: {@code jar:file:///a/app.jar!/…}
 * and {@code file:/a/app.jar} under the Vauban class loader, and under the loader of a jar module in a module layer.
 */
@DisplayName("An archive processed at compile is recognised under any class loader (BUG-20261115-07)")
class ProcessedArchiveLocationTest {

    private static final String MODULE = "m.app";
    private static final String BEAN = "app.ProcessedBean";
    private static final String OTHER_BEAN = "app.OtherBean";
    private static final String MARKER = "META-INF/vauban-bce-processed";
    private static final String BEANS_LIST = "META-INF/vauban-beans.list";
    private static final String EXTENSION_SERVICE =
            "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";
    private static final MethodTypeDesc VOID = MethodTypeDesc.of(ConstantDescs.CD_void);

    @TempDir
    Path tmp;

    /** The deployment's extension, which a processed archive declares as Foy's jar declares its own. */
    public static class ArchiveExtension implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withSubtypes = false)
        public void nothing(ClassConfig ignored) {
            // The extension does no work: whether it runs is the point.
        }
    }

    @Test
    @DisplayName("a processed jar under the Vauban class loader runs no extension at start")
    void processedJarUnderTheVaubanClassLoader() throws Exception {
        Path jar = jar(tmp.resolve("app.jar"), BEAN, true);

        assertEquals(List.of(), extensionsRunUnderVaubanLoader(List.of(jar)));
    }

    @Test
    @DisplayName("a processed directory of classes under the Vauban class loader runs no extension at start")
    void processedDirectoryUnderTheVaubanClassLoader() throws Exception {
        Path classes = directory(tmp.resolve("classes"), BEAN, true);

        assertEquals(List.of(), extensionsRunUnderVaubanLoader(List.of(classes)));
    }

    @Test
    @DisplayName("a processed jar whose path holds a space and a non-ASCII character runs no extension at start")
    void processedJarUnderAnEscapedPath() throws Exception {
        Path jar = jar(Files.createDirectories(tmp.resolve("with space été")).resolve("app.jar"), BEAN, true);

        assertEquals(List.of(), extensionsRunUnderVaubanLoader(List.of(jar)));
    }

    @Test
    @DisplayName("a processed jar under a URLClassLoader still runs no extension at start")
    void processedJarUnderAUrlClassLoader() throws Exception {
        Path jar = jar(tmp.resolve("app.jar"), BEAN, true);

        assertEquals(List.of(), extensionsRunUnderUrlLoader(jar.toUri().toURL()));
    }

    /**
     * No regression, not a reproduction: under a {@code URLClassLoader} the marker and the code source share the
     * spelling of the URL the loader was given, so the comparison as strings held too. A raw spelling against an
     * escaped one is proven in {@link ProcessedArchivesTest}.
     */
    @Test
    @DisplayName("no regression: a processed jar under a URLClassLoader given a raw non-ASCII URL runs no extension")
    void noRegressionUnderARawUrl() throws Exception {
        Path jar = jar(Files.createDirectories(tmp.resolve("with space été")).resolve("app.jar"), BEAN, true);
        // The spelling of java.io.File#toURI: the space escaped, the non-ASCII character left as is.
        URL raw = URL.of(new URI("file", null, jar.toString(), null), null);

        assertEquals(List.of(), extensionsRunUnderUrlLoader(raw));
    }

    @Test
    @DisplayName("a processed jar module in a module layer runs no extension at start")
    void processedJarModuleInALayer() throws Exception {
        Path jar = jar(Files.createDirectories(tmp.resolve("with space été")).resolve("m.app.jar"), moduleEntries());

        assertEquals(List.of(), extensionsRunInALayer(jar));
    }

    @Test
    @DisplayName("a processed exploded module in a module layer runs no extension at start")
    void processedExplodedModuleInALayer() throws Exception {
        Path module = directory(tmp.resolve("modules").resolve(MODULE), moduleEntries());

        assertEquals(List.of(), extensionsRunInALayer(module));
    }

    @Test
    @DisplayName("an archive without the marker runs the extensions at start")
    void unprocessedJarRunsTheExtensions() throws Exception {
        Path jar = jar(tmp.resolve("app.jar"), BEAN, false);

        assertEquals(List.of(ArchiveExtension.class.getName()), extensionsRunUnderVaubanLoader(List.of(jar)));
    }

    @Test
    @DisplayName("the marker of one archive does not stand for an archive beside it")
    void oneProcessedArchiveBesideAnUnprocessedOne() throws Exception {
        Path processed = jar(tmp.resolve("processed.jar"), BEAN, true);
        Path raw = jar(Files.createDirectories(tmp.resolve("raw")).resolve("processed.jar"), OTHER_BEAN, false);

        assertEquals(List.of(ArchiveExtension.class.getName()),
                extensionsRunUnderVaubanLoader(List.of(processed, raw)));
    }

    @Test
    @DisplayName("the build of a deployment whose archives were all processed says every extension ran at compile")
    void freezingAProcessedDeployment() throws Exception {
        Path jar = jar(tmp.resolve("app.jar"), BEAN, true);

        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(), PluginContext.empty())) {
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(Files.createDirectories(tmp.resolve("output")));

            assertFalse(outcome.frozen());
            assertEquals(FrozenExtensions.ALL_AT_COMPILE, outcome.reason());
            assertTrue(outcome.nothingToFreeze(), "the start runs no extension: nothing to warn about");
        }
    }

    @Test
    @DisplayName("the build of a deployment without an extension still says it has none")
    void freezingADeploymentWithoutAnExtension() throws Exception {
        try (var loader = VaubanClassLoader.of(List.of(), getClass().getClassLoader(), PluginContext.empty())) {
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(Files.createDirectories(tmp.resolve("output")));

            assertEquals(FrozenExtensions.NO_EXTENSION, outcome.reason());
            assertTrue(outcome.nothingToFreeze());
        }
    }

    // ---- helpers ----

    private List<String> extensionsRunUnderVaubanLoader(List<Path> archives) throws Exception {
        try (var loader = VaubanClassLoader.of(archives, getClass().getClassLoader(), PluginContext.empty())) {
            return extensionsRunAtStart(loader);
        }
    }

    private List<String> extensionsRunUnderUrlLoader(URL archive) throws Exception {
        try (var loader = new URLClassLoader(new URL[]{archive}, getClass().getClassLoader())) {
            return extensionsRunAtStart(loader);
        }
    }

    /** The module path's loader: module {@code m.app}, read from {@code module}, in a layer above the boot layer. */
    private List<String> extensionsRunInALayer(Path module) {
        var configuration = ModuleLayer.boot().configuration()
                .resolve(ModuleFinder.of(module), ModuleFinder.of(), Set.of(MODULE));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, getClass().getClassLoader());
        return extensionsRunAtStart(layer.findLoader(MODULE));
    }

    /** The extensions the start of the deployment {@code loader} sees ran: none when every archive was processed. */
    private static List<String> extensionsRunAtStart(ClassLoader loader) {
        try (var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            return container.extensionsAtStart().ranAtStart();
        }
    }

    /** A jar as the Vauban processor leaves it: a bean, its index, the extension's service file, and the marker. */
    private static Path jar(Path target, String bean, boolean processed) throws IOException {
        return jar(target, entries(bean, processed));
    }

    private static Path jar(Path target, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(target); var jar = new JarOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return target;
    }

    /** The same archive as a directory of classes, as an IDE or {@code target/classes} holds it. */
    private static Path directory(Path target, String bean, boolean processed) throws IOException {
        return directory(target, entries(bean, processed));
    }

    private static Path directory(Path target, Map<String, byte[]> entries) throws IOException {
        for (var entry : entries.entrySet()) {
            Path file = target.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        return target;
    }

    /** A processed archive that is also module {@code m.app}: open, exporting the bean's package. */
    private static Map<String, byte[]> moduleEntries() {
        var descriptor = ModuleAttribute.of(ModuleDesc.of(MODULE), module -> module
                .moduleFlags(ClassFile.ACC_OPEN)
                .requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)
                .exports(PackageDesc.of("app"), 0));
        var entries = entries(BEAN, true);
        entries.put("module-info.class", ClassFile.of().buildModule(descriptor));
        return entries;
    }

    private static Map<String, byte[]> entries(String bean, boolean processed) {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put(bean.replace('.', '/') + ".class", dependentBean(bean));
        entries.put(BEANS_LIST, (bean + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put(EXTENSION_SERVICE, (ArchiveExtension.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        if (processed) {
            entries.put(MARKER, "# processed at compile\n".getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    /** A {@code @Dependent} class with a public no-argument constructor, that only this archive holds. */
    private static byte[] dependentBean(String name) {
        return ClassFile.of().build(ClassDesc.of(name), clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ConstantDescs.CD_Object)
                .with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of(Dependent.class.getName()))))
                .withMethodBody(ConstantDescs.INIT_NAME, VOID, ClassFile.ACC_PUBLIC, cob -> cob
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, VOID)
                        .return_()));
    }
}
