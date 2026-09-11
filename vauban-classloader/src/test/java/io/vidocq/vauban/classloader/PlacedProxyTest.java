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
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #42 Stage 4 — a client proxy that must live in the produced type's own package (a non-public
 * overridable member, or no accessible constructor) is generated at build time as a
 * <em>resource</em> of the bean archive, and the Vauban class loader — which owns the external
 * module — defines it into that package on a {@code findClass} miss. No {@code opens}, no agent,
 * no rewritten jar: the loader that defined the package may define further classes in it.
 *
 * <p>The same definition step closes the #24 residual for third-party produced types: the
 * external class is listed in the placement manifest, so {@code CdiProxifierTransformer} weaves
 * the side-effect-free {@code (ProxyLink)} entry constructor into it as it is defined and
 * retargets the placed proxy onto it. The fixture's business constructor <strong>throws</strong>,
 * so instantiating the placed proxy discriminates: if the retarget did not happen, the test
 * cannot pass.
 */
@DisplayName("VaubanClassLoader — placed client proxies (#42 Stage 4)")
class PlacedProxyTest {

    /** The produced type of a CDI-agnostic library: public, no scope, package-private member. */
    private static final String WIDGET = "com.example.lib.Widget";
    private static final String PROXY = WIDGET + "_ClientProxy";
    private static final String MANIFEST = "META-INF/vauban/required-opens.list";
    private static final String PLACED = "META-INF/vauban/placed/com/example/lib/Widget_ClientProxy.class";

