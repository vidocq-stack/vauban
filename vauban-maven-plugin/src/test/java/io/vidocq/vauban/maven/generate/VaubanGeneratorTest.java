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

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.Annotation;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanGenerator — build-time CDI bean discovery")
class VaubanGeneratorTest {

    private static final ClassDesc CD_APP_SCOPED =
            ClassDesc.of("jakarta.enterprise.context.ApplicationScoped");
    private static final ClassDesc CD_DEPENDENT =
            ClassDesc.of("jakarta.enterprise.context.Dependent");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("discovers @ApplicationScoped beans from a JAR")
    void shouldDiscoverBeansFromJar() throws IOException {
        // Create a JAR with an @ApplicationScoped bean
        var jarPath = createTestJar("test-lib.jar",
                new TestClass("com.example.GreetingService", CD_APP_SCOPED),
                new TestClass("com.example.Utils", null) // no CDI annotation
        );

        var outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);

        var config = new VaubanGenerator.Config(List.of(jarPath), null, outputDir);
        var result = VaubanGenerator.generate(config);

        // Only the @ApplicationScoped bean should be discovered
        assertEquals(1, result.discoveredBeanClasses().size());
        assertTrue(result.discoveredBeanClasses().contains("com.example.GreetingService"));
        assertFalse(result.discoveredBeanClasses().contains("com.example.Utils"));
    }

    @Test
    @DisplayName("writes META-INF/vauban-beans.list with discovered beans")
    void shouldWriteBeansListFile() throws IOException {
        var jarPath = createTestJar("lib.jar",
                new TestClass("com.example.ServiceA", CD_APP_SCOPED),
                new TestClass("com.example.ServiceB", CD_DEPENDENT)
        );

        var outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);

        var config = new VaubanGenerator.Config(List.of(jarPath), null, outputDir);
        VaubanGenerator.generate(config);

        var beansListFile = outputDir.resolve(VaubanGenerator.BEANS_LIST_PATH);
        assertTrue(Files.exists(beansListFile), "vauban-beans.list should exist");

        var lines = Files.readAllLines(beansListFile, StandardCharsets.UTF_8);
        // First line is a comment
        assertTrue(lines.getFirst().startsWith("#"));
        // Bean names should be present (sorted)
        assertTrue(lines.contains("com.example.ServiceA"));
        assertTrue(lines.contains("com.example.ServiceB"));
    }

    @Test
    @DisplayName("scans project classes directory")
    void shouldScanProjectClassesDir() throws IOException {
        // Write a .class file directly to a directory (simulating target/classes)
        var classesDir = tempDir.resolve("classes");
        var classBytes = generateClassWithAnnotation("com.myapp.MyBean", CD_APP_SCOPED);
        var classFile = classesDir.resolve("com/myapp/MyBean.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, classBytes);

        var outputDir = tempDir.resolve("output");
        var config = new VaubanGenerator.Config(List.of(), classesDir, outputDir);
        var result = VaubanGenerator.generate(config);

        assertEquals(1, result.discoveredBeanClasses().size());
        assertTrue(result.discoveredBeanClasses().contains("com.myapp.MyBean"));
    }

    @Test
    @DisplayName("merges beans from multiple JARs")
    void shouldMergeMultipleJars() throws IOException {
        var jar1 = createTestJar("lib-a.jar",
                new TestClass("com.a.ServiceA", CD_APP_SCOPED));
        var jar2 = createTestJar("lib-b.jar",
                new TestClass("com.b.ServiceB", CD_DEPENDENT));

        var outputDir = tempDir.resolve("output");
        var config = new VaubanGenerator.Config(List.of(jar1, jar2), null, outputDir);
        var result = VaubanGenerator.generate(config);

        assertEquals(2, result.discoveredBeanClasses().size());
        assertTrue(result.discoveredBeanClasses().contains("com.a.ServiceA"));
        assertTrue(result.discoveredBeanClasses().contains("com.b.ServiceB"));
    }

    @Test
    @DisplayName("handles empty JAR list gracefully")
    void shouldHandleEmptyInput() throws IOException {
        var outputDir = tempDir.resolve("output");
        var config = new VaubanGenerator.Config(List.of(), null, outputDir);
        var result = VaubanGenerator.generate(config);

        assertTrue(result.discoveredBeanClasses().isEmpty());
        assertFalse(Files.exists(outputDir.resolve(VaubanGenerator.BEANS_LIST_PATH)));
    }

    @Test
    @DisplayName("warns about non-existent JARs")
    void shouldWarnAboutMissingJars() throws IOException {
        var missingJar = tempDir.resolve("nonexistent.jar");
        var outputDir = tempDir.resolve("output");
        var config = new VaubanGenerator.Config(List.of(missingJar), null, outputDir);
        var result = VaubanGenerator.generate(config);

        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().getFirst().contains("non-existent"));
    }

    @Test
    @DisplayName("generates deterministic client proxy .class for normal-scoped beans")
    void shouldGenerateProxyForNormalScopedBeans() throws Exception {
        // Write .class files to a directory to create a ClassLoader
        var classesDir = tempDir.resolve("classes");
        writeClassToDir(classesDir, "com.proxy.NormalBean", CD_APP_SCOPED);
        writeClassToDir(classesDir, "com.proxy.DependentBean", CD_DEPENDENT);

        // Also create a JAR with the same classes (for scanning)
        var jarPath = createTestJar("proxy-lib.jar",
                new TestClass("com.proxy.NormalBean", CD_APP_SCOPED),
                new TestClass("com.proxy.DependentBean", CD_DEPENDENT));

        var outputDir = tempDir.resolve("output");
        var cl = new java.net.URLClassLoader(new java.net.URL[]{classesDir.toUri().toURL()});

        var config = new VaubanGenerator.Config(List.of(jarPath), null, outputDir, cl);
        var result = VaubanGenerator.generate(config);

        // Proxy generated only for normal-scoped (@ApplicationScoped) bean
        assertEquals(1, result.generatedProxies().size());
        assertTrue(result.generatedProxies().getFirst().contains("NormalBean"));
        assertTrue(result.generatedProxies().getFirst().endsWith("_ClientProxy"));

        // Verify .class file exists
        var proxyClassFile = outputDir.resolve("com/proxy/NormalBean_ClientProxy.class");
        assertTrue(Files.exists(proxyClassFile), "Proxy .class file should be generated");
        assertTrue(Files.size(proxyClassFile) > 0, "Proxy .class file should not be empty");

        // No proxy for @Dependent (not normal-scoped)
        assertTrue(result.generatedProxies().stream()
                .noneMatch(n -> n.contains("DependentBean")));
    }

    @Test
    @DisplayName("proxy naming is deterministic across multiple runs")
    void shouldHaveDeterministicProxyNames() throws Exception {
        var classesDir = tempDir.resolve("classes");
        writeClassToDir(classesDir, "com.det.MyService", CD_APP_SCOPED);

        var jarPath = createTestJar("det-lib.jar",
                new TestClass("com.det.MyService", CD_APP_SCOPED));

        var cl = new java.net.URLClassLoader(new java.net.URL[]{classesDir.toUri().toURL()});

        var output1 = tempDir.resolve("run1");
        var output2 = tempDir.resolve("run2");

        var result1 = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(jarPath), null, output1, cl));
        var result2 = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(jarPath), null, output2, cl));

        // Same name in both runs
        assertEquals(result1.generatedProxies(), result2.generatedProxies());
        assertEquals("com.det.MyService_ClientProxy", result1.generatedProxies().getFirst());
    }

    @Test
    @DisplayName("BCE @Enhancement enriches non-CDI classes from dependency JARs")
    void shouldEnrichNonCdiBeanViaBceEnhancement() throws Exception {
        var outputDir = tempDir.resolve("output");
        var result = generateWithNamedScopeBce(outputDir);

        // HelloResource should be discovered as a bean (promoted by BCE @Enhancement)
        assertTrue(result.discoveredBeanClasses().contains("com.external.HelloResource"),
                "BCE-enriched @Named class should be in beans list. Found: " + result.discoveredBeanClasses());

        // PlainHelper should NOT be a bean (no annotation)
        assertFalse(result.discoveredBeanClasses().contains("com.external.PlainHelper"),
                "PlainHelper without any annotation should not be a bean");

        // Proxy should be generated (RequestScoped is normal-scoped)
        assertTrue(result.generatedProxies().stream()
                        .anyMatch(p -> p.contains("HelloResource")),
                "Client proxy should be generated for promoted @RequestScoped bean. Proxies: " + result.generatedProxies());

        // Brique A for the plugin: the @Enhancement result must also be frozen as a static patch,
        // so the runtime applies the scope without replaying the BCE reflectively.
        var patchFile = outputDir.resolve(
                io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH);
        assertTrue(Files.exists(patchFile), "enhancement patch should be written by the plugin");
        try (var is = Files.newInputStream(patchFile)) {
            var patch = io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.read(is);
            assertTrue(patch.getOrDefault("com.external.HelloResource", List.of())
                            .contains("jakarta.enterprise.context.RequestScoped"),
                    "patch must record the BCE-added @RequestScoped on HelloResource: " + patch);
        }
    }

    @Test
    @DisplayName("the extensions the plugin runs know they run at build time (ravel#21)")
    void extensionsRunByThePluginKnowTheyRunAtBuildTime() throws Exception {
        TestNamedScopeBce.SAW_BUILD_TIME.set(false);

        generateWithNamedScopeBce(tempDir.resolve("output"));

        assertTrue(TestNamedScopeBce.SAW_BUILD_TIME.get(),
                "the @Enhancement the plugin runs must see ExtensionPhase.isBuildTime()");
    }

    /** Generates for a dependency JAR whose @Named class the test BCE turns into a @RequestScoped bean. */
    private GenerationResult generateWithNamedScopeBce(Path outputDir) throws Exception {
        // Create a JAR with a @Named class (no CDI scope) — simulates an external JAR
        // Using @Named as trigger because jakarta.ws.rs.Path is not on the maven-plugin classpath
        var namedCD = ClassDesc.of("jakarta.inject.Named");
        var jarPath = createTestJar("external-lib.jar",
                new TestClass("com.external.HelloResource", namedCD),
                new TestClass("com.external.PlainHelper", null)
        );

        // Also write classes to a directory for ClassLoader
        var classesDir = tempDir.resolve("classes");
        writeClassToDir(classesDir, "com.external.HelloResource", namedCD);
        writeClassToDir(classesDir, "com.external.PlainHelper", null);

        // Create a BCE service file in the classesDir so ServiceLoader finds it
        var svcDir = classesDir.resolve("META-INF/services");
        Files.createDirectories(svcDir);
        Files.writeString(svcDir.resolve(
                "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension"),
                TestNamedScopeBce.class.getName());

        // ClassLoader must include test classes (for BCE) + generated classes
        var testClassesUrl = TestNamedScopeBce.class.getProtectionDomain().getCodeSource().getLocation();
        var cl = new java.net.URLClassLoader(new java.net.URL[]{
                classesDir.toUri().toURL(), testClassesUrl});

        var config = new VaubanGenerator.Config(List.of(jarPath), null, outputDir, cl);
        return VaubanGenerator.generate(config);
    }

    @Test
    @DisplayName("generates a _VaubanComponents provider .class + service file for project no-arg beans")
    void shouldGenerateComponentProviderForProjectBeans() throws Exception {
        var classesDir = tempDir.resolve("classes");
        writeClassToDir(classesDir, "com.myapp.MyResource", CD_APP_SCOPED);

        var outputDir = tempDir.resolve("output");
        var config = new VaubanGenerator.Config(List.of(), classesDir, outputDir);
        VaubanGenerator.generate(config);

        var providerClass = outputDir.resolve("com/myapp/_VaubanComponents.class");
        assertTrue(Files.exists(providerClass), "_VaubanComponents.class should be generated in-module");
        assertTrue(Files.size(providerClass) > 0, "provider .class must not be empty");

        var svc = outputDir.resolve("META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider");
        assertTrue(Files.exists(svc), "class-path service file should be written");
        assertEquals("com.myapp._VaubanComponents", Files.readString(svc, StandardCharsets.UTF_8).strip());
    }

    /**
     * BCE de test : ajoute @RequestScoped aux classes @Named sans scope CDI.
     * Utilise @Named car jakarta.ws.rs.Path n'est pas sur le classpath maven-plugin.
     */
    public static class TestNamedScopeBce
            implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {
        static final java.util.concurrent.atomic.AtomicBoolean SAW_BUILD_TIME =
                new java.util.concurrent.atomic.AtomicBoolean();

        @jakarta.enterprise.inject.build.compatible.spi.Enhancement(
                types = Object.class,
                withAnnotations = jakarta.inject.Named.class)
        public void addScope(jakarta.enterprise.inject.build.compatible.spi.ClassConfig clazz) {
            SAW_BUILD_TIME.set(io.vidocq.vauban.api.ExtensionPhase.isBuildTime());
            clazz.addAnnotation(jakarta.enterprise.context.RequestScoped.class);
        }
    }

    @Test
    @DisplayName("writes a _VaubanComponents per package of a scanned jar, listed in no service file")
    void shouldGenerateProvidersForScannedJarBeans() throws IOException {
        var jarPath = createTestJar("dep-lib.jar",
                new TestClass("org.dep.lib.Service", CD_DEPENDENT),
                new TestClass("org.dep.lib.Helper", null));
        var outputDir = tempDir.resolve("dep-output");
        Files.createDirectories(outputDir);

        var result = VaubanGenerator.generate(
                new VaubanGenerator.Config(List.of(jarPath), null, outputDir, null, true));

        assertEquals(List.of("org.dep.lib._VaubanComponents"), result.generatedProviders());
        assertTrue(Files.isRegularFile(outputDir.resolve("org/dep/lib/_VaubanComponents.class")));
        assertFalse(Files.exists(outputDir.resolve(
                        "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider")),
                "a dependency's providers are declared by its own module, not by the project");
    }

    @Test
    @DisplayName("writes no provider for a scanned jar by default: vauban:generate is unchanged")
    void shouldNotGenerateProvidersForScannedJarByDefault() throws IOException {
        var jarPath = createTestJar("dep-lib2.jar", new TestClass("org.dep.lib2.Service", CD_DEPENDENT));
        var outputDir = tempDir.resolve("dep-output2");
        Files.createDirectories(outputDir);

        var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(jarPath), null, outputDir));

        assertEquals(List.of(), result.generatedProviders());
        assertFalse(Files.exists(outputDir.resolve("org/dep/lib2/_VaubanComponents.class")));
    }

    private void writeClassToDir(Path classesDir, String className, ClassDesc annotation) throws IOException {
        var classBytes = generateClassWithAnnotation(className, annotation);
        var classFile = classesDir.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, classBytes);
    }

    // --- Test helpers ---

    private record TestClass(String className, ClassDesc annotationDesc) {}

    private Path createTestJar(String name, TestClass... classes) throws IOException {
        var jarPath = tempDir.resolve(name);
        try (var jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            for (var tc : classes) {
                var classBytes = generateClassWithAnnotation(tc.className(), tc.annotationDesc());
                var entryName = tc.className().replace('.', '/') + ".class";
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(classBytes);
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    /**
     * Generate a minimal .class file using JDK 25 Class-File API.
     * If annotationDesc is non-null, the class will be annotated with it.
     */
    private static byte[] generateClassWithAnnotation(String className, ClassDesc annotationDesc) {
        var classDesc = ClassDesc.of(className);
        return ClassFile.of().build(classDesc, clb -> {
            clb.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);

            // Add annotation if specified
            if (annotationDesc != null) {
                clb.with(RuntimeVisibleAnnotationsAttribute.of(
                        Annotation.of(annotationDesc)
                ));
            }

            // No-arg constructor
            clb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    java.lang.classfile.ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
        });
    }
}
