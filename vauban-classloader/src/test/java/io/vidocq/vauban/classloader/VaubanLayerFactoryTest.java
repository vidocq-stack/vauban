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
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The application module layer (M2): modules resolved off the module path, defined by the
 * engine loader, classes woven at definition.
 *
 * <p>The module-mode loading test guards a real regression: the JVM loads classes of
 * custom-loader layer modules through {@code ClassLoader.findClass(String moduleName,
 * String name)}, whose inherited default returns {@code null} — with only the name-based
 * {@code findClass} overridden, every layer class whose definition was not already
 * triggered by the plain path came back "not found" ({@code ServiceLoader} providers were
 * the first victims).
 */
@DisplayName("VaubanLayerFactory — application modules defined by the engine loader")
class VaubanLayerFactoryTest {

    private static final String MODULE = "com.example.layered";
    private static final String BEAN = "com.example.layered.Service";

    @Test
    @DisplayName("a layer module's unwoven bean loads through Class.forName(Module, name) and is woven")
    void moduleModeLoadingWeaves(@TempDir Path dir) throws Exception {
        var app = modularAppArchive(dir);
        var appLayer = VaubanLayerFactory.createAppLayer(List.of(app), ModuleLayer.boot(),
                getClass().getClassLoader(), PluginContext.empty());

        assertEquals(java.util.Set.of(MODULE), appLayer.moduleNames());
        var module = appLayer.layer().findModule(MODULE).orElseThrow();

        // Module-mode lookup — the exact path ServiceLoader and the module system use.
        // The class was never loaded through the name-based path before this call.
        var bean = Class.forName(module, BEAN);
        assertNotNull(bean, "Class.forName(Module, name) must reach findClass(moduleName, name)");
        assertSame(appLayer.loader(), bean.getClassLoader());
        assertSame(module, bean.getModule(), "the class must belong to the layer module");
        assertNotNull(bean.getDeclaredConstructor(
                Class.forName("io.vidocq.vauban.api.ProxyLink")),
                "the bean must be woven at definition");

        // Module-mode resources reach the archive too
        try (var in = module.getResourceAsStream("META-INF/vauban-beans.list")) {
            assertNotNull(in, "module resource lookup must reach the archive");
        }
    }

    @Test
    @DisplayName("META-INF/services declarations are promoted to synthetic provides (no module-info declaration needed)")
    void servicesFilesArePromotedToProvides(@TempDir Path dir) throws Exception {
        var root = modularAppArchive(dir);
        // an exported service interface + its implementation, declared ONLY through a
        // standard services file — exactly how APT-generated providers ship
        Files.write(root.resolve("com/example/layered/Api.class"),
                serviceInterface("com.example.layered.Api"));
        Files.write(root.resolve("com/example/layered/ApiImpl.class"),
                serviceImpl("com.example.layered.ApiImpl", "com.example.layered.Api"));
        var services = root.resolve("META-INF/services/com.example.layered.Api");
        Files.createDirectories(services.getParent());
        Files.writeString(services, "# generated\ncom.example.layered.ApiImpl\n");

        var appLayer = VaubanLayerFactory.createAppLayer(List.of(root), ModuleLayer.boot(),
                getClass().getClassLoader(), PluginContext.empty());
        var module = appLayer.layer().findModule(MODULE).orElseThrow();

        var promoted = module.getDescriptor().provides().stream()
                .filter(p -> p.service().equals("com.example.layered.Api"))
                .findFirst();
        assertTrue(promoted.isPresent(), "provides must be synthesized from the services file");
        assertEquals(List.of("com.example.layered.ApiImpl"), promoted.get().providers());

        // and the promoted provider is loadable and instantiable through the layer —
        // exactly what ServiceLoader does from the descriptor (a direct ServiceLoader
        // call is impossible here: the patched test module cannot declare `uses`; the
        // real ServiceLoader path is exercised by the Vidocq end-to-end runs)
        var api = Class.forName(module, "com.example.layered.Api");
        var impl = Class.forName(module, "com.example.layered.ApiImpl");
        assertSame(appLayer.loader(), impl.getClassLoader());
        var instance = impl.getDeclaredConstructor().newInstance();
        assertTrue(api.isInstance(instance), "the provider implements the promoted service");
    }

    @Test
    @DisplayName("spike: a child layer may shadow a parent layer's module of the same name (trampoline re-layering)")
    void childLayerShadowsParentModule(@TempDir Path dir) throws Exception {
        var root = modularAppArchive(dir);
        var first = VaubanLayerFactory.createAppLayer(List.of(root), ModuleLayer.boot(),
                getClass().getClassLoader(), PluginContext.empty());
        // same module name, resolved AGAIN in a child of the first layer — the exact
        // shape of Vidocq.run() re-layering an app already resolved in the boot layer
        var second = VaubanLayerFactory.createAppLayer(List.of(root), first.layer(),
                first.loader(), PluginContext.empty());

        var m1 = first.layer().findModule(MODULE).orElseThrow();
        var m2 = second.layer().findModule(MODULE).orElseThrow();
        assertNotSame(m1, m2, "the child layer must carry its own module instance");

        var c1 = Class.forName(m1, BEAN);
        var c2 = Class.forName(m2, BEAN);
        assertNotSame(c1, c2, "each layer defines its own classes");
        assertSame(second.loader(), c2.getClassLoader());
    }

    /** Modular exploded archive: module-info + unwoven @ApplicationScoped bean + beans.list. */
    private static Path modularAppArchive(Path dir) throws IOException {
        var root = VaubanClassLoaderTest.appArchive(dir, BEAN, true);
        Files.write(root.resolve("module-info.class"),
                ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(MODULE),
                        mb -> mb.requires(ModuleDesc.of("java.base"),
                                ClassFile.ACC_MANDATED, null)
                          .exports(java.lang.classfile.attribute.ModuleExportInfo.of(
                                  java.lang.constant.PackageDesc.of("com.example.layered"), 0)))));
        return root;
    }

    /** {@code public interface <name> {}} */
    private static byte[] serviceInterface(String binaryName) {
        return ClassFile.of().build(java.lang.constant.ClassDesc.of(binaryName), clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
                .withSuperclass(java.lang.constant.ConstantDescs.CD_Object));
    }

    /** {@code public class <name> implements <iface> { public <name>() {} }} */
    private static byte[] serviceImpl(String binaryName, String ifaceName) {
        var desc = java.lang.constant.ClassDesc.of(binaryName);
        return ClassFile.of().build(desc, clb -> clb
                .withSuperclass(java.lang.constant.ConstantDescs.CD_Object)
                .withInterfaceSymbols(java.lang.constant.ClassDesc.of(ifaceName))
                .withMethodBody(java.lang.constant.ConstantDescs.INIT_NAME,
                        java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, cob -> {
                            cob.aload(0);
                            cob.invokespecial(java.lang.constant.ConstantDescs.CD_Object,
                                    java.lang.constant.ConstantDescs.INIT_NAME,
                                    java.lang.constant.MethodTypeDesc.of(
                                            java.lang.constant.ConstantDescs.CD_void));
                            cob.return_();
                        }));
    }
}