    @Test
    @DisplayName("the placed proxy lands in the produced type's package and never runs its constructor")
    void placedProxyIsDefinedInPackageWithoutRunningTheBusinessConstructor(@TempDir Path dir)
            throws Exception {
        var lib = libArchive(dir);
        var app = appArchive(dir, true, true);
        try (var loader = VaubanClassLoader.of(List.of(lib, app), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertTrue(loader.placesClass(PROXY), "the loader must know it can place the proxy");

            var proxy = Class.forName(PROXY, true, loader);
            assertSame(loader, proxy.getClassLoader(), "defined by the loader that owns the package");
            assertEquals(WIDGET, proxy.getSuperclass().getName());
            assertSame(proxy.getSuperclass().getPackage(), proxy.getPackage(),
                    "same runtime package as the produced type — that is the whole point");

            // The external type was woven at definition: it now carries the (ProxyLink) marker.
            var widget = proxy.getSuperclass();
            assertNotNull(widget.getDeclaredConstructor(Class.forName("io.vidocq.vauban.api.ProxyLink")),
                    "the produced type must have gained the side-effect-free entry constructor");

            // And the placed proxy was retargeted onto it: the throwing business constructor of
            // Widget must NOT run. This is the #24 double-construction, closed for external types.
            var instance = proxy.getDeclaredConstructor().newInstance();
            assertNotNull(instance);
        }
    }

    @Test
    @DisplayName("without a manifest entry nothing is placed — placement is declared, never guessed")
    void withoutManifestEntryNothingIsPlaced(@TempDir Path dir) throws Exception {
        var lib = libArchive(dir);
        var app = appArchive(dir, false, true);
        try (var loader = VaubanClassLoader.of(List.of(lib, app), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertFalse(loader.placesClass(PROXY));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(PROXY, true, loader));
        }
    }

    @Test
    @DisplayName("without the placed resource nothing is placed — the runtime keeps its fallback")
    void withoutPlacedResourceNothingIsPlaced(@TempDir Path dir) throws Exception {
        var lib = libArchive(dir);
        var app = appArchive(dir, true, false);
        try (var loader = VaubanClassLoader.of(List.of(lib, app), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertFalse(loader.placesClass(PROXY));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(PROXY, true, loader));
        }
    }

    @Test
    @DisplayName("a real class entry always wins over a placed resource")
    void archiveEntryWinsOverPlacedResource(@TempDir Path dir) throws Exception {
        var lib = libArchive(dir);
        var app = appArchive(dir, true, true);
        // An enhanced-dependencies copy, say, that already ships the proxy as a class entry.
        var shipped = lib.resolve("com/example/lib/Widget_ClientProxy.class");
        Files.write(shipped, VaubanClassLoaderTest.simpleClass(PROXY)); // extends Object: distinguishable
        try (var loader = VaubanClassLoader.of(List.of(lib, app), getClass().getClassLoader(),
                PluginContext.empty())) {
            assertTrue(loader.managesClass(PROXY));
            assertFalse(loader.placesClass(PROXY), "an indexed class is managed, not placed");
            var proxy = Class.forName(PROXY, true, loader);
            assertEquals(Object.class, proxy.getSuperclass(), "the archive's own class was defined");
        }
    }

    @Test
    @DisplayName("a layer loader never delegates a placed proxy to its parent")
    void placedProxyIsNeverDelegatedToTheParent(@TempDir Path dir) throws Exception {
        // The parent already knows a class of the same name — as the boot layer does once a
        // container started there has defined the reflective fallback proxy into its own copy of
        // the library. Delegating would return the PARENT's proxy, which extends the parent's
        // produced type: a different class from the one this layer defines, hence a
        // ClassCastException at the injection point.
        var parentRoot = dir.resolve("parent");
        var parentProxy = parentRoot.resolve("com/example/lib/Widget_ClientProxy.class");
        Files.createDirectories(parentProxy.getParent());
        Files.write(parentProxy, VaubanClassLoaderTest.simpleClass(PROXY)); // extends Object
        var lib = libArchive(dir);
        var app = appArchive(dir, true, true);
        try (var parent = VaubanClassLoader.of(List.of(parentRoot), getClass().getClassLoader(),
                PluginContext.empty());
             var layer = VaubanClassLoader.forLayer(List.of(lib, app), parent, PluginContext.empty())) {
            var proxy = Class.forName(PROXY, true, layer);
            assertSame(layer, proxy.getClassLoader(),
                    "the placed proxy must be defined by the layer loader, not found in the parent");
            assertEquals(WIDGET, proxy.getSuperclass().getName(),
                    "it must extend THIS layer's produced type");
            assertSame(layer, proxy.getSuperclass().getClassLoader());
        }
    }

    @Test
    @DisplayName("a layer loader never delegates any name of a package it owns, placed or not")
    void ownedPackageNamesAreNeverDelegated(@TempDir Path dir) throws Exception {
        // The reflective fallback (or an $$Intercepted subclass) asks for a name that is neither
        // an archive entry nor placed. If the parent knows that name, handing it back would give
        // this layer a class extending the PARENT's types. Owned package ⇒ self-first ⇒ a clean
        // miss, which is what sends the fallback to define the class here instead.
        var name = "com.example.lib.Widget$$Intercepted";
        var parentRoot = dir.resolve("parent");
        var parentClass = parentRoot.resolve("com/example/lib/Widget$$Intercepted.class");
        Files.createDirectories(parentClass.getParent());
        Files.write(parentClass, VaubanClassLoaderTest.simpleClass(name));
        var lib = libArchive(dir);
        try (var parent = VaubanClassLoader.of(List.of(parentRoot), getClass().getClassLoader(),
                PluginContext.empty());
             var layer = VaubanClassLoader.forLayer(List.of(lib), parent, PluginContext.empty())) {
            assertNotNull(Class.forName(name, false, parent), "the parent does know the name");
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name, false, layer),
                    "com.example.lib belongs to the layer: its names must never come from the parent");
        }
    }

    @Test
    @DisplayName("a placed type's superclass with only a business constructor is woven too")
    void placedTypesSuperclassIsWovenWhenItHasOnlyABusinessConstructor(@TempDir Path dir)
            throws Exception {
        // Base has no usable no-arg constructor: unless the loader weaves it as well, the placed
        // type has no side-effect-free chain and its placed proxy runs the business constructors
        // after all (the #24 double construction). Both constructors throw, so instantiating the
        // placed proxy discriminates.
        var lib = dir.resolve("lib");
        var base = lib.resolve("com/example/lib/Base.class");
        Files.createDirectories(base.getParent());
        Files.write(base, classWithThrowingStringCtor("com.example.lib.Base", "java.lang.Object"));
        Files.write(lib.resolve("com/example/lib/Widget.class"),
                classWithThrowingStringCtor(WIDGET, "com.example.lib.Base"));
        var app = dir.resolve("app");
        Files.createDirectories(app.resolve("META-INF/vauban"));
        Files.writeString(app.resolve(MANIFEST), WIDGET + "\n");
        var placed = app.resolve(PLACED);
        Files.createDirectories(placed.getParent());
        Files.write(placed, VaubanClassLoaderTest.proxyChainingBusinessCtor(PROXY, WIDGET));
        try (var layer = VaubanClassLoader.forLayer(List.of(lib, app), getClass().getClassLoader(),
                PluginContext.empty())) {
            var proxy = Class.forName(PROXY, true, layer);
            var proxyLink = Class.forName("io.vidocq.vauban.api.ProxyLink");
            assertNotNull(proxy.getSuperclass().getSuperclass().getDeclaredConstructor(proxyLink),
                    "Base must have gained the entry constructor for Widget's to chain to it");
            assertNotNull(proxy.getDeclaredConstructor().newInstance(),
                    "neither business constructor may run for the placed proxy");
        }
    }

    /**
     * {@code public class <name> extends <superName> { public <name>(String s) { super(…); throw …; } }}
     * — no no-arg constructor, and a business constructor that always throws.
     */
    static byte[] classWithThrowingStringCtor(String binaryName, String superName) {
        var desc = ClassDesc.of(binaryName);
        var superDesc = ClassDesc.of(superName);
        var ise = ClassDesc.of("java.lang.IllegalStateException");
        return ClassFile.of().build(desc, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC);
            clb.withSuperclass(superDesc);
            clb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        if ("java.lang.Object".equals(superName)) {
                            cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                        } else {
                            cob.aconst_null();
                            cob.invokespecial(superDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
                        }
                        cob.new_(ise);
                        cob.dup();
                        cob.invokespecial(ise, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.athrow();
                    });
            clb.withMethodBody("internalOnly", MethodTypeDesc.of(ConstantDescs.CD_String),
                    0, cob -> {
                        cob.ldc("internal");
                        cob.areturn();
                    });
        });
    }

    // ---------------------------------------------------------------- fixtures

    /** Exploded library archive: {@code Widget} only — no beans list, it knows nothing about CDI. */
    static Path libArchive(Path dir) throws IOException {
        var root = dir.resolve("lib");
        var file = root.resolve("com/example/lib/Widget.class");
        Files.createDirectories(file.getParent());
        Files.write(file, plainClassWithThrowingNoArgCtor(WIDGET));
        return root;
    }

    /**
     * Exploded bean archive: optionally the placement manifest naming {@code Widget}, optionally
     * the placed proxy bytes as a resource — exactly what the annotation processor writes.
     */
    static Path appArchive(Path dir, boolean withManifest, boolean withPlaced) throws IOException {
        var root = dir.resolve("app");
        Files.createDirectories(root.resolve("META-INF/vauban"));
        if (withManifest) {
            Files.writeString(root.resolve(MANIFEST), "# placement manifest\n" + WIDGET + "\n");
        }
        if (withPlaced) {
            var placed = root.resolve(PLACED);
            Files.createDirectories(placed.getParent());
            Files.write(placed, proxyChainingNoArgCtor(PROXY, WIDGET));
        }
        return root;
    }

    /**
     * {@code public class <name> { public <name>() { throw …; } String internalOnly() {…} }} —
     * a plain class with no scope annotation whose business constructor is guaranteed to blow up.
     */
    static byte[] plainClassWithThrowingNoArgCtor(String binaryName) {
        var desc = ClassDesc.of(binaryName);
        return ClassFile.of().build(desc, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        var ise = ClassDesc.of("java.lang.IllegalStateException");
                        cob.new_(ise);
                        cob.dup();
                        cob.invokespecial(ise, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.athrow();
                    });
            // package-private member: the reason this type needs an in-package proxy at all
            clb.withMethodBody("internalOnly", MethodTypeDesc.of(ConstantDescs.CD_String),
                    0, cob -> {
                        cob.ldc("internal");
                        cob.areturn();
                    });
        });
    }

    /** {@code public class <proxy> extends <bean> { public <proxy>() { super(); } }} — the co-located shape. */
    static byte[] proxyChainingNoArgCtor(String proxyName, String beanName) {
        var proxyDesc = ClassDesc.of(proxyName);
        var beanDesc = ClassDesc.of(beanName);
        return ClassFile.of().build(proxyDesc, clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC)
                .withSuperclass(beanDesc)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, cob -> {
                            cob.aload(0);
                            cob.invokespecial(beanDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                            cob.return_();
                        }));
    }
}
