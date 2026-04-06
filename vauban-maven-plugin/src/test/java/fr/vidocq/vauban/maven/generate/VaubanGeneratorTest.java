package fr.vidocq.vauban.maven.generate;

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
