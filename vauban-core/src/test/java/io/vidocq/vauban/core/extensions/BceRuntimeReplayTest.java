package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.extensions.testfixtures.PathLike;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.spi.Bean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED tests for bug #7 — runtime part (BCE replay).
 *
 * <h2>Tested contract</h2>
 * <ol>
 *   <li>At startup, {@code VaubanContainerBuilder} must read
 *       {@code META-INF/vauban-bce-runtime.list} across the whole classpath.</li>
 *   <li>For each {@code (bceFQN, classFQN)} pair: resolve the BCE via
 *       ServiceLoader and replay its {@code @Enhancement} phase only on
 *       the targeted class.</li>
 *   <li>The index is rebuilt with the added annotations ->
 *       {@code BeanManager.getBeans(TestResource.class)} sees the class
 *       with its enriched scope.</li>
 *   <li>The short-circuit {@code allSourcesProcessed -> bceClasses = List.of()}
 *       must be replaced by a targeted read of the runtime-list.</li>
 * </ol>
 *
 * <h2>Classpath setup</h2>
 * The classes {@code TestResourceA} / {@code TestResourceB} are synthesized
 * via the <b>JDK 25 Class-File API</b> ({@code java.lang.classfile.*}) with
 * the {@code @PathLike} annotation as their only content. This reproduces the
 * bug #7 scenario: a pre-processed JAR where the APT inscribed the BCE in the
 * runtime-list but did NOT rewrite the bytecode (an APT/JSR-269 conformant pattern).
 *
 * Advantage over {@code javax.tools.JavaCompiler}: no need for
 * {@code requires java.compiler} in the production module, consistent
 * with the rest of Vauban which generates all its bytecode via the Class-File API.
 *
 * Then everything is packed into a JAR:
 * <ul>
 *   <li>{@code TestResource.class}</li>
 *   <li>{@code META-INF/vauban-beans.list}</li>
 *   <li>{@code META-INF/vauban-bce-processed} (marker)</li>
 *   <li>{@code META-INF/vauban-bce-runtime.list}
 *       (the new pivot file of bug #7)</li>
 *   <li>{@code META-INF/services/...BuildCompatibleExtension} listing the BCE</li>
 * </ul>
 */
@DisplayName("BCE runtime replay - reading vauban-bce-runtime.list")
class BceRuntimeReplayTest {

    private static final String RUNTIME_LIST_PATH = "META-INF/vauban-bce-runtime.list";
    private static final String BEANS_LIST_PATH   = "META-INF/vauban-beans.list";
    private static final String BCE_MARKER_PATH   = "META-INF/vauban-bce-processed";
    private static final String BCE_SERVICE_PATH  =
            "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension";

    private static final ClassDesc CD_PathLike = ClassDesc.of(PathLike.class.getName());
    private static final MethodTypeDesc MTD_void = MethodTypeDesc.of(ConstantDescs.CD_void);

    @TempDir
    Path tempDir;

    // ---- Annotation trigger ----
    // See {@link io.vidocq.vauban.core.extensions.testfixtures.PathLike}.
    // Extracted to top-level to get a stable ClassDesc
    // (a descriptor without '$') and to be resolvable from any ClassLoader.

    // ---- Test BCE: adds @RequestScoped to @PathLike classes ----

    public static class TestScopeBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = PathLike.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(RequestScoped.class);
        }
    }

    // ======================================================================
    // Test 2 - Runtime replays the BCE from the list and applies the scope
    // ======================================================================

    @Nested
    @DisplayName("Test 2 - BCE replay → RequestScoped scope applied")
    class ReplayFromRuntimeList {

        @Test
        @DisplayName("BeanManager.getBeans(TestResource.class) returns a bean with scope @RequestScoped")
        void shouldReplayBceAndApplyRequestScopedAtRuntime() throws Exception {
            // Pre-processed JAR: TestResource with @PathLike ALONE in the bytecode
            var jar = buildPreProcessedJar(
                    tempDir.resolve("resource.jar"),
                    "app.TestResource",
                    /*beansList*/       List.of("app.TestResource"),
                    /*runtimeList*/     List.of(
                            TestScopeBce.class.getName() + ";app.TestResource"),
                    /*includeBceClass*/ true,
                    /*includeService*/  true);

            try (var cl = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()},
                    getClass().getClassLoader())) {
                var testResourceClass = Class.forName("app.TestResource", true, cl);
                assertFalse(isAnnotationOnBytecode(testResourceClass, RequestScoped.class),
                        "Precondition: @RequestScoped MUST NOT be in the raw bytecode "
                                + "(APT must NOT rewrite the .class, in accordance with the solution)");

                try (var container = VaubanContainer.builder()
                        .classLoader(cl)
                        .scanClasspath()
                        .build()) {

                    var bm = container.getBeanManager();
                    var beans = bm.getBeans(testResourceClass);

                    assertFalse(beans.isEmpty(),
                            "BeanManager should expose the pre-processed TestResource as a bean"
                                    + " after replaying BCE from vauban-bce-runtime.list");

                    Bean<?> bean = beans.iterator().next();
                    assertEquals(RequestScoped.class, bean.getScope(),
                            "BCE must be replayed at runtime and @RequestScoped must be applied");
                }
            }
        }
    }

    // ======================================================================
    // Test 3 - exact symptom of bug #7: getBeans(Object.class, @Any)
    // ======================================================================

    @Nested
    @DisplayName("Test 3 - exact symptom of bug #7")
    class AnyQueryVisibility {

        @Test
        @DisplayName("getBeans(Object.class, Any.Literal) contains TestResource after replay")
        void shouldExposeReplayedBeanToAnyQuery() throws Exception {
            var jar = buildPreProcessedJar(
                    tempDir.resolve("resource.jar"),
                    "app.TestResource",
                    List.of("app.TestResource"),
                    List.of(TestScopeBce.class.getName() + ";app.TestResource"),
                    true, true);

            try (var cl = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()},
                    getClass().getClassLoader())) {
                var testResourceClass = Class.forName("app.TestResource", true, cl);

                try (var container = VaubanContainer.builder()
                        .classLoader(cl)
                        .scanClasspath()
                        .build()) {

                    var bm = container.getBeanManager();
                    var allBeans = bm.getBeans(Object.class, Any.Literal.INSTANCE);

                    boolean found = allBeans.stream()
                            .anyMatch(b -> b.getBeanClass().equals(testResourceClass));

                    assertTrue(found,
                            "getBeans(Object.class, @Any) must contain the replayed TestResource bean. "
                                    + "This is the exact symptom of bug #7. "
                                    + "Actual bean classes: "
                                    + allBeans.stream().map(Bean::getBeanClass).toList());
                }
            }
        }
    }

    // ======================================================================
    // Test 4 - Mixed classpath: pre-processed JAR + raw JAR
    // ======================================================================

    @Nested
    @DisplayName("Test 4 - mixed classpath")
    class MixedClasspath {

        @Test
        @DisplayName("pre-processed JAR (replay) + raw JAR (full BCE fallback) → two visible beans")
        void shouldHandleBothPreProcessedAndRawJars() throws Exception {
            // JAR A: pre-processed, TestResourceA targeted by the runtime-list
            var jarA = buildPreProcessedJar(
                    tempDir.resolve("resA.jar"),
                    "app.TestResourceA",
                    List.of("app.TestResourceA"),
                    List.of(TestScopeBce.class.getName() + ";app.TestResourceA"),
                    /*includeBceClass*/ true,
                    /*includeService*/  true);

            // JAR B: NOT pre-processed, no marker, no beans.list, no runtime-list
            var jarB = buildRawJar(
                    tempDir.resolve("resB.jar"),
                    "app.TestResourceB");

            try (var cl = new URLClassLoader(
                    new URL[]{jarA.toUri().toURL(), jarB.toUri().toURL()},
                    getClass().getClassLoader())) {

                var classA = Class.forName("app.TestResourceA", true, cl);
                var classB = Class.forName("app.TestResourceB", true, cl);

                try (var container = VaubanContainer.builder()
                        .classLoader(cl)
                        .scanClasspath()
                        // JAR B is raw: the consumer must add it explicitly
                        // (no beans.list). The normal pattern for a legacy JAR.
                        .addBeanClass(classB)
                        .build()) {

                    var bm = container.getBeanManager();
                    var allBeans = bm.getBeans(Object.class, Any.Literal.INSTANCE);

                    boolean foundA = allBeans.stream()
                            .anyMatch(b -> b.getBeanClass().equals(classA));
                    boolean foundB = allBeans.stream()
                            .anyMatch(b -> b.getBeanClass().equals(classB));

                    assertTrue(foundA,
                            "TestResourceA (pre-processed) should be visible after replay from runtime-list");
                    assertTrue(foundB,
                            "TestResourceB (raw JAR) should be visible after full BCE fallback");

                    // Both beans MUST have scope @RequestScoped
                    var beanA = allBeans.stream()
                            .filter(b -> b.getBeanClass().equals(classA)).findFirst().orElseThrow();
                    var beanB = allBeans.stream()
                            .filter(b -> b.getBeanClass().equals(classB)).findFirst().orElseThrow();

                    assertEquals(RequestScoped.class, beanA.getScope(),
                            "TestResourceA should have @RequestScoped from BCE replay");
                    assertEquals(RequestScoped.class, beanB.getScope(),
                            "TestResourceB should have @RequestScoped from full BCE fallback");
                }
            }
        }
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    /**
     * Check whether a runtime-visible annotation is present on a class's
     * <b>raw bytecode</b>. Used to assert that the APT does NOT rewrite
     * the source .class file (conforming to JSR-269 conventions).
     */
    private static boolean isAnnotationOnBytecode(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> ann) {
        return clazz.isAnnotationPresent(ann);
    }

    /**
     * Synthesize a minimal class bytecode via <b>Class-File API JDK 25</b>
     * ({@code java.lang.classfile.*}).
     *
     * <p>The generated class :
     * <ul>
     *   <li>extends {@code java.lang.Object}</li>
     *   <li>carries ONLY the {@code @PathLike} runtime-visible annotation
     *       (no CDI scope, no other marker) — reproduces exactly the
     *       bug #7 scenario where the BCE-injected annotations are NOT
     *       baked into the .class file</li>
     *   <li>has a trivial public default constructor (required for CDI
     *       bean instantiation)</li>
     * </ul>
     *
     * <p>Implementation note: we do NOT invoke {@code javac} programmatically
     * because that would require {@code requires java.compiler} in the
     * {@code io.vidocq.vauban.core} module — polluting the production
     * module-info just for tests. Class-File API lives in {@code java.base}.
     */
    private byte[] synthesizeTestResource(String fqn) {
        ClassDesc targetCD = ClassDesc.of(fqn);

        return ClassFile.of().build(targetCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);

            // @PathLike marker (runtime-visible)
            clb.with(RuntimeVisibleAnnotationsAttribute.of(
                    Annotation.of(CD_PathLike)));

            // public <init>() { super(); }
            clb.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MTD_void,
                    ClassFile.ACC_PUBLIC,
                    cob -> cob
                            .aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, MTD_void)
                            .return_());
        });
    }

    /**
     * Build a pre-processed JAR containing:
     * <ul>
     *   <li>{@code <fqn>.class} (synthesized with ONLY @PathLike)</li>
     *   <li>{@code META-INF/vauban-beans.list}</li>
     *   <li>{@code META-INF/vauban-bce-processed} (marker)</li>
     *   <li>{@code META-INF/vauban-bce-runtime.list}</li>
     *   <li>optionally the BCE class + ServiceLoader file</li>
     * </ul>
     */
    private Path buildPreProcessedJar(Path target,
                                      String classFqn,
                                      List<String> beansList,
                                      List<String> runtimeList,
                                      boolean includeBceClass,
                                      boolean includeService) throws Exception {
        Files.createDirectories(target.getParent());
        try (var fos = Files.newOutputStream(target);
             var jar = new JarOutputStream(fos)) {

            // Class (synthesized via Class-File API)
            var classBytes = synthesizeTestResource(classFqn);
            writeJarEntry(jar, classFqn.replace('.', '/') + ".class", classBytes);

            // beans.list
            var beansContent = "# Vauban beans list (test)\n" + String.join("\n", beansList) + "\n";
            writeJarEntry(jar, BEANS_LIST_PATH, beansContent.getBytes(StandardCharsets.UTF_8));

            // bce-processed marker
            writeJarEntry(jar, BCE_MARKER_PATH,
                    "# bce processed\n".getBytes(StandardCharsets.UTF_8));

            // runtime-list (pivot du bug #7)
            var runtimeContent = "# Vauban BCE runtime replay list\n"
                    + String.join("\n", runtimeList) + "\n";
            writeJarEntry(jar, RUNTIME_LIST_PATH, runtimeContent.getBytes(StandardCharsets.UTF_8));

            if (includeBceClass) {
                // Include the BCE class bytecode (loaded from current CL)
                var bceBytes = loadClassBytes(TestScopeBce.class);
                writeJarEntry(jar, TestScopeBce.class.getName().replace('.', '/') + ".class",
                        bceBytes);
            }

            if (includeService) {
                writeJarEntry(jar, BCE_SERVICE_PATH,
                        (TestScopeBce.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return target;
    }

    /**
     * Build a "raw" JAR (no markers, no beans.list, no runtime-list) — just the class.
     */
    private Path buildRawJar(Path target, String classFqn) throws Exception {
        Files.createDirectories(target.getParent());
        try (var fos = Files.newOutputStream(target);
             var jar = new JarOutputStream(fos)) {
            var classBytes = synthesizeTestResource(classFqn);
            writeJarEntry(jar, classFqn.replace('.', '/') + ".class", classBytes);
        }
        return target;
    }

    private static void writeJarEntry(JarOutputStream jar, String name, byte[] content) throws IOException {
        var entry = new JarEntry(name);
        jar.putNextEntry(entry);
        jar.write(content);
        jar.closeEntry();
    }

    private static byte[] loadClassBytes(Class<?> clazz) throws IOException {
        var resource = clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IOException("Cannot find bytecode for " + clazz);
            var baos = new ByteArrayOutputStream();
            is.transferTo(baos);
            return baos.toByteArray();
        }
    }
}
