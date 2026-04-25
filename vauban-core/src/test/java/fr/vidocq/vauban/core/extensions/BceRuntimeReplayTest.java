package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.core.extensions.testfixtures.PathLike;
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
 * Tests ROUGES pour le bug #7 — partie runtime (rejeu BCE).
 *
 * <h2>Contrat teste</h2>
 * <ol>
 *   <li>Au demarrage, {@code VaubanContainerBuilder} doit lire
 *       {@code META-INF/vauban-bce-runtime.list} sur tout le classpath.</li>
 *   <li>Pour chaque couple {@code (bceFQN, classFQN)} : resoudre la BCE via
 *       ServiceLoader et rejouer sa phase {@code @Enhancement} uniquement sur
 *       la classe ciblee.</li>
 *   <li>L'index est reconstruit avec les annotations ajoutees ->
 *       {@code BeanManager.getBeans(TestResource.class)} voit la classe
 *       avec son scope enrichi.</li>
 *   <li>Le short-circuit {@code allSourcesProcessed -> bceClasses = List.of()}
 *       doit etre remplace par une lecture ciblee de la runtime-list.</li>
 * </ol>
 *
 * <h2>Montage de classpath</h2>
 * Les classes {@code TestResourceA} / {@code TestResourceB} sont synthetisees
 * via la <b>Class-File API JDK 25</b> ({@code java.lang.classfile.*}) avec
 * pour seul contenu l'annotation {@code @PathLike}. Cela reproduit le
 * scenario bug #7 : un JAR pre-processe ou l'APT a inscrit la BCE dans la
 * runtime-list mais n'a PAS reecrit le bytecode (pattern conforme APT/JSR-269).
 *
 * Avantage vs {@code javax.tools.JavaCompiler} : pas besoin de
 * {@code requires java.compiler} dans le module de production, coherent
 * avec le reste de Vauban qui genere tout son bytecode via Class-File API.
 *
 * Puis on packe dans un JAR :
 * <ul>
 *   <li>{@code TestResource.class}</li>
 *   <li>{@code META-INF/vauban-beans.list}</li>
 *   <li>{@code META-INF/vauban-bce-processed} (marker)</li>
 *   <li>{@code META-INF/vauban-bce-runtime.list}
 *       (nouveau fichier pivot du bug #7)</li>
 *   <li>{@code META-INF/services/...BuildCompatibleExtension} listant la BCE</li>
 * </ul>
 */
@DisplayName("BCE runtime replay - lecture de vauban-bce-runtime.list")
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
    // Voir {@link fr.vidocq.vauban.core.extensions.testfixtures.PathLike}.
    // Extraite en top-level pour avoir un ClassDesc stable
    // (descripteur sans '$') et etre resolvable depuis tout ClassLoader.

    // ---- BCE de test : ajoute @RequestScoped aux classes @PathLike ----

    public static class TestScopeBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = PathLike.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(RequestScoped.class);
        }
    }

    // ======================================================================
    // Test 2 - Runtime rejoue la BCE depuis la liste et applique le scope
    // ======================================================================

    @Nested
    @DisplayName("Test 2 - rejeu BCE → scope RequestScoped applique")
    class ReplayFromRuntimeList {

        @Test
        @DisplayName("BeanManager.getBeans(TestResource.class) retourne un bean avec scope @RequestScoped")
        void shouldReplayBceAndApplyRequestScopedAtRuntime() throws Exception {
            // JAR pre-processe : TestResource avec @PathLike SEUL dans le bytecode
            var jar = buildPreProcessedJar(
                    tempDir.resolve("resource.jar"),
                    "app.TestResource",
                    /*beansList*/       List.of("app.TestResource"),
                    /*runtimeList*/     List.of(
                            TestScopeBce.class.getName() + ";app.TestResource"),
                    /*includeBceClass*/ true,
                    /*includeService*/  true);

            var cl = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()},
                    getClass().getClassLoader());
            var testResourceClass = Class.forName("app.TestResource", true, cl);
            assertFalse(isAnnotationOnBytecode(testResourceClass, RequestScoped.class),
                    "Precondition: @RequestScoped MUST NOT be in the raw bytecode "
                            + "(APT doit ne PAS reecrire le .class, conformement a la solution)");

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

    // ======================================================================
    // Test 3 - symptome exact bug #7 : getBeans(Object.class, @Any)
    // ======================================================================

    @Nested
    @DisplayName("Test 3 - symptome exact bug #7")
    class AnyQueryVisibility {

        @Test
        @DisplayName("getBeans(Object.class, Any.Literal) contient TestResource apres replay")
        void shouldExposeReplayedBeanToAnyQuery() throws Exception {
            var jar = buildPreProcessedJar(
                    tempDir.resolve("resource.jar"),
                    "app.TestResource",
                    List.of("app.TestResource"),
                    List.of(TestScopeBce.class.getName() + ";app.TestResource"),
                    true, true);

            var cl = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()},
                    getClass().getClassLoader());
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

    // ======================================================================
    // Test 4 - Classpath mixte : JAR pre-processe + JAR brut
    // ======================================================================

    @Nested
    @DisplayName("Test 4 - classpath mixte")
    class MixedClasspath {

        @Test
        @DisplayName("JAR pre-processe (replay) + JAR brut (fallback full BCE) → deux beans visibles")
        void shouldHandleBothPreProcessedAndRawJars() throws Exception {
            // JAR A : pre-processe, TestResourceA ciblee par runtime-list
            var jarA = buildPreProcessedJar(
                    tempDir.resolve("resA.jar"),
                    "app.TestResourceA",
                    List.of("app.TestResourceA"),
                    List.of(TestScopeBce.class.getName() + ";app.TestResourceA"),
                    /*includeBceClass*/ true,
                    /*includeService*/  true);

            // JAR B : NON pre-processe, pas de marker ni beans.list ni runtime-list
            var jarB = buildRawJar(
                    tempDir.resolve("resB.jar"),
                    "app.TestResourceB");

            var cl = new URLClassLoader(
                    new URL[]{jarA.toUri().toURL(), jarB.toUri().toURL()},
                    getClass().getClassLoader());

            var classA = Class.forName("app.TestResourceA", true, cl);
            var classB = Class.forName("app.TestResourceB", true, cl);

            try (var container = VaubanContainer.builder()
                    .classLoader(cl)
                    .scanClasspath()
                    // JAR B est brut : le consommateur doit l'ajouter explicitement
                    // (pas de beans.list). Pattern normal d'un JAR legacy.
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

                // Les deux beans DOIVENT avoir scope @RequestScoped
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
     * {@code fr.vidocq.vauban.core} module — polluting the production
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
