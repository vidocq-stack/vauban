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

import static io.vidocq.vauban.maven.generate.GeneratorInputs.DEPENDENT;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.PROVIDER_SERVICE;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.copyPackage;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.producerHolder;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.names;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.writeClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.maven.enhance.fixture.Widget;
import io.vidocq.vauban.maven.generate.paths.Gadget;
import io.vidocq.vauban.maven.generate.paths.Hidden;
import io.vidocq.vauban.maven.generate.paths.Workshop;
import io.vidocq.vauban.maven.generate.produceronly.Outlet;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client proxies, interceptor subclasses, producer proxies and providers {@code vauban:generate} writes for a
 * project's own classes, what it skips, and the warnings it gives when it cannot write them.
 */
class ProducerProxyAndProviderGenerationTest {

    private static final String WORKSHOP = Workshop.class.getName();
    private static final String PROVIDER = "_VaubanComponents";
    private static final String PROXY_SUFFIX = "_ClientProxy.class";

    @TempDir
    Path tmp;

    /** The test class loader, except that {@link Hidden} is missing, as a type absent from the plugin's class path. */
    private static ClassLoader withoutHidden() {
        return new ClassLoader(ProducerProxyAndProviderGenerationTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(Hidden.class.getName())) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private GenerationResult generateWorkshop(Path output) throws IOException {
        var classes = copyPackage(Workshop.class, tmp.resolve("classes"));
        return VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, withoutHidden()));
    }

    private static List<String> proxyFiles(Path packageDir) throws IOException {
        try (Stream<Path> files = Files.list(packageDir)) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(PROXY_SUFFIX))
                    .sorted().toList();
        }
    }

    private static Path packageDir(Path output, Class<?> anchor) {
        return output.resolve(anchor.getPackageName().replace('.', '/'));
    }

    @Test
    void eachProducerGetsTheProxyItsTypeAllows() throws IOException {
        var output = tmp.resolve("output");

        var result = generateWorkshop(output);

        assertEquals(List.of(WORKSHOP + "_ClientProxy"), result.generatedProxies());
        assertEquals(List.of(WORKSHOP + "$$Intercepted"), result.generatedInterceptors());
        // One proxy for the two producers of Gadget, none for the interface, the hidden type or the @Dependent value.
        var proxies = proxyFiles(packageDir(output, Workshop.class));
        assertEquals(2, proxies.size(), proxies.toString());
        assertTrue(proxies.getFirst().startsWith("Gadget$$"), proxies.toString());
        assertEquals("Workshop" + PROXY_SUFFIX, proxies.get(1));
        var gadgetProxy = Workshop.class.getPackageName() + "." + proxies.getFirst().replace(".class", "");
        var provider = packageDir(output, Workshop.class).resolve(PROVIDER + ".class");
        assertTrue(names(provider).contains(gadgetProxy), "the provider creates the producer proxy");
        assertFalse(names(provider).contains(Workshop.Bench.class.getName()), "a nested bean keeps reflection");
        assertEquals(Workshop.class.getPackageName() + "." + PROVIDER,
                Files.readString(output.resolve(PROVIDER_SERVICE), StandardCharsets.UTF_8).strip());
    }

    @Test
    void aSecondRunWritesNothingAgain() throws IOException {
        var output = tmp.resolve("output");
        generateWorkshop(output);
        var proxiesBefore = proxyFiles(packageDir(output, Workshop.class));

        var classes = tmp.resolve("classes");
        var again = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, withoutHidden()));

        assertEquals(List.of(), again.generatedProxies());
        assertEquals(List.of(), again.generatedInterceptors());
        assertEquals(proxiesBefore, proxyFiles(packageDir(output, Workshop.class)));
    }

    @Test
    void anOutputThatCannotTakeTheClassesReportsEachFailure() throws IOException {
        var output = tmp.resolve("output");
        var blockedPackage = packageDir(output, Workshop.class);
        Files.createDirectories(blockedPackage.getParent());
        Files.writeString(blockedPackage, "a file where the package directory should be");
        Files.createDirectories(output.resolve("META-INF"));
        Files.writeString(output.resolve("META-INF/vauban"), "a file where the placed proxies should be");

        var result = generateWorkshop(output);

        assertEquals(List.of(), result.generatedProxies());
        assertEquals(List.of(), result.generatedInterceptors());
        for (var expected : List.of(
                "Failed to generate proxy for " + WORKSHOP + ": ",
                "Failed to generate interceptor subclass for " + WORKSHOP + ": ",
                "Failed to generate producer proxy for " + Gadget.class.getName() + ": ",
                "Failed to ship the in-package proxy of " + Widget.class.getName() + ": ",
                "Producer of " + Widget.class.getName() + " is not build-time proxyable",
                "Failed to generate component provider for package " + Workshop.class.getPackageName() + ": ")) {
            assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith(expected)),
                    "expected a warning starting with '" + expected + "' in " + result.warnings());
        }
        assertFalse(Files.exists(output.resolve(PROVIDER_SERVICE)), "no provider was written, so none is listed");
    }

    @Test
    void aPackageWithOnlyAProducerGetsAProviderForItsProxy() throws IOException {
        var classes = copyPackage(Outlet.class, tmp.resolve("classes"));
        var output = tmp.resolve("output");

        VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, withoutHidden()));

        var provider = packageDir(output, Outlet.class).resolve(PROVIDER + ".class");
        var proxies = proxyFiles(packageDir(output, Outlet.class)).stream()
                .filter(name -> name.startsWith("Gadget$$")).toList();
        assertEquals(1, proxies.size(), proxies.toString());
        assertTrue(names(provider).contains(Outlet.class.getPackageName() + "." + proxies.getFirst()
                .replace(".class", "")));
        assertEquals(Outlet.class.getPackageName() + "." + PROVIDER,
                Files.readString(output.resolve(PROVIDER_SERVICE), StandardCharsets.UTF_8).strip());
    }

    @Test
    void aServiceFileThatCannotBeWrittenIsAWarning() throws IOException {
        var classes = copyPackage(Outlet.class, tmp.resolve("classes"));
        var output = tmp.resolve("output");
        Files.createDirectories(output.resolve(PROVIDER_SERVICE));

        var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, withoutHidden()));

        assertTrue(Files.isRegularFile(packageDir(output, Outlet.class).resolve(PROVIDER + ".class")));
        assertTrue(result.warnings().stream()
                        .anyMatch(warning -> warning.startsWith("Failed to write component provider service file: ")),
                result.warnings().toString());
    }

    @Test
    void aProducerInTheDefaultPackageGetsAProviderThere() throws Exception {
        var classes = tmp.resolve("classes");
        writeClass(classes, "Outer$Holder", producerHolder("Outer$Holder", DEPENDENT, Gadget.class));
        var output = tmp.resolve("output");

        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, loader));
        }

        assertTrue(Files.isRegularFile(output.resolve(PROVIDER + ".class")));
        assertEquals(PROVIDER, Files.readString(output.resolve(PROVIDER_SERVICE), StandardCharsets.UTF_8).strip());
    }

    @Test
    void aProducerOnlyProviderThatCannotBeWrittenIsAWarning() throws Exception {
        var classes = tmp.resolve("classes");
        writeClass(classes, "Outer$Holder", producerHolder("Outer$Holder", DEPENDENT, Gadget.class));
        var output = tmp.resolve("output");
        Files.createDirectories(output.resolve(PROVIDER + ".class"));

        GenerationResult result;
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, loader));
        }

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.startsWith(
                "Failed to generate producer-only component provider for package : ")), result.warnings().toString());
        assertFalse(Files.exists(output.resolve(PROVIDER_SERVICE)));
    }
}
