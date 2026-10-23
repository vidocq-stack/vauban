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

import static io.vidocq.vauban.maven.generate.GeneratorInputs.APPLICATION_SCOPED;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.DEPENDENT;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.PROVIDER_SERVICE;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.beanClass;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.compiled;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.jar;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.names;
import static io.vidocq.vauban.maven.generate.GeneratorInputs.writeClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.maven.generate.deps.Counter;
import io.vidocq.vauban.maven.generate.deps.Tally;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code vauban:generate} reads: dependency directories and jars with or without a beans list, files it skips or
 * cannot read, a missing class, and the providers it writes for a scanned jar's beans.
 */
class GeneratorInputScanningTest {

    private static final String BEAN = "com.scan.Service";
    private static final String LISTED = "com.listed.Known";
    private static final String BEANS_LIST = VaubanGenerator.BEANS_LIST_PATH;
    private static final String SCAN_CLASS = "com/scan/Service.class";
    private static final String EXTENSION_SERVICE = "META-INF/services/" + BuildCompatibleExtension.class.getName();

    @TempDir
    Path tmp;

    private GenerationResult generate(List<Path> dependencies, Path projectClasses) throws IOException {
        return VaubanGenerator.generate(new VaubanGenerator.Config(dependencies, projectClasses, tmp.resolve("output")));
    }

    private Path dependencyDirectory(String name) throws IOException {
        var dir = tmp.resolve(name);
        writeClass(dir, BEAN, beanClass(BEAN, DEPENDENT));
        return dir;
    }

    @Test
    void aDependencyDirectoryWithABeansListIsKnownNotScanned() throws IOException {
        var dir = dependencyDirectory("reactor-module");
        Files.createDirectories(dir.resolve(BEANS_LIST).getParent());
        Files.writeString(dir.resolve(BEANS_LIST), "# written by the processor\n" + LISTED + "\n");

        var result = generate(List.of(dir), null);

        assertEquals(List.of(LISTED), result.discoveredBeanClasses());
    }

