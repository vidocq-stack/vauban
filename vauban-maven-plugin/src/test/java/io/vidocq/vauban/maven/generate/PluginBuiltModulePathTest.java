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
package io.vidocq.vauban.maven.generate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: a module compiled without the Vauban processor, completed by {@code vauban:generate}
 * and booted on the module path with no {@code opens}. Its bean is bound only through a method it
 * declares (BUG-20261004-10), and its package is exported to the container only, so the container
 * creates the pre-generated {@code $$Intercepted} through the module's {@code _VaubanComponents}
 * or not at all (BUG-20261007-05).
 *
 * <p>The module is built the way a plugin user builds it: javac on the classes, then the generator
 * over the classes directory, then {@code module-info.java} alone, which names the provider the
 * generator wrote. The container and the Jakarta APIs come from this test's own class path, as
 * modules, in a layer of their own.</p>
 */
@DisplayName("vauban:generate — a plugin-built module on the module path, no opens")
class PluginBuiltModulePathTest {

    private static final String MODULE = "vauban.plugin.it";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a method-bound bean in a package exported to the container only is intercepted")
    void methodBoundBeanBootsWithoutOpens() throws Exception {
        var modules = modulesOnTheClassPath();
        var classes = buildModule(modules);

        var finder = ModuleFinder.of(Stream.concat(Stream.of(classes), modules.stream()).toArray(Path[]::new));
        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(MODULE));
        var layer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()),
                ClassLoader.getPlatformClassLoader()).layer();
        var loader = layer.findLoader(MODULE);

        var service = loader.loadClass("vauban.plugin.it.api.Service");
        var calls = (List<?>) loader.loadClass("vauban.plugin.it.api.Calls").getField("LOG").get(null);
        var bean = loader.loadClass("vauban.plugin.it.beans.MethodBoundService");
        var interceptor = loader.loadClass("vauban.plugin.it.beans.AuditInterceptor");
        var containerType = loader.loadClass("io.vidocq.vauban.core.container.VaubanContainer");

        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            var builder = containerType.getMethod("builder").invoke(null);
            var add = builder.getClass().getMethod("addBeanClass", Class.class);
            add.invoke(builder, interceptor);
            add.invoke(builder, bean);
            try (var container = (AutoCloseable) builder.getClass().getMethod("build").invoke(builder)) {
                var instance = containerType.getMethod("select", Class.class).invoke(container, bean);
                assertEquals(bean.getName() + "$$Intercepted", instance.getClass().getName());
                assertEquals("wrote x", service.getMethod("write", String.class).invoke(instance, "x"));
                assertEquals(List.of("write"), calls);
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("an extension gets a managed class's lookup from the plugin-written provider of its package")
    void pluginWrittenProviderGrantsTheModuleLookup() throws Throwable {
        var modules = modulesOnTheClassPath();
        var classes = buildModule(modules);

        var finder = ModuleFinder.of(Stream.concat(Stream.of(classes), modules.stream()).toArray(Path[]::new));
        var configuration = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(MODULE));
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()),
                ClassLoader.getPlatformClassLoader());
        var layer = controller.layer();
        var loader = layer.findLoader(MODULE);
        var core = layer.findModule("io.vidocq.vauban.core").orElseThrow();
        // Test-only: ModuleLookups is exported to the trusted extension modules alone.
        controller.addExports(core, "io.vidocq.vauban.core.access", PluginBuiltModulePathTest.class.getModule());

        var bean = loader.loadClass("vauban.plugin.it.beans.MethodBoundService");
        var interceptor = loader.loadClass("vauban.plugin.it.beans.AuditInterceptor");
        assertTrue(!bean.getModule().isOpen(bean.getPackageName(), core), "nothing is opened to the container");
        var containerType = loader.loadClass("io.vidocq.vauban.core.container.VaubanContainer");
        var lookupFor = loader.loadClass("io.vidocq.vauban.core.access.ModuleLookups").getMethod("lookupFor", Class.class);

        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            var builder = containerType.getMethod("builder").invoke(null);
            var add = builder.getClass().getMethod("addBeanClass", Class.class);
            add.invoke(builder, interceptor);
            add.invoke(builder, bean);
            try (var container = (AutoCloseable) builder.getClass().getMethod("build").invoke(builder)) {
                var lookup = ((java.util.Optional<?>) lookupFor.invoke(null, bean))
                        .map(java.lang.invoke.MethodHandles.Lookup.class::cast).orElseThrow();
                assertEquals(bean, lookup.lookupClass());
                assertTrue(lookup.hasFullPrivilegeAccess());
                var secret = lookup.findSpecial(bean, "secret",
                        java.lang.invoke.MethodType.methodType(String.class, String.class), bean);
                var instance = containerType.getMethod("select", Class.class).invoke(container, bean);
                assertEquals("secret of duke", (String) secret.invoke(instance, "duke"));
            }
            assertTrue(((java.util.Optional<?>) lookupFor.invoke(null, bean)).isEmpty(), "withdrawn at shutdown");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Compiles the fixture module, runs the generator over it, then compiles its descriptor. */
    private Path buildModule(List<Path> modules) throws Exception {
        var fixture = Path.of(PluginBuiltModulePathTest.class.getResource("/plugin-module-it").toURI());
        var classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        var modulePath = String.join(File.pathSeparator, modules.stream().map(Path::toString).toList());

        List<String> sources;
        try (var files = Files.walk(fixture.resolve("src"))) {
            sources = files.filter(f -> f.toString().endsWith(".java")).map(Path::toString).toList();
        }
        var options = new ArrayList<>(List.of("-proc:none", "-d", classes.toString(), "-classpath", modulePath));
        options.addAll(sources);
        javac(options);

        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
                PluginBuiltModulePathTest.class.getClassLoader())) {
            var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, classes, loader));
            assertTrue(result.generatedInterceptors().contains("vauban.plugin.it.beans.MethodBoundService$$Intercepted"),
                    "generated: " + result.generatedInterceptors() + ", warnings: " + result.warnings());
        }

        javac(List.of("-d", classes.toString(), "--module-path", modulePath,
                "--patch-module", MODULE + "=" + classes,
                fixture.resolve("module-info/module-info.java").toString()));
        return classes;
    }

    private static void javac(List<String> options) {
        var out = new ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, out, out, options.toArray(String[]::new));
        assertEquals(0, rc, "javac " + options + "\n" + out.toString(StandardCharsets.UTF_8));
    }

    /**
     * The explicit modules on this test's class path: the container, its dependencies and the
     * Jakarta APIs, each a jar or a classes directory holding a {@code module-info.class}.
     */
    private static List<Path> modulesOnTheClassPath() throws Exception {
        var found = new LinkedHashSet<Path>();
        for (var url : Collections.list(PluginBuiltModulePathTest.class.getClassLoader()
                .getResources("module-info.class"))) {
            // Only class-path entries: the JDK's own modules come from the boot layer.
            if (url.getProtocol().equals("jar") || url.getProtocol().equals("file")) found.add(locationOf(url));
        }
        return List.copyOf(found);
    }

    private static Path locationOf(java.net.URL url) throws URISyntaxException {
        var text = url.toString();
        if (text.startsWith("jar:")) {
            return Path.of(new java.net.URI(text.substring(4, text.indexOf("!/"))));
        }
        return Path.of(url.toURI()).getParent();
    }
}
