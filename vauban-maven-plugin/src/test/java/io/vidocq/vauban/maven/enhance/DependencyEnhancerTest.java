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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 2 (issue #42): enhancing a modular dependency jar adds a co-located proxy, a provider, and a rewritten module-info. */
class DependencyEnhancerTest {

    private static final String PKG = "io/vidocq/vauban/maven/enhance/fixture/";

    @Test
    @DisplayName("warns that copying the jar is a last resort when an in-package proxy would do")
    void warnsWhenPlacementWouldHaveBeenEnough(@TempDir Path tmp) throws Exception {
        var fqn = Widget.class.getName();
        var sourceJar = tmp.resolve("widget.jar");
        writePlainJar(sourceJar, fqn);
        var warnings = new ArrayList<String>();

        DependencyEnhancer.enhance(sourceJar, "com.acme:widget:1.0", tmp.resolve("out"),
                List.of(fqn), Widget.class.getClassLoader(), warnings);

        var hint = warnings.stream().filter(w -> w.contains(fqn) && w.contains("class loader")).toList();
        assertEquals(1, hint.size(),
                "rewriting a copy of someone else's jar drops its signature, so the goal must say "
                        + "when the class loader could have shipped the same proxy instead: " + warnings);
        assertTrue(hint.get(0).contains("signature"),
                "the warning must name the cost that makes this goal a last resort: " + hint);
    }

    /** A jar holding just the fixture class, with no module-info and no signature. */
    private static void writePlainJar(Path jar, String fqn) throws Exception {
        var resource = fqn.replace('.', '/') + ".class";
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(resource));
            try (var in = Widget.class.getClassLoader().getResourceAsStream(resource)) {
                out.write(in.readAllBytes());
            }
            out.closeEntry();
        }
    }

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
        var result = DependencyEnhancer.enhance(srcJar, "org.example:libwidget:1.0",
                tmp.resolve("enhanced"), List.of(fqn), getClass().getClassLoader(), warnings);

        // Widget is exactly the case the class loader can place, so the goal now says so.
        // Nothing else may be reported: this path must stay clean.
        var unexpected = warnings.stream().filter(w -> !w.contains("only needs its client proxy")).toList();
        assertTrue(unexpected.isEmpty(), "no warning expected beyond the placement hint, got: " + unexpected);
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


    @Test
    @DisplayName("records provenance in the manifest and drops the inherited signature")
    void recordsProvenanceAndStripsSignature(@TempDir Path tmp) throws Exception {
        var fqn = Widget.class.getName();
        var srcJar = tmp.resolve("signed-libwidget.jar");
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("fixture.mod"),
                mb -> mb.requires(ModuleRequireInfo.of(
                        ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));
        byte[] widgetBytes;
        try (var is = getClass().getResourceAsStream("/" + PKG + "Widget.class")) {
            widgetBytes = is.readAllBytes();
        }
        // A signed jar: a manifest carrying per-entry digests, plus the signature block.
        var manifest = ("Manifest-Version: 1.0\r\nCreated-By: test\r\n\r\n"
                + "Name: " + PKG + "Widget.class\r\nSHA-256-Digest: bogus=\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var out = new JarOutputStream(Files.newOutputStream(srcJar))) {
            put(out, "META-INF/MANIFEST.MF", manifest);
            put(out, "META-INF/VENDOR.SF", "Signature-Version: 1.0\r\n".getBytes());
            put(out, "META-INF/VENDOR.RSA", new byte[] {1, 2, 3});
            put(out, "module-info.class", moduleInfo);
            put(out, PKG + "Widget.class", widgetBytes);
        }
        var expectedDigest = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(srcJar)));

        var warnings = new ArrayList<String>();
        var result = DependencyEnhancer.enhance(srcJar, "org.example:libwidget:1.0",
                tmp.resolve("enhanced"), List.of(fqn), getClass().getClassLoader(), warnings);

        try (var jar = new JarFile(result.enhancedJar().toFile())) {
            assertNull(jar.getEntry("META-INF/VENDOR.SF"),
                    "the inherited signature file must not be copied: the content it attests changed");
            assertNull(jar.getEntry("META-INF/VENDOR.RSA"), "the signature block must not be copied");

            var mf = jar.getManifest();
            assertNotNull(mf, "the enhanced jar must keep a manifest");
            assertEquals("org.example:libwidget:1.0",
                    mf.getMainAttributes().getValue("Vauban-Enhanced-From"),
                    "downstream tooling must be able to tell this is not the original artefact");
            assertEquals(expectedDigest, mf.getMainAttributes().getValue("Vauban-Enhanced-Digest"),
                    "the digest must pin the exact source artefact this copy was derived from");
            assertNotNull(mf.getMainAttributes().getValue("Vauban-Enhanced-By"));
            assertEquals("test", mf.getMainAttributes().getValue("Created-By"),
                    "the original main attributes must survive");
            assertTrue(mf.getEntries().isEmpty(),
                    "per-entry digests belong to the discarded signature, they must not survive");
        }
    }

    @Test
    @DisplayName("the enhanced jar resolves on a module path, and its layer serves the proxy and the provider")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void enhancedJarResolvesOnAModulePath(@TempDir Path tmp) throws Exception {
        var fqn = Widget.class.getName();
        var srcJar = tmp.resolve("libwidget.jar");
        // An ordinary third-party module: it exports and opens its package, as a library whose
        // types are meant to be injected must. The point here is the rewritten descriptor, not
        // the non-exported case (which the class loader places instead of rewriting).
        var pkg = java.lang.constant.PackageDesc.of(Widget.class.getPackageName());
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("fixture.mod"),
                mb -> mb.requires(ModuleRequireInfo.of(
                                ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))
                        .exports(pkg, 0)
                        .opens(pkg, 0)));
        byte[] widgetBytes;
        try (var is = getClass().getResourceAsStream("/" + PKG + "Widget.class")) {
            widgetBytes = is.readAllBytes();
        }
        try (var out = new JarOutputStream(Files.newOutputStream(srcJar))) {
            put(out, "module-info.class", moduleInfo);
            put(out, PKG + "Widget.class", widgetBytes);
        }

        var result = DependencyEnhancer.enhance(srcJar, "org.example:libwidget:1.0",
                tmp.resolve("enhanced"), List.of(fqn), getClass().getClassLoader(), new ArrayList<>());

        // Resolve it for real. Reading the descriptor proves it parses; only resolution proves the
        // module system accepts it — `requires io.vidocq.vauban.api` has to be satisfiable, and the
        // `provides` has to name a class the jar actually contains.
        var finder = java.lang.module.ModuleFinder.compose(
                java.lang.module.ModuleFinder.of(result.enhancedJar()),
                explicitModulesOnTestClassPath());
        var configuration = ModuleLayer.boot().configuration()
                .resolve(finder, java.lang.module.ModuleFinder.of(), List.of("fixture.mod"));
        // Parent loader: the platform loader, so nothing can be answered by the test's own
        // class path — what loads here came out of the enhanced jar.
        var layer = ModuleLayer.boot()
                .defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());

        var loader = layer.findLoader("fixture.mod");
        Class<?> widget = loader.loadClass(fqn);
        Class<?> proxy = loader.loadClass(fqn + "_ClientProxy");
        assertNotSame(Widget.class, widget, "the module must serve its own Widget, not the test's");
        assertSame(widget, proxy.getSuperclass(), "the proxy must link against the module's own type");
        assertEquals("fixture.mod", proxy.getModule().getName(),
                "the proxy belongs to the enhanced module, not to an unnamed module");
        assertNotNull(proxy.getDeclaredMethod("internalTag"),
                "the co-located proxy overrides the package-private member — the whole reason this "
                        + "jar was rewritten rather than proxied from the producer's package");

        // Delegation works through the module boundary: the proxy answers from the contextual
        // instance, and holds nothing itself.
        Object contextual = widget.getDeclaredConstructor().newInstance();
        Object instance = proxy.getDeclaredConstructor().newInstance();
        proxy.getMethod("$$setDelegate", java.util.function.Supplier.class)
                .invoke(instance, (java.util.function.Supplier<Object>) () -> contextual);
        assertEquals("widget", proxy.getMethod("describe").invoke(instance));

        // The rewritten `provides` is what the container reads: the generated provider must be
        // discoverable as a service of this layer.
        Class svc = loader.loadClass("io.vidocq.vauban.api.VaubanComponentProvider");
        java.util.ServiceLoader<?> services = java.util.ServiceLoader.load(layer, svc);
        var providers = services.stream().map(p -> p.type().getName()).toList();
        assertTrue(providers.contains(Widget.class.getPackageName() + "._VaubanComponents"),
                "the enhanced module must provide its generated component provider. Found: " + providers);
    }

    /**
     * The modular jars of the test class path, as a module path. This test runs on the class path
     * (the plugin has no module descriptor), so {@code io.vidocq.vauban.api} and everything it
     * requires must be handed to the resolver explicitly. Only jars that carry their own
     * {@code module-info} are taken: an automatic module derived from a build directory would
     * depend on a file name.
     */
    private static java.lang.module.ModuleFinder explicitModulesOnTestClassPath() {
        var modular = new ArrayList<Path>();
        for (var entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            var path = Path.of(entry);
            if (!entry.endsWith(".jar") || !Files.isRegularFile(path)) {
                continue;
            }
            try (var jar = new JarFile(path.toFile())) {
                if (jar.getEntry("module-info.class") != null) {
                    modular.add(path);
                }
            } catch (IOException ignored) {
                // not a readable jar: it cannot contribute a module either
            }
        }
        return java.lang.module.ModuleFinder.of(modular.toArray(Path[]::new));
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