    @Test
    void aDependencyDirectoryIsScannedSkippingWhatIsNoBeanClass() throws IOException {
        var dir = dependencyDirectory("plain-module");
        Files.write(dir.resolve("module-info.class"), new byte[] {1, 2, 3});
        Files.write(dir.resolve("com/scan/Service_ClientProxy.class"), new byte[] {1, 2, 3});
        Files.write(dir.resolve("com/scan/Broken.class"), new byte[] {1, 2, 3});

        var result = generate(List.of(dir), null);

        assertEquals(List.of(BEAN), result.discoveredBeanClasses());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().getFirst().startsWith("Failed to scan Broken.class: "), result.warnings().toString());
    }

    @Test
    void anUnreadableBeansListIsIgnoredAndTheDirectoryScanned() throws IOException {
        var dir = dependencyDirectory("garbled-module");
        Files.createDirectories(dir.resolve(BEANS_LIST).getParent());
        Files.write(dir.resolve(BEANS_LIST), new byte[] {(byte) 0xC3, (byte) 0x28, '\n'});

        var result = generate(List.of(dir), null);

        assertEquals(List.of(BEAN), result.discoveredBeanClasses());
    }

    @Test
    void aJarWithABeansListIsKnownNotScanned() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(BEANS_LIST, (LISTED + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put(SCAN_CLASS, beanClass(BEAN, APPLICATION_SCOPED));
        var lib = jar(tmp.resolve("processed.jar"), entries);

        var result = generate(List.of(lib), null);

        assertEquals(List.of(LISTED), result.discoveredBeanClasses());
    }

    @Test
    void aJarThatIsNoZipIsAWarning() throws IOException {
        var broken = Files.writeString(tmp.resolve("broken.jar"), "not a zip");

        var result = generate(List.of(broken), null);

        assertEquals(List.of(), result.discoveredBeanClasses());
        assertTrue(result.warnings().getFirst().startsWith("Failed to scan JAR broken.jar: "),
                result.warnings().toString());
    }

    @Test
    void aMissingProjectClassesDirectoryIsNoProject() throws IOException {
        var lib = jar(tmp.resolve("lib.jar"), Map.of(SCAN_CLASS, beanClass(BEAN, DEPENDENT)));

        var result = generate(List.of(lib), tmp.resolve("no-classes-yet"));

        assertEquals(List.of(BEAN), result.discoveredBeanClasses());
        assertFalse(Files.exists(tmp.resolve("output").resolve(PROVIDER_SERVICE)));
    }

    @Test
    void aProjectWithOnlyANestedBeanGetsNoProvider() throws IOException {
        var classes = tmp.resolve("classes");
        writeClass(classes, "com.app.Outer$Inner", beanClass("com.app.Outer$Inner", DEPENDENT));

        var result = generate(List.of(), classes);

        assertEquals(List.of("com.app.Outer$Inner"), result.discoveredBeanClasses());
        assertFalse(Files.exists(tmp.resolve("output").resolve(PROVIDER_SERVICE)));
    }

    @Test
    void aBeanClassTheClassLoaderCannotLoadIsSkippedWithAWarning() throws IOException {
        var lib = jar(tmp.resolve("ghost.jar"), Map.of("com/ghost/Ghost.class",
                beanClass("com.ghost.Ghost", APPLICATION_SCOPED)));

        var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(lib), null, tmp.resolve("output"),
                getClass().getClassLoader()));

        assertEquals(List.of(), result.generatedProxies());
        assertTrue(result.warnings().contains("Cannot load class for generation: com.ghost.Ghost"),
                result.warnings().toString());
    }

    @Test
    void anExtensionThatEnhancesNothingLeavesTheIndexAsItIs() throws IOException {
        var services = tmp.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve(EXTENSION_SERVICE), IdleExtension.class.getName());
        var lib = jar(tmp.resolve("lib.jar"), Map.of(SCAN_CLASS, beanClass(BEAN, DEPENDENT)));
        var output = tmp.resolve("output");

        GenerationResult result;
        try (var loader = new URLClassLoader(new URL[] {services.toUri().toURL()}, getClass().getClassLoader())) {
            result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(lib), null, output, loader));
        }

        assertEquals(List.of(BEAN), result.discoveredBeanClasses());
        assertFalse(Files.exists(output.resolve(
                io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH)));
    }

    @Test
    void aBrokenExtensionServiceEntryIsSkippedWithAWarningAndTheOthersStillRun() throws IOException {
        var services = tmp.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve(EXTENSION_SERVICE),
                "com.missing.AbsentExtension\n" + NamingExtension.class.getName() + "\n");
        var lib = jar(tmp.resolve("deps.jar"), Map.of("io/vidocq/vauban/maven/generate/deps/Tally.class",
                compiled(Tally.class)));
        var output = tmp.resolve("output");

        GenerationResult result;
        try (var loader = new URLClassLoader(new URL[] {services.toUri().toURL()}, getClass().getClassLoader())) {
            result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(lib), null, output, loader));
        }

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains(EXTENSION_SERVICE)
                && warning.contains("com.missing.AbsentExtension")), result.warnings().toString());
        assertEquals(List.of(Tally.class.getName()), result.discoveredBeanClasses());
        assertTrue(Files.exists(output.resolve(
                io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH)));
    }

    @Test
    void serviceFilesThatCannotBeListedStopTheDiscoveryWithOneWarning() throws IOException {
        var lib = jar(tmp.resolve("deps.jar"), Map.of("io/vidocq/vauban/maven/generate/deps/Tally.class",
                compiled(Tally.class)));
        var output = tmp.resolve("output");
        // ServiceLoader throws the same error on every hasNext() when the service files cannot be listed.
        var unlistable = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals(EXTENSION_SERVICE)) {
                    throw new IOException("service files cannot be listed");
                }
                return super.getResources(name);
            }
        };

        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),
                () -> VaubanGenerator.generate(new VaubanGenerator.Config(List.of(lib), null, output, unlistable)));

        assertEquals(1, result.warnings().stream().filter(warning -> warning.contains(EXTENSION_SERVICE)).count(),
                result.warnings().toString());
        assertEquals(List.of(Tally.class.getName()), result.discoveredBeanClasses());
    }

    @Test
    void aScannedJarGetsAProviderForItsTopLevelManagedBeansOnly() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("io/vidocq/vauban/maven/generate/deps/Counter.class", compiled(Counter.class));
        entries.put("io/vidocq/vauban/maven/generate/deps/Counter$Part.class", compiled(Counter.Part.class));
        entries.put("io/vidocq/vauban/maven/generate/deps/Tally.class", compiled(Tally.class));
        var lib = jar(tmp.resolve("deps.jar"), entries);
        var classes = tmp.resolve("classes");
        writeClass(classes, BEAN, beanClass(BEAN, DEPENDENT));
        var output = tmp.resolve("output");

        var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(lib), classes, output,
                getClass().getClassLoader(), true));

        var depsProvider = Counter.class.getPackageName() + "._VaubanComponents";
        assertEquals(List.of(depsProvider), result.generatedProviders());
        var created = names(output.resolve(depsProvider.replace('.', '/') + ".class"));
        assertTrue(created.contains(Counter.class.getName() + "_ClientProxy"), created.toString());
        assertTrue(created.contains(Tally.class.getName()), created.toString());
        assertFalse(created.contains(Tally.class.getName() + "_ClientProxy"), created.toString());
        assertFalse(created.contains(Counter.Part.class.getName()), created.toString());
        assertEquals("com.scan._VaubanComponents",
                Files.readString(output.resolve(PROVIDER_SERVICE), StandardCharsets.UTF_8).strip());
    }

    @Test
    void withoutAClassLoaderAJarProviderNamesNoProxyItCannotLoad() throws IOException {
        var lib = jar(tmp.resolve("deps.jar"), Map.of("io/vidocq/vauban/maven/generate/deps/Counter.class",
                compiled(Counter.class)));
        var output = tmp.resolve("output");

        var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(lib), null, output, null, true));

        var depsProvider = Counter.class.getPackageName() + "._VaubanComponents";
        assertEquals(List.of(depsProvider), result.generatedProviders());
        var created = names(output.resolve(depsProvider.replace('.', '/') + ".class"));
        assertTrue(created.contains(Counter.class.getName()), created.toString());
        assertFalse(created.contains(Counter.class.getName() + "_ClientProxy"), created.toString());
    }

    /** An extension that names every {@link Tally}. */
    public static class NamingExtension implements BuildCompatibleExtension {

        @Enhancement(types = Tally.class)
        public void enhance(ClassConfig clazz) {
            clazz.addAnnotation(jakarta.inject.Named.class);
        }
    }

    /** An extension whose enhancement matches no class of the archive. */
    public static class IdleExtension implements BuildCompatibleExtension {

        @Enhancement(types = Object.class, withAnnotations = jakarta.enterprise.inject.Alternative.class)
        public void enhance(ClassConfig clazz) {
            clazz.addAnnotation(jakarta.enterprise.context.RequestScoped.class);
        }
    }
}
