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
package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.processor.VaubanProcessor;
import io.vidocq.vauban.processor.apt.scanned.ScannedBean;
import io.vidocq.vauban.processor.apt.vetoed.ScannedQuietBean;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the build generates calls no vetoed interceptor (BUG-20261116-12). The {@code $$Intercepted} subclass the
 * processor writes for a bean with an interceptor binding hands each call to the interceptors the container resolves
 * at run time, so a vetoed interceptor, which the container does not know, is never in its chain. The processor lists
 * no vetoed class in {@code vauban-beans.list}, the classes the container of a compiled application indexes, and
 * writes no way to create one. A class of a vetoed package is vetoed too (BUG-20261117-01).
 */
@DisplayName("A vetoed interceptor is woven into nothing the build generates (BUG-20261116-12, BUG-20261117-01)")
class VetoedInterceptorCompileTimeTest {

    private static final String VETOED = "@jakarta.enterprise.inject.Vetoed";
    private static final String LOGGER = "vi.Logger";
    private static final String LOGGED_SERVICE = "vi.LoggedService";
    /** What {@code vi.LoggedService} answers when {@code vi.Logger} alone intercepts it. */
    private static final String LOGGED_HELLO = "Logger(hello)";
    /** What an extension's {@code @Registration} sees of {@link #withAVetoedPackage()}, sorted. */
    private static final List<String> REGISTERED = List.of(LOGGED_SERVICE, LOGGER);

    @TempDir
    Path workDir;

    @Test
    @DisplayName("a bean bound to a vetoed interceptor gets a subclass that does not call it")
    void theSubclassOfABoundBeanCallsNoVetoedInterceptor() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        sources.put("vi/Logged.java", binding("Logged"));
        sources.put("vi/Muted.java", binding("Muted"));
        sources.put("vi/Logger.java", wrappingInterceptor("vi", "Logger", "Logged", "", 0));
        sources.put("vi/VetoedLogger.java", wrappingInterceptor("vi", "VetoedLogger", "Logged", VETOED, 1));
        sources.put("vi/VetoedMuter.java", wrappingInterceptor("vi", "VetoedMuter", "Muted", VETOED, 0));
        sources.put("vi/LoggedService.java", boundBean("LoggedService", "Logged"));
        sources.put("vi/MutedService.java", boundBean("MutedService", "Muted"));

        var result = compile(sources);

        assertTrue(Files.exists(result.classes().resolve("vi/MutedService$$Intercepted.class")),
                "the processor weaves a bean by its bindings, whatever interceptor they name");
        var beans = Files.readString(result.classes().resolve("META-INF/vauban-beans.list"));
        assertTrue(beans.contains(LOGGER), beans);
        assertFalse(beans.contains("vi.Vetoed"), beans);
        assertFalse(components(result).contains("vi.Vetoed"), "the processor creates no vetoed interceptor");
        try (var loader = loader(result);
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(LOGGED_HELLO, hello(container, loader.loadClass(LOGGED_SERVICE)));
            assertEquals("hello", hello(container, loader.loadClass("vi.MutedService")),
                    "a bean only a vetoed interceptor would intercept");
        }
    }

    /**
     * CDI 4.1 ignores every type of a package whose {@code package-info} carries {@code @Vetoed}. The processor lists
     * none of them in {@code vauban-beans.list}, the classes the container of a compiled application indexes, and
     * generates nothing for them: no client proxy, no {@code $$Intercepted} subclass, no {@code _VaubanComponents}
     * (BUG-20261117-01).
     */
    @Test
    @DisplayName("neither a bean nor an interceptor of a vetoed package is part of a compiled application")
    void aVetoedPackageHoldsNoBeanAndNoInterceptor() throws Exception {
        var result = compile(withAVetoedPackage());

        var beans = Files.readString(result.classes().resolve("META-INF/vauban-beans.list"));
        assertTrue(beans.contains(LOGGER), beans);
        assertFalse(beans.contains("vi.quiet"), beans);
        assertEquals(Set.of("package-info.class", "QuietBean.class", "QuietLogger.class"),
                fileNames(result.classes().resolve("vi/quiet")), "the build generates no class for a vetoed package");
        assertFalse(Files.exists(result.generated().resolve("vi/quiet")),
                "the build generates no source for a vetoed package");
        try (var loader = loader(result);
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertNothingOfTheVetoedPackage(container, loader);
        }
    }

    /** Records the beans and interceptors {@code @Registration} gives it while the processor runs. */
    public static class RegistrationRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void registration(BeanInfo bean) {
            SEEN.add(bean.declaringClass().name());
        }
    }

    /**
     * The container of a compiled application indexes the beans the processor listed. Its extensions see nothing of a
     * vetoed package, whether they run at start or the build froze them, as {@code vauban:generate} does.
     */
    @Test
    @DisplayName("the extensions of a compiled application, run at start or frozen, see nothing of a vetoed package")
    void theExtensionsOfACompiledApplicationSeeNothingOfAVetoedPackage() throws Exception {
        var result = compile(withAVetoedPackage());
        var services = result.classes().resolve("META-INF/services/" + BuildCompatibleExtension.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, RegistrationRecorder.class.getName() + "\n");

        RegistrationRecorder.SEEN.clear();
        try (var loader = loader(result); var container = VaubanContainer.builder().classLoader(loader)
                .scanClasspath().build()) {
            assertFalse(container.extensionsAtStart().frozen());
            assertNothingOfTheVetoedPackage(container, loader);
        }
        assertEquals(REGISTERED, recorded(), "what the extension sees at start");

        RegistrationRecorder.SEEN.clear();
        try (var loader = loader(result)) {
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(result.classes());
            assertTrue(outcome.frozen(), outcome.reason());
        }
        assertEquals(REGISTERED, recorded(), "what the extension sees at build");
        try (var loader = loader(result); var container = VaubanContainer.builder().classLoader(loader)
                .scanClasspath().build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertNothingOfTheVetoedPackage(container, loader);
        }
    }

    @Test
    @DisplayName("an extension run by the processor sees neither a bean nor an interceptor of a vetoed package")
    void anExtensionRunByTheProcessorSeesNothingOfAVetoedPackage() throws Exception {
        List<String> seen = registeredWhileCompiling(withAVetoedPackage());

        assertTrue(seen.contains(LOGGED_SERVICE), seen::toString);
        List<String> vetoed = seen.stream().filter(name -> name.startsWith("vi.quiet.")).toList();
        assertEquals(List.of(), vetoed, "what @Registration sees of the vetoed package");
    }

    /** Adds a class of a vetoed package of the processor path, as an extension may add one of a library. */
    public static class VetoedPackageScanner implements BuildCompatibleExtension {

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(ScannedQuietBean.class.getName());
        }
    }

    /**
     * A class an extension adds through {@code ScannedClasses} comes from a library, which no {@code beans.xml} needs
     * to mark. The processor reads the {@code package-info} of its package beside it: one of a vetoed package is no
     * bean, the processor does not list it, and no extension sees it.
     */
    @Test
    @DisplayName("a class of a vetoed package an extension adds through ScannedClasses is no bean")
    void aClassOfAVetoedPackageAnExtensionScansIsNoBean() throws Exception {
        RegistrationRecorder.SEEN.clear();
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(VetoedPackageScanner.class, RegistrationRecorder.class));

        var result = ProcessorCompiler.compile(workDir, withAVetoedPackage(), List.of(), processor);

        assertTrue(result.success(), () -> "compilation failed: " + result.messages());
        var beans = Files.readString(result.classes().resolve("META-INF/vauban-beans.list"));
        assertTrue(beans.contains(LOGGED_SERVICE), beans);
        assertFalse(beans.contains(ScannedQuietBean.class.getName()), beans);
        assertFalse(recorded().contains(ScannedQuietBean.class.getName()), recorded()::toString);
        try (var loader = loader(result);
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(Set.of(), container.getBeanManager().getBeans(ScannedQuietBean.class),
                    "a class of a vetoed package an extension added");
        }
    }

    /** Adds a class of the processor path whose package has no {@code package-info} there. */
    public static class PackageScanner implements BuildCompatibleExtension {

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(ScannedBean.class.getName());
        }
    }

    /**
     * The processor reads the veto of the package of a class an extension adds through the compiler, which sees a
     * {@code package-info} the compilation compiles or finds on its class path, and not only on the processor path.
     */
    @Test
    @DisplayName("a class an extension adds is no bean when the compilation vetoes its package")
    void aClassAnExtensionScansIsNoBeanWhenTheCompilationVetoesItsPackage() throws Exception {
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(PackageScanner.class));
        var sources = withAVetoedPackage();
        var scannedPackage = ScannedBean.class.getPackageName();
        sources.put(scannedPackage.replace('.', '/') + "/package-info.java", VETOED + "\npackage " + scannedPackage
                + ";\n");

        var result = ProcessorCompiler.compile(workDir, sources, List.of(), processor);

        assertTrue(result.success(), () -> "compilation failed: " + result.messages());
        var beans = Files.readString(result.classes().resolve("META-INF/vauban-beans.list"));
        assertTrue(beans.contains(LOGGED_SERVICE), beans);
        assertFalse(beans.contains(ScannedBean.class.getName()), beans);
    }

    /**
     * {@code @Registration(types = Object.class)} with a {@code BeanInfo} is called for the interceptors too, as the
     * container does at start. The processor matches an interceptor by loading its class, which a class this
     * compilation compiles is not, so it calls it for none of them (BUG-20261117-02).
     */
    @Test
    @Disabled("BUG-20261117-02: an extension run by the processor sees no interceptor this compilation compiles")
    @DisplayName("an extension run by the processor sees the interceptors the compilation compiles")
    void anExtensionRunByTheProcessorSeesTheInterceptorsTheCompilationCompiles() throws Exception {
        List<String> seen = registeredWhileCompiling(withAVetoedPackage());

        assertEquals(REGISTERED, seen, "the beans and interceptors @Registration sees");
    }

    /**
     * A type no annotation brings into the index reaches it when an injection point needs it, as a type of another
     * module would. One of a vetoed package stays no bean: the processor does not list it, and the injection point is
     * unsatisfied, as the container finds it at start.
     */
    @Test
    @DisplayName("a type of a vetoed package an injection point needs is no bean")
    void aTypeOfAVetoedPackageAnInjectionPointNeedsIsNoBean() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        sources.put("vi/hidden/package-info.java", VETOED + "\npackage vi.hidden;\n");
        sources.put("vi/hidden/Hidden.java", """
                package vi.hidden;
                public class Hidden {
                    @jakarta.inject.Inject
                    public Hidden() {
                    }
                }
                """);
        sources.put("vi/Seeker.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Seeker {
                    @jakarta.inject.Inject
                    vi.hidden.Hidden hidden;
                }
                """);

        var result = ProcessorCompiler.compile(workDir, sources);

        var beansList = result.classes().resolve("META-INF/vauban-beans.list");
        var beans = Files.exists(beansList) ? Files.readString(beansList) : "";
        assertFalse(beans.contains("vi.hidden"), beans);
        assertFalse(result.success(), "the injection point has no bean");
        assertTrue(result.mentions("Unsatisfied dependency"), () -> String.join("\n", result.messages()));
    }

    // ---- helpers ----

    /** The classes of the beans and interceptors {@code @Registration} sees while the processor compiles them. */
    private List<String> registeredWhileCompiling(Map<String, String> sources) throws IOException {
        RegistrationRecorder.SEEN.clear();
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(RegistrationRecorder.class));

        var result = ProcessorCompiler.compile(workDir, sources, List.of(), processor);

        assertTrue(result.success(), () -> "compilation failed: " + result.messages());
        return recorded();
    }

    private static List<String> recorded() {
        return RegistrationRecorder.SEEN.stream().sorted().toList();
    }

    /** The bean of the vetoed package is no bean, and its interceptor does not intercept the bean outside it. */
    private static void assertNothingOfTheVetoedPackage(VaubanContainer container, ClassLoader loader)
            throws ClassNotFoundException {
        assertEquals(Set.of(), container.getBeanManager().getBeans(loader.loadClass("vi.quiet.QuietBean")),
                "a bean class of the vetoed package");
        assertEquals(LOGGED_HELLO, hello(container, loader.loadClass(LOGGED_SERVICE)));
    }

    /** The loader of the compiled classes, which takes the rest from the test's class path. */
    private URLClassLoader loader(ProcessorCompiler.Result result) throws IOException {
        return new URLClassLoader(new URL[] {result.classes().toUri().toURL()}, getClass().getClassLoader());
    }

    /**
     * A bean bound to an interceptor, and the package {@code vi.quiet}, vetoed by its {@code package-info}: an
     * interceptor for the same binding, and a normal-scoped bean bound to it, which would get a client proxy and an
     * {@code $$Intercepted} subclass.
     */
    private static Map<String, String> withAVetoedPackage() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("vi/Logged.java", binding("Logged"));
        sources.put("vi/Logger.java", wrappingInterceptor("vi", "Logger", "Logged", "", 0));
        sources.put("vi/LoggedService.java", boundBean("LoggedService", "Logged"));
        sources.put("vi/quiet/package-info.java", VETOED + "\npackage vi.quiet;\n");
        sources.put("vi/quiet/QuietLogger.java", wrappingInterceptor("vi.quiet", "QuietLogger", "vi.Logged", "", 1));
        sources.put("vi/quiet/QuietBean.java", """
                package vi.quiet;
                @vi.Logged
                @jakarta.enterprise.context.ApplicationScoped
                public class QuietBean {
                    public String get() {
                        return "quiet";
                    }
                }
                """);
        return sources;
    }

    /** The names of the files of {@code directory}, without those of its subdirectories. */
    private static Set<String> fileNames(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private ProcessorCompiler.Result compile(Map<String, String> sources) throws IOException {
        var result = ProcessorCompiler.compile(workDir, sources);
        assertTrue(result.success(), () -> "compilation failed: " + result.messages());
        return result;
    }

    /** The component provider the processor wrote for package {@code vi}: what it creates without reflection. */
    private static String components(ProcessorCompiler.Result result) throws IOException {
        return Files.readString(result.generated().resolve("vi/_VaubanComponents.java"));
    }

    private static String binding(String name) {
        return """
                package vi;
                @jakarta.interceptor.InterceptorBinding
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                public @interface %s {
                }
                """.formatted(name);
    }

    /** An interceptor that wraps what it intercepts in {@code name(...)}, at {@code APPLICATION + rank}. */
    private static String wrappingInterceptor(String pkg, String name, String binding, String veto, int rank) {
        return """
                package %s;
                %s
                @%s
                @jakarta.interceptor.Interceptor
                @jakarta.annotation.Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION + %d)
                public class %s {
                    @jakarta.interceptor.AroundInvoke
                    Object wrap(jakarta.interceptor.InvocationContext invocation) throws Exception {
                        return "%s(" + invocation.proceed() + ")";
                    }
                }
                """.formatted(pkg, veto, binding, rank, name, name);
    }

    private static String boundBean(String name, String binding) {
        return """
                package vi;
                @%s
                @jakarta.enterprise.context.Dependent
                public class %s implements java.util.function.Supplier<String> {
                    @Override
                    public String get() {
                        return "hello";
                    }
                }
                """.formatted(binding, name);
    }

    private static Object hello(VaubanContainer container, Class<?> beanClass) {
        return ((Supplier<?>) container.select(beanClass)).get();
    }
}
