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
import io.vidocq.vauban.processor.apt.binding.Traced;
import io.vidocq.vauban.processor.apt.repeatable.Mark;
import io.vidocq.vauban.processor.apt.repeatable.Tag;
import io.vidocq.vauban.processor.apt.retention.ClassRetained;
import io.vidocq.vauban.processor.apt.retention.SourceRetained;
import io.vidocq.vauban.processor.apt.scanned.ScannedBean;
import io.vidocq.vauban.processor.apt.vetoed.ScannedQuietBean;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.PassivationCapable;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.WildcardType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the build generates calls no vetoed interceptor (BUG-20261116-12). The {@code $$Intercepted} subclass the
 * processor writes for a bean with an interceptor binding hands each call to the interceptors the container resolves
 * at run time, so a vetoed interceptor, which the container does not know, is never in its chain. The processor lists
 * no vetoed class in {@code vauban-beans.list}, the classes the container of a compiled application indexes, and
 * writes no way to create one. A class of a vetoed package is vetoed too (BUG-20261117-01). An extension the processor
 * runs gets from {@code @Registration} the interceptors and the observers the compilation compiles, as the container
 * gives them at start and frozen (BUG-20261117-02, BUG-20261117-05), and from {@code @Enhancement} the subtypes of
 * its types (BUG-20261117-07). A bean has the types a class the compilation compiles brings it, for the extensions and
 * for the injection points the build resolves, as at start (BUG-20261117-03). An extension sees the supertypes a class
 * declares, with their type arguments, the same three ways (BUG-20261117-22).
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
    private static final String SERIAL_SERVICE = "vi.SerialService";
    private static final String SERIAL_CLIENT = "vi.SerialClient";
    /** How the build and the container report an injection point more than one bean satisfies. */
    private static final String AMBIGUOUS = "Ambiguous dependency";
    /** How the recorders name what an {@code InterceptorInfo} method, and a {@code Serializable} one, is given. */
    private static final String INTERCEPTOR = "interceptor ";
    private static final String SERIALIZABLE = "serializable ";
    /** The package of the beans whose bean types the parameterized-type tests compare (BUG-20261117-14). */
    private static final String TYPES = "vi.types.";
    private static final String READER_SOURCE = "vi/types/Reader.java";
    /** What {@link BeanTypeRecorder} records of {@link #reader(String)}. */
    private static final String READER_TYPES =
            "vi.types.Reader: java.lang.Object, java.util.function.Supplier<java.lang.Object>, vi.types.Reader";
    private static final String DEPENDENT = "@jakarta.enterprise.context.Dependent\n";
    private static final String JAVA = ".java";
    private static final String REPO_OF_STRING = "Repo<String>";
    /** The raw types above {@code ArrayList}, as {@link BeanTypeRecorder} writes them, but the class's own. */
    private static final String ABOVE_ARRAY_LIST = "java.io.Serializable, java.lang.Cloneable, java.lang.Iterable,"
            + " java.lang.Object, java.util.AbstractCollection, java.util.AbstractList, java.util.ArrayList,"
            + " java.util.Collection, java.util.List, java.util.RandomAccess, java.util.SequencedCollection, ";
    /** What {@link BeanTypeRecorder} records of {@code vi.types.InnerFactory}, a class bean. */
    private static final String INNER_FACTORY_TYPES = "vi.types.InnerFactory: java.lang.Object, vi.types.InnerFactory";
    /** What {@link BeanTypeRecorder} records of the beans of {@link #withRawSupertypes()}. */
    private static final List<String> RAW_SUPERTYPE_BEANS = List.of(
            "vi.types.LegacyMap: java.io.Serializable, java.lang.Cloneable, java.lang.Object, java.util.AbstractMap,"
                    + " java.util.HashMap, java.util.Map, vi.types.LegacyMap",
            "vi.types.LegacyNames: " + ABOVE_ARRAY_LIST + "vi.types.LegacyNames");
    private static final String TEXTS_IMPL = "TextsImpl";
    private static final String HOLDER = "Holder";
    private static final String HOLDER_SOURCE = "public interface Holder<T> {\n}";
    /** What the build adds to an unsatisfied injection point it leaves to the container. */
    private static final String DEFERRED = " — the required type is not declared by this compilation unit; resolution"
            + " deferred to the runtime container.";
    /** How the container starts the message of a deployment it refuses. */
    private static final String VALIDATION_FAILED = "CDI deployment validation failed:\n  - ";
    /** {@code vi.types.FooFactory}, a {@code @Dependent} bean that produces a raw {@code Foo}. */
    private static final String RAW_FOO_FACTORY = DEPENDENT + """
            @SuppressWarnings("rawtypes")
            public class FooFactory {
                @jakarta.enterprise.inject.Produces
                Foo foo() {
                    return new Foo() {
                    };
                }
            }""";
    /** {@code vi.types.Outer<O>}, whose inner class {@code Inner<I>} is a {@code Holder<O>}. */
    private static final String GENERIC_OUTER_SOURCE = """
            public class Outer<O> {
                public class Inner<I> implements Holder<O> {
                }
            }""";
    /** The classes of {@code vi.types} whose supertypes {@link SupertypeRecorder} records (BUG-20261117-22). */
    private static final List<String> SUPERTYPE_PROBES = List.of("Probe", "Extending", "Archive", "Supplying", "Shapes",
            "Raw", "Plain", "Level");
    private static final String SUPPLYING = "Supplying";
    private static final String THROUGH_GENERIC_CLASS = "through genericClass() ";
    private static final String OBJECT_SUPERCLASS = "superClass=CLASS java.lang.Object";
    /** What {@link SupertypeRecorder} records of an interface, which has no superclass (BUG-20261117-32). */
    private static final String NO_SUPERCLASS = "superClass=null";
    private static final String NO_SUPERINTERFACE = "superInterfaces=[]";
    /** The class of {@code vi.types} whose wildcards {@link WildcardRecorder} records (BUG-20261117-34). */
    private static final String WILD = "Wild";
    private static final String EQUALS_UNBOUNDED = ", equals Types.wildcardUnbounded()";
    private static final String ECHO = "echo";
    /** The bean metadata of the bean an interceptor intercepts, without its type argument (BUG-20261117-41). */
    private static final String INTERCEPTED_BEAN =
            "@jakarta.enterprise.inject.Intercepted jakarta.enterprise.inject.spi.Bean";
    private static final String WATCHED_SERVICE = "vi.WatchedService";
    /** What {@code vi.WatchedService} answers when {@code vi.Watcher}, which reads the bean it intercepts, does. */
    private static final String WATCHED_HELLO = "Watcher(vi.WatchedService: hello)";
    /** What {@link #startedAnswer} gives of a container it only starts, without calling a bean. */
    private static final String DEPLOYED = "deployed";
    /** Mansart's repository interface, above two interfaces no annotation brings into the index (BUG-20261117-31). */
    private static final String ARCHIVE = "Archive";
    private static final String PLAIN = "Plain";
    /** A class with a lambda and a bridge method, outside the index, and a bean of the same shape (BUG-20261117-43). */
    private static final String TELLER = "Teller";
    private static final String COUNTER = "Counter";

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
        registerExtension(result, RegistrationRecorder.class);

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
        assertTrue(seen.contains(LOGGER), () -> "the interceptor outside the vetoed package: " + seen);
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
     * container does at start. The processor matched an interceptor by loading its class, which a class this
     * compilation compiles is not, so it called it for none of them (BUG-20261117-02).
     */
    @Test
    @DisplayName("an extension run by the processor sees the interceptors the compilation compiles")
    void anExtensionRunByTheProcessorSeesTheInterceptorsTheCompilationCompiles() throws Exception {
        List<String> seen = registeredWhileCompiling(withAVetoedPackage());

        assertEquals(REGISTERED, seen, "the beans and interceptors @Registration sees");
    }

    /**
     * Records what {@code @Registration} gives a method with an {@code InterceptorInfo} parameter, one whose
     * {@code types} names an interceptor binding type, which is no type of the interceptor it binds, and one whose
     * {@code types} names a type of an interceptor.
     */
    public static class InterceptorRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void interceptors(InterceptorInfo interceptor) {
            SEEN.add(INTERCEPTOR + interceptor.declaringClass().name());
        }

        @Registration(types = Traced.class)
        public void bindings(InterceptorInfo interceptor) {
            SEEN.add("binding " + interceptor.declaringClass().name());
        }

        @Registration(types = Serializable.class)
        public void serializables(BeanInfo bean) {
            SEEN.add(SERIALIZABLE + bean.declaringClass().name());
        }
    }

    /**
     * A method with an {@code InterceptorInfo} parameter is called for each interceptor, and for no bean. A method whose
     * {@code types} names a type of an interceptor is called for that interceptor, which has the type through a
     * superclass the compilation compiles. A method whose {@code types} names the binding type of {@code vi.Tracer}
     * is called for no interceptor. The processor that runs the extension calls them as the container does when it
     * runs the extension at start, and as the build that freezes it does, and none of them for the vetoed interceptor
     * of the same type (BUG-20261117-02).
     */
    @Test
    @DisplayName("@Registration gets the same interceptors from the processor, at start and frozen")
    void registrationGetsTheSameInterceptorsFromTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Tracer.java", """
                package vi;
                @%s
                @jakarta.interceptor.Interceptor
                @jakarta.annotation.Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
                public class Tracer {
                    @jakarta.interceptor.AroundInvoke
                    Object trace(jakarta.interceptor.InvocationContext invocation) throws Exception {
                        return invocation.proceed();
                    }
                }
                """.formatted(Traced.class.getName()));
        var expected = List.of(INTERCEPTOR + LOGGER, "interceptor vi.Tracer", SERIALIZABLE + LOGGER);

        var seen = seenThreeWays(sources, InterceptorRecorder.class, InterceptorRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * Records the observers {@code @Registration} gives it: of any event, of a {@code Serializable} event, of an array of
     * objects, of a {@code Number}, of an {@code Integer} and of an array of {@code int}.
     */
    public static class ObserverRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void observers(ObserverInfo observer) {
            SEEN.add("observer " + described(observer));
        }

        @Registration(types = Serializable.class)
        public void serializableEvents(ObserverInfo observer) {
            SEEN.add("serializable event " + described(observer));
        }

        @Registration(types = Object[].class)
        public void objectArrayEvents(ObserverInfo observer) {
            SEEN.add("object array event " + described(observer));
        }

        @Registration(types = Number.class)
        public void numberEvents(ObserverInfo observer) {
            SEEN.add("number event " + described(observer));
        }

        @Registration(types = Integer.class)
        public void integerEvents(ObserverInfo observer) {
            SEEN.add("integer event " + described(observer));
        }

        @Registration(types = int[].class)
        public void intArrayEvents(ObserverInfo observer) {
            SEEN.add("int array event " + described(observer));
        }

        /** The class that declares {@code observer}, and the type of the event it observes. */
        private static String described(ObserverInfo observer) {
            return observer.declaringClass().name() + " of " + nameOf(observer.eventType());
        }

        private static String nameOf(Type type) {
            if (type.isClass()) return type.asClass().declaration().name();
            if (type.isArray()) return nameOf(type.asArray().componentType()) + "[]";
            if (type.isTypeVariable()) return type.asTypeVariable().name();
            if (type.isPrimitive()) return type.asPrimitive().name();
            return type.toString();
        }
    }

    /**
     * CDI 4.1 calls a method with an {@code ObserverInfo} parameter for each observer whose observed event type is
     * assignable to an expected type. The container matched the class that declares the observer instead, and the
     * processor, which loaded that class, matched none the compilation compiles (BUG-20261117-05). An array is
     * assignable to {@code Object}, {@code Cloneable}, {@code Serializable} and to an array of a supertype of its
     * component, a type variable to what one of its bounds is, a primitive type to what its wrapper is, and an array of
     * primitives to an array of the same primitives only. The container reads the event type of an observer it loads
     * as a class type named {@code int}, the processor as the primitive type: both match alike.
     */
    @Test
    @DisplayName("@Registration gets the observers of the events of its types from the processor, at start and frozen")
    void registrationGetsTheObserversOfTheEventsOfItsTypesFromTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Ping.java", """
                package vi;
                public class Ping extends Wrapping {
                }
                """);
        sources.put("vi/Listener.java", """
                package vi;
                @jakarta.enterprise.context.ApplicationScoped
                public class Listener implements java.io.Serializable {
                    void onPing(@jakarta.enterprise.event.Observes Ping ping) {
                    }
                    void onText(@jakarta.enterprise.event.Observes CharSequence text) {
                    }
                    void onTexts(@jakarta.enterprise.event.Observes String[] texts) {
                    }
                }
                """);
        sources.put("vi/Gauge.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Gauge<T extends Number> {
                    void onReading(@jakarta.enterprise.event.Observes T reading) {
                    }
                }
                """);
        sources.put("vi/Counter.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Counter {
                    void onCount(@jakarta.enterprise.event.Observes int count) {
                    }
                    void onCounts(@jakarta.enterprise.event.Observes int[] counts) {
                    }
                }
                """);
        var expected = sorted(List.of(
                "observer vi.Listener of java.lang.CharSequence", "observer vi.Listener of vi.Ping",
                "observer vi.Listener of java.lang.String[]", "observer vi.Gauge of T",
                "observer vi.Counter of int", "observer vi.Counter of int[]",
                "serializable event vi.Listener of vi.Ping", "serializable event vi.Listener of java.lang.String[]",
                "serializable event vi.Gauge of T", "serializable event vi.Counter of int",
                "serializable event vi.Counter of int[]",
                "object array event vi.Listener of java.lang.String[]",
                "number event vi.Gauge of T", "number event vi.Counter of int",
                "integer event vi.Counter of int",
                "int array event vi.Counter of int[]"));

        var seen = seenThreeWays(sources, ObserverRecorder.class, ObserverRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * Adds {@code @Deprecated}, unguarded, to each {@code @Dependent} subtype of {@code java.util.EventListener}, and
     * counts it back on the class of each such bean {@code @Registration} gives it.
     */
    public static class DeprecatingExtension implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Enhancement(types = java.util.EventListener.class, withSubtypes = true, withAnnotations = Dependent.class)
        public void deprecate(ClassConfig clazz) {
            clazz.addAnnotation(Deprecated.class);
        }

        @Registration(types = Object.class)
        public void count(BeanInfo bean) {
            var declaration = bean.declaringClass();
            if (declaration.name().equals("vi.Impl") || declaration.name().equals("vi.Other")) {
                SEEN.add(declaration.name() + " @Deprecated x"
                        + declaration.annotations(annotation -> annotation.name().equals(Deprecated.class.getName()))
                        .size());
            }
        }
    }

    /**
     * {@code @Registration} sees a class as {@code @Enhancement} left it, the {@code @Deprecated} an extension added
     * once, in the three places. The processor gave none (BUG-20261117-10).
     */
    @Test
    @DisplayName("@Registration sees the annotations @Enhancement added, from the processor, at start and frozen")
    void registrationSeesTheAnnotationsEnhancementAddedFromTheProcessorAtStartAndFrozen() throws Exception {
        var seen = seenThreeWays(withListenerSubtypes(), DeprecatingExtension.class, DeprecatingExtension.SEEN);

        seen.assertEachIs(List.of("vi.Impl @Deprecated x1", "vi.Other @Deprecated x1"));
    }

    /** Records the {@code @Dependent} classes {@code @Enhancement} gives it as subtypes of {@code java.util.EventListener}. */
    public static class ListenerEnhancer implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Enhancement(types = java.util.EventListener.class, withSubtypes = true, withAnnotations = Dependent.class)
        public void listeners(ClassInfo type) {
            SEEN.add(type.name());
        }
    }

    /**
     * {@code @Enhancement(withSubtypes = true)} is called for each type of the deployment that is a subtype of an
     * expected type: {@code vi.Impl} has it through the class it extends, {@code vi.Other} through an interface it
     * implements, which extends it. The processor matched a class against the superclasses its index holds and the
     * interfaces the class declares itself, and missed both, which the container finds at start by loading them
     * (BUG-20261117-07). Each is counted once: the container calls the method twice for each, at start and at build
     * (BUG-20261117-08).
     */
    @Test
    @DisplayName("@Enhancement gets the subtypes of its types from the processor, at start and frozen")
    void enhancementGetsTheSubtypesOfItsTypesFromTheProcessorAtStartAndFrozen() throws Exception {
        var seen = seenThreeWays(withListenerSubtypes(), ListenerEnhancer.class, ListenerEnhancer.SEEN);

        seen.distinct().assertEachIs(List.of("vi.Impl", "vi.Other"));
    }

    /**
     * CDI 4.1 calls an {@code @Enhancement} method once for each type it matches. The container calls it twice at
     * start, and the build that freezes it too: once in the enhancement pass over the classes no processor saw, and
     * once more in the full run of the extensions, each with an instance of its own (BUG-20261117-08).
     */
    @Test
    @DisplayName("@Enhancement is called once for each type, from the processor, at start and frozen")
    void enhancementIsCalledOnceForEachTypeFromTheProcessorAtStartAndFrozen() throws Exception {
        var seen = seenThreeWays(withListenerSubtypes(), ListenerEnhancer.class, ListenerEnhancer.SEEN);

        seen.assertEachIs(List.of("vi.Impl", "vi.Other"));
    }

    /**
     * The sources of {@link #withASerializableInterceptor()}, with two {@code @Dependent} subtypes of
     * {@code java.util.EventListener}: {@code vi.Impl} through the class it extends, {@code vi.Other} through the
     * interface it implements; neither of these two carries {@code @Dependent}.
     */
    private static Map<String, String> withListenerSubtypes() {
        var sources = withASerializableInterceptor();
        sources.put("vi/Base.java", """
                package vi;
                public abstract class Base implements java.util.EventListener {
                }
                """);
        sources.put("vi/Impl.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Impl extends Base {
                }
                """);
        sources.put("vi/Sub.java", """
                package vi;
                public interface Sub extends java.util.EventListener {
                }
                """);
        sources.put("vi/Other.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Other implements Sub {
                }
                """);
        return sources;
    }

    /**
     * A bean has the types of the classes it extends. The container reads those of a class its index does not hold by
     * loading it, which a class the compilation compiles does not on the processor, so the processor's bean types
     * stopped there: an extension it ran did not see {@code vi.SerialService} as a {@code Serializable}, which the
     * container sees at start (BUG-20261117-03). The processor reads such a class as it describes it.
     */
    @Test
    @DisplayName("an extension run by the processor sees the types a bean has through a superclass it compiles")
    void anExtensionRunByTheProcessorSeesTheTypesABeanHasThroughASuperclassItCompiles() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/SerialService.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class SerialService extends Wrapping {
                }
                """);

        var seen = seenThreeWays(sources, InterceptorRecorder.class, InterceptorRecorder.SEEN);

        seen.assertEachIs(List.of(INTERCEPTOR + LOGGER, SERIALIZABLE + LOGGER, SERIALIZABLE + SERIAL_SERVICE));
    }

    /**
     * The processor resolves the injection points of the compilation with the bean types it computes, as the
     * container does at start with its own. An injection point of {@code Serializable} that only
     * {@code vi.SerialService} satisfies, through {@code vi.Wrapping}, a class the compilation compiles, was unsatisfied
     * at build and failed the compilation (BUG-20261117-03). It resolves to that bean at build, at start and frozen.
     */
    @Test
    @DisplayName("an injection point of a type a bean has through a superclass the compilation compiles resolves at build")
    void anInjectionPointOfATypeABeanHasThroughASuperclassItCompilesResolvesAtBuildAsAtStart() throws Exception {
        var seen = seenThreeWays(withASerializableInjectionPoint(), InterceptorRecorder.class,
                InterceptorRecorder.SEEN, (container, loader) -> assertEquals(SERIAL_SERVICE,
                        hello(container, loader.loadClass(SERIAL_CLIENT)).getClass().getName(),
                        "the bean the Serializable injection point resolves to"));

        seen.assertEachIs(List.of(INTERCEPTOR + LOGGER, SERIALIZABLE + LOGGER, SERIALIZABLE + SERIAL_SERVICE));
    }

    /**
     * A second {@code @Serial Serializable} bean makes the injection point of
     * {@link #withASerializableInjectionPoint()} ambiguous. The processor, which did not see {@code vi.SerialService}
     * as a {@code Serializable}, resolved it to the other bean and built an application the container refuses at start
     * (BUG-20261117-03). The build now refuses it too.
     */
    @Test
    @DisplayName("two beans of a type one has through a superclass the compilation compiles are ambiguous at build")
    void twoBeansOfATypeOneHasThroughASuperclassItCompilesAreAmbiguousAtBuildAsAtStart() throws Exception {
        var sources = withASerializableInjectionPoint();
        sources.put("vi/OtherSerial.java", """
                package vi;
                @Serial
                @jakarta.enterprise.context.Dependent
                public class OtherSerial implements java.io.Serializable {
                }
                """);

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        var unvalidated = ProcessorCompiler.compile(workDir.resolve("unvalidated"), sources,
                List.of("-Avauban.validation=false"));

        assertFalse(built.success(), "the build resolved the Serializable injection point");
        assertTrue(built.mentions(AMBIGUOUS), () -> String.join("\n", built.messages()));
        assertTrue(unvalidated.success(), () -> "compilation failed: " + unvalidated.messages());
        try (var loader = loader(unvalidated)) {
            var builder = VaubanContainer.builder().classLoader(loader).scanClasspath();
            var refused = assertThrows(RuntimeException.class, builder::build, "the container starts");
            assertTrue(String.valueOf(refused.getMessage()).contains(AMBIGUOUS), refused::toString);
        }
    }

    /**
     * {@link #withASerializableInterceptor()}, with {@code vi.SerialService}, a {@code @Serial} bean that extends
     * {@code vi.Wrapping}, and {@code vi.SerialClient}, which injects a {@code @Serial Serializable} and supplies it.
     */
    private static Map<String, String> withASerializableInjectionPoint() {
        var sources = withASerializableInterceptor();
        sources.put("vi/Serial.java", """
                package vi;
                @jakarta.inject.Qualifier
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Serial {
                }
                """);
        sources.put("vi/SerialService.java", """
                package vi;
                @Serial
                @jakarta.enterprise.context.Dependent
                public class SerialService extends Wrapping {
                }
                """);
        sources.put("vi/SerialClient.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class SerialClient implements java.util.function.Supplier<Object> {
                    @jakarta.inject.Inject
                    @Serial
                    java.io.Serializable serial;
                    @Override
                    public Object get() {
                        return serial;
                    }
                }
                """);
        return sources;
    }

    /**
     * A bean has the parameterized types of the classes and interfaces above it: {@code vi.types.Text implements
     * Callable<String>} is a {@code Callable<String>} and no {@code Callable<Integer>}, so an injection point of
     * {@code Callable<String>} resolves to it and not to {@code Count implements Callable<Integer>}; through interfaces
     * the compilation compiles, one of {@code Repo<String>} resolves to {@code TextsImpl implements Texts}, where
     * {@code Texts extends Repo<String>}, and not to {@code CountsImpl}. The container resolves both so at start. The
     * processor named the supertypes of a class it compiles raw, and a raw bean type matches every parameterization:
     * the build reported both injection points ambiguous (BUG-20261117-14). It deferred the second to the container
     * while its bean types stopped at {@code Texts} (BUG-20261117-03).
     */
    @Test
    @DisplayName("an injection point of a parameterized type resolves at build as at start")
    void anInjectionPointOfAParameterizedTypeResolvesAtBuildAsAtStart() throws Exception {
        var sources = withTwoCallables(withTwoRepositories(REPO_OF_STRING, "Repo<Integer>"));
        sources.put("vi/types/Readers.java", """
                package vi.types;
                @jakarta.enterprise.context.Dependent
                public class Readers implements java.util.function.Supplier<Object> {
                    @jakarta.inject.Inject
                    java.util.concurrent.Callable<String> text;
                    @jakarta.inject.Inject
                    Repo<String> texts;
                    @Override
                    public Object get() {
                        return text.getClass().getName() + " " + texts.getClass().getName();
                    }
                }
                """);

        seenThreeWays(sources, RegistrationRecorder.class, RegistrationRecorder.SEEN, (container, loader) ->
                assertEquals(TYPES + "Text " + TYPES + TEXTS_IMPL,
                        hello(container, loader.loadClass(TYPES + "Readers"))));
    }

    /**
     * {@code TextsImpl implements Texts}, where {@code Texts extends Repo<String>}, is a {@code Repo<String>}, and
     * {@code CountsImpl}, through {@code Counts extends Repo<Integer>}, a {@code Repo<Integer>}: no annotation brings
     * {@code Texts}, {@code Counts} or {@code Repo} into the index. The processor reads the type arguments from the
     * compilation, so its bean types are those the container computes at start, and an injection point of
     * {@code Repo<String>} resolves to {@code TextsImpl} at build, at start and frozen (BUG-20261117-14).
     */
    @Test
    @DisplayName("a parameterized type a bean has through an interface the compilation compiles resolves at build")
    void aParameterizedTypeABeanHasThroughAnInterfaceItCompilesResolvesAtBuildAsAtStart() throws Exception {
        var sources = withTwoRepositories(REPO_OF_STRING, "Repo<Integer>");
        sources.put(READER_SOURCE, reader(REPO_OF_STRING));

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, readerGets(TEXTS_IMPL));

        seen.assertEachIs(List.of(
                "vi.types.CountsImpl: java.lang.Object, vi.types.Counts, vi.types.CountsImpl,"
                        + " vi.types.Repo<java.lang.Integer>",
                READER_TYPES,
                "vi.types.TextsImpl: java.lang.Object, vi.types.Repo<java.lang.String>, vi.types.Texts,"
                        + " vi.types.TextsImpl"));
    }

    /**
     * A type variable resolves along the classes and interfaces above a bean: {@code Texts extends Base<String>}, where
     * {@code Base<B> extends Repo<B>}, makes {@code TextsImpl} a {@code Base<String>} and a {@code Repo<String>}, at
     * build as at start (BUG-20261117-14).
     */
    @Test
    @DisplayName("a type variable resolves along the interfaces the compilation compiles, at build as at start")
    void aTypeVariableResolvesAlongTheInterfacesTheCompilationCompilesAtBuildAsAtStart() throws Exception {
        var sources = withTwoRepositories("Base<String>", "Base<Integer>");
        putType(sources, "Base", "public interface Base<B> extends Repo<B> {\n}");
        sources.put(READER_SOURCE, reader(REPO_OF_STRING));

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, readerGets(TEXTS_IMPL));

        seen.assertEachIs(List.of(
                "vi.types.CountsImpl: java.lang.Object, vi.types.Base<java.lang.Integer>, vi.types.Counts,"
                        + " vi.types.CountsImpl, vi.types.Repo<java.lang.Integer>",
                READER_TYPES,
                "vi.types.TextsImpl: java.lang.Object, vi.types.Base<java.lang.String>,"
                        + " vi.types.Repo<java.lang.String>, vi.types.Texts, vi.types.TextsImpl"));
    }

    /**
     * {@code Text implements Callable<String>}, a bean the index holds, is a {@code Callable<String>} at build as at
     * start. The container reads the type arguments by reflection on the bean class, which does not load in the
     * processor: the processor's bean type was the raw {@code Callable}, from the index (BUG-20261117-14).
     */
    @Test
    @DisplayName("a parameterized type a bean declares resolves at build as at start")
    void aParameterizedTypeABeanDeclaresResolvesAtBuildAsAtStart() throws Exception {
        var sources = withTwoCallables(withAVetoedPackage());
        sources.put(READER_SOURCE, reader("java.util.concurrent.Callable<String>"));

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, readerGets("Text"));

        seen.assertEachIs(List.of(
                "vi.types.Count: java.lang.Object, java.util.concurrent.Callable<java.lang.Integer>, vi.types.Count",
                READER_TYPES,
                "vi.types.Text: java.lang.Object, java.util.concurrent.Callable<java.lang.String>, vi.types.Text"));
    }

    /**
     * Beans whose superclass, interface or type argument the compilation does not find: javac reports the missing
     * classes, and the processor, which reads the supertypes of a bean from the compiler's model, still runs its
     * deployment instead of failing on the erroneous types: it generates the components of {@code vi.types}
     * (BUG-20261117-14).
     */
    @Test
    @DisplayName("a supertype the compilation does not find leaves javac's error, not a processor failure")
    void aSupertypeTheCompilationDoesNotFindLeavesJavacsError() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Broken", DEPENDENT + "public class Broken extends Gone<String> implements Missing<String>,"
                + " java.util.function.Supplier<Lost> {\n    public Lost get() {\n        return null;\n    }\n}");
        putType(sources, "Kept", DEPENDENT + "public class Kept implements Holder<Lost> {\n}");
        putType(sources, HOLDER, HOLDER_SOURCE);

        var built = ProcessorCompiler.compile(workDir.resolve("broken"), sources);

        assertFalse(built.success(), "the compilation passed");
        assertTrue(built.mentions("Gone") && built.mentions("Missing") && built.mentions("Lost"),
                () -> String.join("\n", built.messages()));
        assertTrue(built.mentions("Generated vi.types._VaubanComponents"),
                () -> "the processor did not run its deployment: " + String.join("\n", built.messages()));
    }

    /**
     * The processor's bean types equal the container's for every shape a type argument takes: wildcards, arrays, a
     * nested class, a type variable with an intersection bound that refers to itself, a raw supertype, a chain of
     * superclasses that pass a type variable on, a JDK superclass, and a producer (BUG-20261117-14). They are the final
     * bean types, after CDI 4.1's rule on legal bean types (2.2.1): no type with a wildcard type parameter, as
     * {@code Pair<List<? extends Number>, List<? super Integer>>}, nor its raw class, and no type variable a supertype
     * the class uses raw declares, as the {@code B} of {@code Holder<B>} above {@code RawUser implements Base}, but the
     * raw {@code Holder}, as Java erases the types above a raw type (JLS 4.8). The container's {@code Bean.getTypes()}
     * has them too, the type of a generic bean class itself with its type variables, {@code Ranked<T extends Number &
     * Comparable<T>>}, which the descriptor named raw until BUG-20261117-16.
     */
    @Test
    @DisplayName("the processor's bean types equal the container's for every shape of type argument")
    void theProcessorsBeanTypesEqualTheContainersForEveryShapeOfTypeArgument() throws Exception {
        var rankedVariable = "<T extends java.lang.Number & java.lang.Comparable<T>>";
        var ranked = "vi.types.Ranked: java.lang.Object, vi.types.Holder" + rankedVariable + ", vi.types.Ranked"
                + rankedVariable;
        var unchanged = List.of(
                "vi.types.Library: java.lang.Object, vi.types.BookShelf<java.lang.String>,"
                        + " vi.types.Holder<java.util.List<java.lang.String>>, vi.types.Library,"
                        + " vi.types.Shelf<java.lang.String>",
                "vi.types.Names: java.io.Serializable, java.lang.Cloneable, java.lang.Iterable<java.lang.String>,"
                        + " java.lang.Object, java.util.AbstractCollection<java.lang.String>,"
                        + " java.util.AbstractList<java.lang.String>, java.util.ArrayList<java.lang.String>,"
                        + " java.util.Collection<java.lang.String>, java.util.List<java.lang.String>,"
                        + " java.util.RandomAccess, java.util.SequencedCollection<java.lang.String>, vi.types.Names",
                "vi.types.RawUser: java.lang.Object, vi.types.Base, vi.types.Holder, vi.types.RawUser",
                "vi.types.Shapes: java.lang.Object, vi.types.Outer$Inner<java.lang.String[]>, vi.types.Shapes,"
                        + " vi.types.Wild");
        var factory = List.of("producer of vi.types.Factory: java.lang.Object, vi.types.Wild",
                "vi.types.Factory: java.lang.Object, vi.types.Factory");

        var container = new ContainerTypes();

        var seen = seenThreeWays(withEveryShapeOfTypeArgument(), BeanTypeRecorder.class, BeanTypeRecorder.SEEN,
                container);

        var expected = sortedConcat(factory, unchanged, List.of(ranked));
        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * A raw bean type is assignable to a parameterized required type only "if the raw types are identical and all type
     * parameters of the required type are either unbounded type variables or java.lang.Object" (CDI 4.1, 5.2.4): a
     * producer of a raw {@code Foo}, where {@code Foo<T>}, satisfies no {@code @Inject Foo<Integer>}. The build and
     * the start resolved that injection point to it, as they took a raw bean type for any parameterization of its
     * class, while {@code BeanManager.getBeans} returned no bean (BUG-20261117-16). The start and the freeze now refuse
     * it with the same message. The build refuses it when {@code Foo} is a class, and leaves it to the container with
     * a warning when it is an interface, which a bean of another archive may implement, as for any unsatisfied
     * injection point of an interface.
     */
    @ParameterizedTest
    @ValueSource(strings = {"class", "interface"})
    @DisplayName("a producer of a raw type satisfies no other parameterization, at build, at start and frozen")
    void aProducerOfARawTypeSatisfiesNoOtherParameterizationAtBuildAtStartAndFrozen(String kind) throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Foo", "public " + kind + " Foo<T> {\n}");
        putType(sources, "FooFactory", RAW_FOO_FACTORY);
        sources.put(READER_SOURCE, reader("Foo<Integer>"));

        assertUnsatisfiedThreeWays(sources, "ParameterizedType[rawType=vi.types.Foo,"
                + " typeArguments=[ClassType[name=java.lang.Integer]]]", "interface".equals(kind));
    }

    /**
     * A producer of a raw {@code Foo} satisfies {@code @Inject Foo<Object>}, at build, at start and frozen: each type
     * argument of the required type is {@code Object} (CDI 4.1, 5.2.4). Its bean types hold the raw {@code Foo}.
     */
    @Test
    @DisplayName("a producer of a raw type satisfies its parameterization by Object, at build, at start and frozen")
    void aProducerOfARawTypeSatisfiesItsParameterizationByObjectAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Foo", "public interface Foo<T> {\n}");
        putType(sources, "FooFactory", RAW_FOO_FACTORY);
        sources.put(READER_SOURCE, reader("Foo<Object>"));
        var got = new ArrayList<String>();

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, resolvedInto(got));

        assertAll(() -> seen.assertEachIs(List.of("producer of vi.types.FooFactory: java.lang.Object, vi.types.Foo",
                        "vi.types.FooFactory: java.lang.Object, vi.types.FooFactory", READER_TYPES)),
                () -> assertEquals(List.of(TYPES + "FooFactory$1", TYPES + "FooFactory$1"), got,
                        "the bean the injection point resolves to, at start, run and frozen"));
    }

    /**
     * A {@code @Dependent} bean of the class {@code Repo<T>} satisfies {@code @Inject Repo<String>}, at build, at start
     * and frozen: its bean type {@code Repo<T>} is assignable to it by the rule on a type variable (CDI 4.1, 5.2.4).
     * The descriptor named the class raw, {@code Repo}, which satisfied it only through the rule BUG-20261117-16
     * removes: the strict rule alone made such a bean unsatisfied.
     */
    @Test
    @DisplayName("a bean of a generic class is injectable as a parameterization of it, at build, at start and frozen")
    void aBeanOfAGenericClassIsInjectableAsAParameterizationOfItAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Repo", DEPENDENT + "public class Repo<T> {\n}");
        sources.put(READER_SOURCE, reader(REPO_OF_STRING));
        var got = new ArrayList<String>();

        seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, resolvedInto(got));

        assertEquals(List.of(TYPES + "Repo", TYPES + "Repo"), got,
                "the bean the injection point resolves to, at start, run and frozen");
    }

    /**
     * The bean types of a managed bean include the bean class (CDI 4.1, 3.1.2), with its type parameters: the bean of
     * {@code Repo<T>} has the bean type {@code Repo<T>}, that of {@code Sorted<T extends Number & Comparable<T>>} the
     * type with its bounds, and a {@code @Typed} that names a generic class gives it too, in the processor, at start
     * and frozen, as in {@code Bean.getTypes()}. The descriptor named each class raw (BUG-20261117-16).
     */
    @Test
    @DisplayName("a bean of a generic class has its class with its type variables as a bean type")
    void aBeanOfAGenericClassHasItsClassWithItsTypeVariablesAsABeanType() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, HOLDER, HOLDER_SOURCE);
        putType(sources, "Repo", DEPENDENT + "public class Repo<T> {\n}");
        putType(sources, "Sorted", DEPENDENT + "public class Sorted<T extends Number & Comparable<T>> {\n}");
        putType(sources, "TypedRepo", DEPENDENT + """
                @jakarta.enterprise.inject.Typed(TypedRepo.class)
                public class TypedRepo<T> implements Holder<T> {
                }""");
        var container = new ContainerTypes();

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, container);

        var expected = List.of("vi.types.Repo: java.lang.Object, vi.types.Repo<T>",
                "vi.types.Sorted: java.lang.Object, vi.types.Sorted<T extends java.lang.Number &"
                        + " java.lang.Comparable<T>>",
                "vi.types.TypedRepo: java.lang.Object, vi.types.TypedRepo<T>");
        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * A bean of {@code Counter<T extends Number>} satisfies {@code @Inject Counter<Integer>}, at build, at start and
     * frozen: {@code Integer} is assignable to the upper bound of {@code T} (CDI 4.1, 5.2.4). Java refuses
     * {@code Counter<String>} in source; the bean's {@code Shelf<T>}, its superclass, says what the rule does with a
     * type outside the bound.
     */
    @Test
    @DisplayName("a bean of a bounded generic class is injectable within its bound, at build, at start and frozen")
    void aBeanOfABoundedGenericClassIsInjectableWithinItsBoundAtBuildAtStartAndFrozen() throws Exception {
        var sources = withACounter();
        sources.put(READER_SOURCE, reader("Counter<Integer>"));
        var got = new ArrayList<String>();

        seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, resolvedInto(got));

        assertEquals(List.of(TYPES + "Counter", TYPES + "Counter"), got,
                "the bean the injection point resolves to, at start, run and frozen");
    }

    /**
     * A bean of {@code Chained<U extends Number, T extends U> implements Holder<T>} satisfies {@code @Inject
     * Holder<Integer>} at build, at start and frozen, and the container injects it: {@code Integer} is assignable to
     * the uppermost bound of {@code T}, {@code Number} (CDI 4.1, 5.2.4, rule e). The validation read that bound and
     * passed; {@code BeanManager.getBeans}, which the injection reads at run time, read {@code U} and found no bean
     * (BUG-20261117-16).
     */
    @Test
    @DisplayName("a bean whose type variable is bounded by another is injectable within the uppermost bound")
    void aBeanWhoseTypeVariableIsBoundedByAnotherIsInjectableWithinTheUppermostBound() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Holder", "public interface Holder<H> {\n}");
        putType(sources, "Chained", DEPENDENT
                + "public class Chained<U extends Number, T extends U> implements Holder<T> {\n}");
        sources.put(READER_SOURCE, reader("Holder<Integer>"));
        var got = new ArrayList<String>();

        seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, resolvedInto(got));

        assertEquals(List.of(TYPES + "Chained", TYPES + "Chained"), got,
                "the bean the injection point resolves to, at start, run and frozen");
    }

    /**
     * A bean of {@code Counter<T extends Number> extends Shelf<T>} does not satisfy {@code @Inject Shelf<String>}, at
     * build, at start and frozen: {@code String} is not assignable to the upper bound of {@code T} (CDI 4.1, 5.2.4).
     */
    @Test
    @DisplayName("a bean of a bounded generic class satisfies no type outside its bound, at build, at start and frozen")
    void aBeanOfABoundedGenericClassSatisfiesNoTypeOutsideItsBoundAtBuildAtStartAndFrozen() throws Exception {
        var sources = withACounter();
        sources.put(READER_SOURCE, reader("Shelf<String>"));

        assertUnsatisfiedThreeWays(sources, "ParameterizedType[rawType=vi.types.Shelf,"
                + " typeArguments=[ClassType[name=java.lang.String]]]", false);
    }

    /**
     * A parameterized bean type is assignable to a raw required type only if each of its type arguments is
     * {@code Object} or an unbounded type variable (CDI 4.1, 5.2.4): a bean of {@code Counter<T extends Number>}, whose
     * bean type is {@code Counter<T extends Number>}, does not satisfy {@code @Inject Counter}, at build, at start and
     * frozen. The descriptor named the class raw, and the raw {@code Counter} satisfied it, while
     * {@code BeanManager.getBeans} of {@code Counter} found no bean (BUG-20261117-16).
     */
    @Test
    @DisplayName("a bean of a bounded generic class does not satisfy its raw type, at build, at start and frozen")
    void aBeanOfABoundedGenericClassDoesNotSatisfyItsRawTypeAtBuildAtStartAndFrozen() throws Exception {
        var sources = withACounter();
        sources.put(READER_SOURCE, reader("Counter").replace("    @jakarta.inject.Inject",
                "    @SuppressWarnings(\"rawtypes\")\n    @jakarta.inject.Inject"));

        assertUnsatisfiedThreeWays(sources, "ClassType[name=vi.types.Counter]", false);
    }

    /**
     * An observer's parameter besides the event parameter, a disposer's besides the disposed one and a producer
     * method's are injection points, and an injection point of a type variable is a definition error (CDI 4.1,
     * 5.2.3). The processor warns, as of every definition error; the start refuses the deployment with its
     * extensions run, and the build's freeze refuses to freeze it, so no frozen start comes after. The start looked
     * the disposer's {@code T extends Gadget} up by its erased class, and checked none of them (BUG-20261117-16, fix
     * round 2).
     */
    @Test
    @DisplayName("a type-variable parameter of an observer or a disposer is a definition error, at build and at start")
    void aParameterOfATypeVariableIsADefinitionErrorAtBuildAndAtStart() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Gadget", DEPENDENT + "public class Gadget {\n}");
        putType(sources, "Widget", "public class Widget {\n}");
        putType(sources, "WidgetFactory", DEPENDENT + """
                public class WidgetFactory<T extends Gadget> {
                    @jakarta.enterprise.inject.Produces
                    Widget widget() {
                        return new Widget();
                    }

                    void dispose(@jakarta.enterprise.inject.Disposes Widget widget, T gadget) {
                    }
                }""");
        putType(sources, "GadgetListener", DEPENDENT + """
                public class GadgetListener<T extends Gadget> {
                    void on(@jakarta.enterprise.event.Observes String event, T gadget) {
                    }
                }""");
        putType(sources, "Sprocket", "public class Sprocket {\n}");
        putType(sources, "SprocketFactory", DEPENDENT + """
                public class SprocketFactory<T extends Gadget> {
                    @jakarta.enterprise.inject.Produces
                    Sprocket sprocket(T gadget) {
                        return new Sprocket();
                    }
                }""");
        assertDefinitionErrorsAtBuildAtStartAndFrozen(sources, List.of(
                "Injection point has type variable type T: vi.types.WidgetFactory.dispose disposer param 1",
                "Injection point has type variable type T: vi.types.GadgetListener.on observer param 1",
                "Injection point has type variable type T: vi.types.SprocketFactory.sprocket producer param 0"));
    }

    /**
     * An initializer's parameter is an injection point, and one of a type variable is a definition error (CDI 4.1,
     * 5.2.3), on a class that may be a bean: {@code GadgetSetter<T extends Gadget>}. An initializer a bean inherits
     * from an abstract class is checked as the bean class gives it, {@code T} as {@code Gadget}: no error. The start
     * checked every class of the index, and refused {@code AbstractGadgetSetter}; the build checked none, and did
     * not warn of {@code GadgetSetter} (BUG-20261117-30).
     */
    @Test
    @DisplayName("an initializer's parameter of a type variable is a definition error on a class that may be a bean")
    void anInitializersParameterOfATypeVariableIsADefinitionErrorOnAClassThatMayBeABean() throws Exception {
        var sources = withAnInheritedGadgetSetter();
        putType(sources, "GadgetSetter", DEPENDENT + """
                public class GadgetSetter<T extends Gadget> {
                    @jakarta.inject.Inject
                    void set(T gadget) {
                    }
                }""");

        var refused = assertDefinitionErrorsAtBuildAtStartAndFrozen(sources, List.of(
                "Injection point has type variable type T: vi.types.GadgetSetter.set initializer param 0"));
        assertFalse(refused.contains("AbstractGadgetSetter"), refused);
    }

    /**
     * An {@code @Inject} field of a type variable is an injection point of a type variable, a definition error (CDI
     * 4.1, 5.2.3), the field declared by the bean class, {@code GadgetField<T>}, or inherited from a vetoed class
     * that the bean class leaves it a type variable, {@code GenericFieldOfVetoed<T> extends VetoedGadgetField<T>}. No
     * rule checked a field, and the start looked {@code GadgetField}'s up by its erasure (BUG-20261117-29).
     */
    @Test
    @DisplayName("an @Inject field of a type variable is a definition error, at build, at start and frozen")
    void anInjectFieldOfATypeVariableIsADefinitionErrorAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Gadget", DEPENDENT + "public class Gadget {\n}");
        putType(sources, "GadgetField", DEPENDENT + """
                public class GadgetField<T> {
                    @jakarta.inject.Inject
                    T gadget;
                }""");
        putType(sources, "VetoedGadgetField", VETOED + """

                public class VetoedGadgetField<T> {
                    @jakarta.inject.Inject
                    T gadget;
                }""");
        putType(sources, "GenericFieldOfVetoed", DEPENDENT
                + "public class GenericFieldOfVetoed<T> extends VetoedGadgetField<T> {\n}");
        putType(sources, "GadgetFieldOfVetoed", DEPENDENT
                + "public class GadgetFieldOfVetoed extends VetoedGadgetField<Gadget> {\n}");

        var refused = assertDefinitionErrorsAtBuildAtStartAndFrozen(sources, List.of(
                "Injection point has type variable type T: vi.types.GadgetField.gadget",
                "Injection point has type variable type T: vi.types.GenericFieldOfVetoed.gadget"));
        assertFalse(refused.contains("GadgetFieldOfVetoed.gadget"), refused);
    }

    /**
     * A concrete class without a bean-defining annotation is no bean in the annotated discovery mode: the
     * {@code @Inject T gadget} field {@code PlainGadgetField<T>} declares is its bean subclass's, given a
     * {@code Gadget}. The start refused it as an injection point of a type variable, and the build did not warn
     * (BUG-20261117-29, review round 1).
     */
    @Test
    @DisplayName("an @Inject field of a class without a bean-defining annotation is inherited, build, start, frozen")
    void anInjectFieldOfAClassWithoutABeanDefiningAnnotationIsInheritedAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Gadget", DEPENDENT + "public class Gadget {\n}");
        putType(sources, "PlainGadgetField", """
                public class PlainGadgetField<T> implements java.util.function.Supplier<Object> {
                    @jakarta.inject.Inject
                    T gadget;
                    @Override
                    public Object get() {
                        return gadget.getClass().getName();
                    }
                }""");
        putType(sources, "GadgetFieldOfPlain", DEPENDENT
                + "public class GadgetFieldOfPlain extends PlainGadgetField<Gadget> {\n}");

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        assertTrue(built.success(), () -> String.join("\n", built.messages()));
        assertTrue(built.messages().stream().noneMatch(m -> m.contains("type variable type")),
                () -> String.join("\n", built.messages()));
        var gets = (StartedCheck) (container, loader) -> assertEquals(TYPES + "Gadget",
                hello(container, loader.loadClass(TYPES + "GadgetFieldOfPlain")), "what the field got");
        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, gets);
        assertFalse(seen.start().isEmpty(), "the extension saw no bean");
    }

    /**
     * As Cassini's {@code CassiniScopeExtension} does for a {@code @Path} class: a class annotated {@code @Named} and
     * nothing else gets {@code @RequestScoped}.
     */
    public static class RequestScopeExtension implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withAnnotations = jakarta.inject.Named.class)
        public void addScope(ClassConfig clazz) {
            clazz.addAnnotation(jakarta.enterprise.context.RequestScoped.class);
        }
    }

    /**
     * A generic managed bean must be {@code @Dependent} (CDI 4.1, 3.1), the scope declared or added by an extension at
     * {@code @Enhancement}, as Weld reads the annotated type the extensions modified: {@code Resource<T>}, which
     * {@link RequestScopeExtension} makes {@code @RequestScoped}, is a definition error. The processor runs the
     * extension and warns; the start of what it compiled applies the scope the processor froze, the extension not
     * run again, and refuses the deployment. Vauban accepted it, and Cassini looked behind the client proxy of such a
     * resource (BUG-20261117-35). {@code GenericBeanScopeTest} runs the extension at start.
     */
    @Test
    @DisplayName("a normal scope an extension adds to a generic class is a definition error, at build, start, frozen")
    void aNormalScopeAnExtensionAddsToAGenericClassIsADefinitionErrorAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Resource", "@jakarta.inject.Named\npublic class Resource<T> {\n}");
        putType(sources, "PlainResource", "@jakarta.inject.Named\npublic class PlainResource {\n}");
        var error = "Managed bean vi.types.Resource has type parameters and is not @Dependent:"
                + " @jakarta.enterprise.context.RequestScoped";

        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(RequestScopeExtension.class));
        var processed = ProcessorCompiler.compile(workDir.resolve("processed"), sources, List.of(), processor);
        assertTrue(processed.messages().contains("WARNING: [Vauban] " + error
                + " — the container rejects this deployment at start."),
                () -> String.join("\n", processed.messages()));
        assertTrue(processed.success(), () -> String.join("\n", processed.messages()));
        registerExtension(processed, RequestScopeExtension.class);
        try (var loader = loader(processed)) {
            var builder = VaubanContainer.builder().classLoader(loader).scanClasspath();
            var frozen = assertThrows(DefinitionException.class, builder::build, "the container started")
                    .getMessage();
            assertTrue(frozen.contains("\n  - " + error), frozen);
            assertFalse(frozen.contains("PlainResource"), frozen);
        }
    }

    /**
     * The initializer {@code GadgetSetterOfAbstract} inherits from {@code AbstractGadgetSetter<T>} is given a
     * {@code Gadget}, at start and frozen; the build does not warn (BUG-20261117-30).
     */
    @Test
    @DisplayName("an inherited initializer's parameter of a type variable is legal, at build, at start and frozen")
    void anInheritedInitializersParameterOfATypeVariableIsLegalAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAnInheritedGadgetSetter();

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        assertTrue(built.success(), () -> String.join("\n", built.messages()));
        assertTrue(built.messages().stream().noneMatch(m -> m.contains("type variable type")),
                () -> String.join("\n", built.messages()));
        var gets = (StartedCheck) (container, loader) -> assertEquals(TYPES + "Gadget",
                hello(container, loader.loadClass(TYPES + "GadgetSetterOfAbstract")), "what the initializer got");
        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, gets);
        assertFalse(seen.start().isEmpty(), "the extension saw no bean");
    }

    /**
     * A bean that inherits an initializer from a vetoed class and leaves its parameter a type variable of its own,
     * {@code GenericSetterOfVetoed<T> extends VetoedSetter<T>}, has an injection point of a type variable (CDI 4.1,
     * 5.2.3). It started, and failed to call the initializer at the first lookup (BUG-20261117-30, review round 1).
     */
    @Test
    @DisplayName("an inherited initializer's parameter left a type variable is a definition error, build, start")
    void anInheritedInitializersParameterLeftATypeVariableIsADefinitionErrorAtBuildAtStartAndFrozen()
            throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Gadget", DEPENDENT + "public class Gadget {\n}");
        putType(sources, "VetoedSetter", """
                @jakarta.enterprise.inject.Vetoed
                public class VetoedSetter<T> {
                    @jakarta.inject.Inject
                    void set(T gadget) {
                    }
                }""");
        putType(sources, "GenericSetterOfVetoed", DEPENDENT
                + "public class GenericSetterOfVetoed<T> extends VetoedSetter<T> {\n}");

        assertDefinitionErrorsAtBuildAtStartAndFrozen(sources, List.of(
                "Injection point has type variable type T: vi.types.GenericSetterOfVetoed.set initializer param 0"));
    }

    /**
     * A concrete class without a bean-defining annotation is no bean in the annotated discovery mode: the initializer
     * {@code PlainGadgetSetter<T>} declares is its bean subclass's, given a {@code Gadget}. The start refused it as an
     * injection point of a type variable, where the build did not warn (BUG-20261117-30, review round 1).
     */
    @Test
    @DisplayName("an initializer of a class without a bean-defining annotation is inherited, build, start, frozen")
    void anInitializerOfAClassWithoutABeanDefiningAnnotationIsInheritedAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAnInheritedGadgetSetter();
        sources.remove("vi/types/AbstractGadgetSetter" + JAVA);
        sources.remove("vi/types/GadgetSetterOfAbstract" + JAVA);
        putType(sources, "PlainGadgetSetter", """
                public class PlainGadgetSetter<T> implements java.util.function.Supplier<Object> {
                    private T gadget;
                    @jakarta.inject.Inject
                    void set(T gadget) {
                        this.gadget = gadget;
                    }
                    @Override
                    public Object get() {
                        return gadget.getClass().getName();
                    }
                }""");
        putType(sources, "GadgetSetterOfPlain", DEPENDENT
                + "public class GadgetSetterOfPlain extends PlainGadgetSetter<Gadget> {\n}");

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        assertTrue(built.success(), () -> String.join("\n", built.messages()));
        assertTrue(built.messages().stream().noneMatch(m -> m.contains("type variable type")),
                () -> String.join("\n", built.messages()));
        var gets = (StartedCheck) (container, loader) -> assertEquals(TYPES + "Gadget",
                hello(container, loader.loadClass(TYPES + "GadgetSetterOfPlain")), "what the initializer got");
        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, gets);
        assertFalse(seen.start().isEmpty(), "the extension saw no bean");
    }

    /**
     * An observer's parameter that no bean satisfies fails the start, the observer inherited or not, with the
     * extensions run at start and frozen: {@code InheritedIntegerHolderListener} inherits
     * {@code on(@Observes Pung, Holder<Integer>)}, and the only holder is a {@code Holder<String>}. The start read the
     * observers the bean class declares only, and the call failed at the first event (BUG-20261117-26).
     */
    @Test
    @DisplayName("an inherited observer's unsatisfied parameter fails the start, extensions run and frozen")
    void anInheritedObserversUnsatisfiedParameterFailsTheStartRunAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Holder", "public interface Holder<H> {\n}");
        putType(sources, "TextHolder", DEPENDENT + "public class TextHolder implements Holder<String> {\n}");
        putType(sources, "Pung", "public class Pung {\n}");
        putType(sources, "IntegerHolderListenerBase", """
                public abstract class IntegerHolderListenerBase {
                    void on(@jakarta.enterprise.event.Observes Pung pung, Holder<Integer> holder) {
                    }
                }""");
        putType(sources, "InheritedIntegerHolderListener", DEPENDENT
                + "public class InheritedIntegerHolderListener extends IntegerHolderListenerBase {\n}");

        var refusals = startRefusalsRunAndFrozen(sources);
        for (var refusal : refusals) {
            assertTrue(refusal.contains("unsatisfied dependency for parameter arg1 of type vi.types.Holder"
                    + "<java.lang.Integer>"), refusal);
        }
    }

    /**
     * The build fails on the parameter of an inherited or a static observer that no bean satisfies, or more than one
     * does, as the start does: it resolved the parameters of the instance observers a bean class declares only
     * (BUG-20261118-11). Two beans are tools: {@code InheritedToolListener} inherits {@code on(@Observes Pung, Tool)},
     * and {@code StaticToolListener} declares it static. {@code InheritedPungHolderListener} inherits
     * {@code on(@Observes Pung, Holder<Pung>)}, and the only holder is a {@code Holder<String>}: a type no bean of this
     * compilation declares, it leaves the start to decide with a warning, as for a declared observer. The parameters
     * the container provides, and one of a superclass's type variable the bean class gives {@code String}, are none.
     */
    @Test
    @DisplayName("an inherited or static observer's unresolvable parameter fails the build and the start")
    void anInheritedOrStaticObserversUnresolvableParameterFailsTheBuildAndTheStart() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Holder", "public interface Holder<H> {\n}");
        putType(sources, "TextHolder", DEPENDENT + "public class TextHolder implements Holder<String> {\n}");
        putType(sources, "Pung", "public class Pung {\n}");
        putType(sources, "Tool", "public interface Tool {\n}");
        putType(sources, "Hammer", DEPENDENT + "public class Hammer implements Tool {\n}");
        putType(sources, "Saw", DEPENDENT + "public class Saw implements Tool {\n}");
        putType(sources, "StaticToolListener", DEPENDENT + """
                public class StaticToolListener {
                    static void on(@jakarta.enterprise.event.Observes Pung pung, Tool tool) {
                    }
                }""");
        putType(sources, "ToolListenerBase", """
                public abstract class ToolListenerBase {
                    void on(@jakarta.enterprise.event.Observes Pung pung, Tool tool) {
                    }
                }""");
        putType(sources, "InheritedToolListener", DEPENDENT
                + "public class InheritedToolListener extends ToolListenerBase {\n}");
        putType(sources, "PungHolderListenerBase", """
                public abstract class PungHolderListenerBase {
                    void on(@jakarta.enterprise.event.Observes Pung pung, Holder<Pung> holder) {
                    }
                }""");
        putType(sources, "InheritedPungHolderListener", DEPENDENT
                + "public class InheritedPungHolderListener extends PungHolderListenerBase {\n}");
        putType(sources, "HolderListenerBase", """
                public abstract class HolderListenerBase<X> {
                    void on(@jakarta.enterprise.event.Observes Pung pung, Holder<X> holder,
                            jakarta.enterprise.inject.spi.BeanManager manager,
                            jakarta.enterprise.inject.Instance<Tool> tools) {
                    }
                }""");
        putType(sources, "TextHolderListener", DEPENDENT
                + "public class TextHolderListener extends HolderListenerBase<String> {\n}");

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        assertFalse(built.success(), "the build succeeded");
        var reported = built.messages().stream().filter(m -> m.contains("[Vauban]")).sorted().toList();
        assertAll(() -> assertEquals(3, reported.size(), () -> String.join("\n", reported)),
                () -> assertTrue(reported.get(0).startsWith("ERROR: [Vauban] Ambiguous dependency: parameter 1 of"
                        + " InheritedToolListener.on("), reported.get(0)),
                () -> assertTrue(reported.get(1).startsWith("ERROR: [Vauban] Ambiguous dependency: parameter 1 of"
                        + " StaticToolListener.on("), reported.get(1)),
                () -> assertTrue(reported.get(2).startsWith("WARNING: [Vauban] Unsatisfied dependency: parameter 1 of"
                        + " InheritedPungHolderListener.on("), reported.get(2)));
        for (var refusal : startRefusalsRunAndFrozen(sources)) {
            assertTrue(refusal.contains("dependency for parameter arg1 of type vi.types."), refusal);
        }
    }

    /**
     * The build finds the inherited observers the start finds: {@code SilentToolListener} overrides
     * {@code on(Pung, Tool)} without {@code @Observes}, {@code PrivateToolListenerBase}'s observer is private, and
     * {@code BridgedToolListener} overrides {@code GenericToolListenerBase<Tool>}'s {@code on(Pung, T)}, which the
     * start sees through javac's bridge {@code on(Pung, Object)}, so none is an observer of its bean, and two beans
     * of type {@code Tool} are no error. The build resolved them and refused what the start accepts (BUG-20261118-11,
     * review rounds 2 and 3).
     */
    @Test
    @DisplayName("an observer the start does not inherit is no error at build, at start and frozen")
    void anObserverTheStartDoesNotInheritIsNoErrorAtBuildAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Pung", "public class Pung {\n}");
        putType(sources, "Tool", "public interface Tool {\n}");
        putType(sources, "Hammer", DEPENDENT + "public class Hammer implements Tool {\n}");
        putType(sources, "Saw", DEPENDENT + "public class Saw implements Tool {\n}");
        putType(sources, "ToolListenerBase", """
                public abstract class ToolListenerBase {
                    void on(@jakarta.enterprise.event.Observes Pung pung, Tool tool) {
                    }
                }""");
        putType(sources, "SilentToolListener", DEPENDENT + """
                public class SilentToolListener extends ToolListenerBase {
                    @Override
                    void on(Pung pung, Tool tool) {
                    }
                }""");
        putType(sources, "PrivateToolListenerBase", """
                public abstract class PrivateToolListenerBase {
                    private void on(@jakarta.enterprise.event.Observes Pung pung, Tool tool) {
                    }
                }""");
        putType(sources, "PrivateToolListener", DEPENDENT
                + "public class PrivateToolListener extends PrivateToolListenerBase {\n}");
        putType(sources, "GenericToolListenerBase", """
                public abstract class GenericToolListenerBase<T> {
                    void on(@jakarta.enterprise.event.Observes Pung pung, T tool) {
                    }
                }""");
        putType(sources, "BridgedToolListener", DEPENDENT + """
                public class BridgedToolListener extends GenericToolListenerBase<Tool> {
                    @Override
                    void on(Pung pung, Tool tool) {
                    }
                }""");

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        assertTrue(built.success(), () -> String.join("\n", built.messages()));
        assertTrue(built.messages().stream().noneMatch(m -> m.startsWith("ERROR") || m.startsWith("WARNING")),
                () -> String.join("\n", built.messages()));
        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN);
        assertFalse(seen.start().isEmpty(), "the extension saw no bean");
    }

    /**
     * Compiles {@code sources}, the build's deployment validation off, and lists {@link BeanTypeRecorder} as their
     * extension: the start refuses the deployment with a {@code DeploymentException}, with the extension run at
     * start, and after the build froze it.
     *
     * @return the two messages, extensions run then frozen
     */
    private List<String> startRefusalsRunAndFrozen(Map<String, String> sources) throws Exception {
        var result = ProcessorCompiler.compile(workDir, sources, List.of("-Avauban.validation=false"));
        assertTrue(result.success(), () -> "compilation failed: " + result.messages());
        registerExtension(result, BeanTypeRecorder.class);
        String run;
        try (var loader = loader(result)) {
            run = assertThrows(DeploymentException.class,
                    VaubanContainer.builder().classLoader(loader).scanClasspath()::build, "the container started")
                    .getMessage();
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(result.classes());
            assertTrue(outcome.frozen(), outcome.reason());
        }
        try (var loader = loader(result)) {
            var builder = VaubanContainer.builder().classLoader(loader).scanClasspath();
            var frozen = assertThrows(DeploymentException.class, builder::build, "the frozen container started");
            return List.of(run, frozen.getMessage());
        }
    }

    /**
     * {@link #withAVetoedPackage()}, with the bean {@code Gadget}, the abstract {@code AbstractGadgetSetter<T>} and
     * its bean {@code GadgetSetterOfAbstract extends AbstractGadgetSetter<Gadget>}, which supplies the class of what
     * the inherited initializer got.
     */
    private static Map<String, String> withAnInheritedGadgetSetter() {
        var sources = withAVetoedPackage();
        putType(sources, "Gadget", DEPENDENT + "public class Gadget {\n}");
        putType(sources, "AbstractGadgetSetter", """
                public abstract class AbstractGadgetSetter<T> implements java.util.function.Supplier<Object> {
                    private T gadget;
                    @jakarta.inject.Inject
                    void set(T gadget) {
                        this.gadget = gadget;
                    }
                    @Override
                    public Object get() {
                        return gadget.getClass().getName();
                    }
                }""");
        putType(sources, "GadgetSetterOfAbstract", DEPENDENT
                + "public class GadgetSetterOfAbstract extends AbstractGadgetSetter<Gadget> {\n}");
        return sources;
    }

    /**
     * Compiles {@code sources}: the processor warns of each of {@code errors}, the start refuses the deployment with
     * its extensions run, and the build's freeze refuses to freeze it, so no frozen start comes after.
     *
     * @return the message the start refused the deployment with
     */
    private String assertDefinitionErrorsAtBuildAtStartAndFrozen(Map<String, String> sources, List<String> errors)
            throws Exception {
        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        assertTrue(built.success(), () -> String.join("\n", built.messages()));
        for (var error : errors) {
            assertTrue(built.messages().contains("WARNING: [Vauban] " + error
                    + " — the container rejects this deployment at start."),
                    () -> String.join("\n", built.messages()));
        }
        var unvalidated = ProcessorCompiler.compile(workDir.resolve("unvalidated"), sources,
                List.of("-Avauban.validation=false"));
        assertTrue(unvalidated.success(), () -> "compilation failed: " + unvalidated.messages());
        registerExtension(unvalidated, BeanTypeRecorder.class);
        String run;
        String frozen;
        try (var loader = loader(unvalidated)) {
            run = assertThrows(DefinitionException.class,
                    VaubanContainer.builder().classLoader(loader).scanClasspath()::build, "the container starts")
                    .getMessage();
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(unvalidated.classes());
            assertFalse(outcome.frozen(), "the build froze a deployment with a definition error");
            frozen = outcome.reason();
        }
        for (var error : errors) {
            assertAll(() -> assertTrue(run.contains("\n  - " + error), run),
                    () -> assertTrue(frozen.contains("\n  - " + error), frozen));
        }
        return run;
    }

    /**
     * {@link #withAVetoedPackage()}, with the class {@code Shelf<S>} and the bean
     * {@code Counter<T extends Number> extends Shelf<T>}.
     */
    private static Map<String, String> withACounter() {
        var sources = withAVetoedPackage();
        putType(sources, "Shelf", "public class Shelf<S> {\n}");
        putType(sources, "Counter", DEPENDENT + "public class Counter<T extends Number> extends Shelf<T> {\n}");
        return sources;
    }

    /**
     * Checks that the injection point of {@code vi.types.Reader} in {@code sources}, of the type {@code requiredType}
     * names, is unsatisfied with the same message at build, at start and frozen. The build fails, or, when
     * {@code deferred}, warns and leaves it to the container. The start throws, with the extensions run and with the
     * record the freeze wrote, which holds no validation: the start validates the deployment.
     */
    private void assertUnsatisfiedThreeWays(Map<String, String> sources, String requiredType, boolean deferred)
            throws Exception {
        var messages = unsatisfiedReader(requiredType);
        var atBuild = messages.stream()
                .map(message -> deferred ? "WARNING: [Vauban] " + message + DEFERRED : "ERROR: [Vauban] " + message)
                .toList();

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        var unvalidated = ProcessorCompiler.compile(workDir.resolve("unvalidated"), sources,
                List.of("-Avauban.validation=false"));

        assertTrue(unvalidated.success(), () -> "compilation failed: " + unvalidated.messages());
        assertEquals(deferred, built.success(), () -> String.join("\n", built.messages()));
        assertTrue(built.messages().stream().anyMatch(atBuild::contains), () -> String.join("\n", built.messages()));
        registerExtension(unvalidated, BeanTypeRecorder.class);
        var atStart = messages.stream().map(message -> VALIDATION_FAILED + message + "\n").toList();
        String run;
        try (var loader = loader(unvalidated)) {
            run = assertThrows(DeploymentException.class,
                    VaubanContainer.builder().classLoader(loader).scanClasspath()::build, "the container starts")
                    .getMessage();
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(unvalidated.classes());
            assertTrue(outcome.frozen(), outcome.reason());
        }
        BeanTypeRecorder.SEEN.clear();
        String frozen;
        try (var loader = loader(unvalidated)) {
            frozen = assertThrows(DeploymentException.class,
                    VaubanContainer.builder().classLoader(loader).scanClasspath()::build,
                    "the container starts frozen").getMessage();
        }
        var seenAtStart = List.copyOf(BeanTypeRecorder.SEEN);
        assertAll(() -> assertTrue(atStart.contains(run), run),
                () -> assertTrue(atStart.contains(frozen), frozen),
                () -> assertEquals(List.of(), seenAtStart, "a frozen extension does not run at start"));
    }

    /**
     * What the build and the container say of an unsatisfied {@code vi.types.Reader.injected} of {@code type}, its
     * two qualifiers in each order: the set that holds them has no order of its own.
     */
    private static List<String> unsatisfiedReader(String type) {
        var any = "QualifierInstance[annotationName=jakarta.enterprise.inject.Any, members={}]";
        var defaultQualifier = "QualifierInstance[annotationName=jakarta.enterprise.inject.Default, members={}]";
        var prefix = "Unsatisfied dependency: field Reader.injected of type " + type + " with qualifiers [";
        return List.of(prefix + any + ", " + defaultQualifier + "]", prefix + defaultQualifier + ", " + any + "]");
    }

    /** Adds to {@code got} the class of the bean {@code vi.types.Reader} got, in each container it is given. */
    private static StartedCheck resolvedInto(List<String> got) {
        return (container, loader) -> got.add(String.valueOf(hello(container, loader.loadClass(TYPES + "Reader"))));
    }

    /**
     * A type variable resolves inside a wildcard, an array and the type that encloses an inner class, at start as at
     * build, where the Java compiler resolves it (JLS 4.10.2): {@code WildShelf<S> implements Holder<List<? extends S>>},
     * {@code ArrayShelf<S> implements Holder<S[]>}, and {@code Outer<O>.Inner<I> implements Holder<O>}, each given
     * {@code String}. The container left {@code S} and {@code O} there (BUG-20261117-14). {@code Holder<List<? extends
     * String>>} has a wildcard type parameter, no legal bean type (CDI 4.1, 2.2.1): neither keeps it, nor its raw
     * class.
     */
    @Test
    @DisplayName("a type variable resolves inside a wildcard, an array and an enclosing type, at start as at build")
    void aTypeVariableResolvesInsideAWildcardAnArrayAndAnEnclosingTypeAtStartAsAtBuild() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, HOLDER, HOLDER_SOURCE);
        putType(sources, "WildShelf", "public abstract class WildShelf<S> implements"
                + " Holder<java.util.List<? extends S>> {\n}");
        putType(sources, "WildLibrary", DEPENDENT + "public class WildLibrary extends WildShelf<String> {\n}");
        withAnArrayLibrary(sources);
        putType(sources, "Outer", GENERIC_OUTER_SOURCE);
        putType(sources, "InnerUser", DEPENDENT + "public class InnerUser extends Outer<String>.Inner<Integer> {\n"
                + "    public InnerUser() {\n        new Outer<String>().super();\n    }\n}");
        var expected = List.of(
                "vi.types.ArrayLibrary: java.lang.Object, vi.types.ArrayLibrary, vi.types.ArrayShelf<java.lang.String>,"
                        + " vi.types.Holder<java.lang.String[]>",
                "vi.types.InnerUser: java.lang.Object, vi.types.Holder<java.lang.String>, vi.types.InnerUser,"
                        + " vi.types.Outer$Inner<java.lang.Integer>",
                "vi.types.WildLibrary: java.lang.Object, vi.types.WildLibrary, vi.types.WildShelf<java.lang.String>");

        var container = new ContainerTypes();

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, container);

        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * {@code ArrayLibrary extends ArrayShelf<String>}, where {@code ArrayShelf<S> implements Holder<S[]>}, is a
     * {@code Holder<String[]>}: an injection point of that type resolves to it at build, at start and frozen. The
     * container gave it {@code Holder<S[]>}, and refused at start the injection point the build had resolved
     * (BUG-20261117-14).
     */
    @Test
    @DisplayName("an injection point of a type argument array resolves at start as at build")
    void anInjectionPointOfATypeArgumentArrayResolvesAtStartAsAtBuild() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, HOLDER, HOLDER_SOURCE);
        withAnArrayLibrary(sources);
        sources.put(READER_SOURCE, reader("Holder<String[]>"));

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, readerGets("ArrayLibrary"));

        seen.assertEachIs(List.of("vi.types.ArrayLibrary: java.lang.Object, vi.types.ArrayLibrary,"
                + " vi.types.ArrayShelf<java.lang.String>, vi.types.Holder<java.lang.String[]>", READER_TYPES));
    }

    /**
     * A producer of a parameterized type has the classes and interfaces above it at build as at start, with their type
     * arguments: {@code Box<String>}, where the compilation compiles {@code Box<T> implements Repo<T>}, is a
     * {@code Repo<String>}, and {@code List<Foo>}, where it compiles {@code Foo}, a {@code Collection<Foo>}. The
     * processor gave each its own type and {@code Object} only: neither class loads where it runs (BUG-20261117-14).
     */
    @Test
    @DisplayName("a producer of a parameterized type has the types above it at build as at start")
    void aProducerOfAParameterizedTypeHasTheTypesAboveItAtBuildAsAtStart() throws Exception {
        var sources = withABoxProducer();
        putType(sources, "Foo", "public class Foo {\n}");
        putType(sources, "Foos", DEPENDENT + "public class Foos {\n    @jakarta.enterprise.inject.Produces\n"
                + "    java.util.List<Foo> foos() {\n        return java.util.List.of();\n    }\n}");
        var expected = List.of(
                "producer of vi.types.BoxFactory: java.lang.Object, vi.types.Box<java.lang.String>,"
                        + " vi.types.Repo<java.lang.String>",
                "producer of vi.types.Foos: java.lang.Iterable<vi.types.Foo>, java.lang.Object,"
                        + " java.util.Collection<vi.types.Foo>, java.util.List<vi.types.Foo>,"
                        + " java.util.SequencedCollection<vi.types.Foo>",
                "vi.types.BoxFactory: java.lang.Object, vi.types.BoxFactory",
                "vi.types.Foos: java.lang.Object, vi.types.Foos");

        var container = new ContainerTypes();

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, container);

        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * {@code @Produces Box<String>}, where {@code Box<T> implements Repo<T>}, and {@code TextsImpl implements
     * Repo<String>} make an injection point of {@code Repo<String>} ambiguous, at build as at start. The build resolved
     * it to {@code TextsImpl}, and the start refused it (BUG-20261117-14).
     */
    @Test
    @DisplayName("a producer of a parameterized type and a bean of a type above it are ambiguous at build as at start")
    void aProducerOfAParameterizedTypeAndABeanOfATypeAboveItAreAmbiguousAtBuildAsAtStart() throws Exception {
        var sources = withABoxProducer();
        putType(sources, TEXTS_IMPL, DEPENDENT + "public class TextsImpl implements Repo<String> {\n}");
        sources.put(READER_SOURCE, reader(REPO_OF_STRING));

        var built = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        var unvalidated = ProcessorCompiler.compile(workDir.resolve("unvalidated"), sources,
                List.of("-Avauban.validation=false"));

        assertFalse(built.success(), "the build resolved the Repo<String> injection point");
        assertTrue(built.mentions(AMBIGUOUS), () -> String.join("\n", built.messages()));
        assertTrue(unvalidated.success(), () -> "compilation failed: " + unvalidated.messages());
        try (var loader = loader(unvalidated)) {
            var builder = VaubanContainer.builder().classLoader(loader).scanClasspath();
            var refused = assertThrows(RuntimeException.class, builder::build, "the container starts");
            assertTrue(String.valueOf(refused.getMessage()).contains(AMBIGUOUS), refused::toString);
        }
    }

    /**
     * A producer of {@code Box<List<String>>}, where {@code Box<T> implements Repo<T>}, is a
     * {@code Repo<List<String>>}, at build as at start: an injection point of that type resolves to it. At start, the
     * bean discovery rebuilt the produced type for reflection with {@code Object} for each type argument that is not a
     * class, so it gave the producer {@code Box<Object>} and {@code Repo<Object>}, and the start refused the injection
     * point the build resolved (BUG-20261117-17).
     */
    @Test
    @DisplayName("a producer of a type with a parameterized type argument has the types above it at start as at build")
    void aProducerOfATypeWithAParameterizedTypeArgumentHasTheTypesAboveItAtStartAsAtBuild() throws Exception {
        var sources = withABoxProducer();
        putType(sources, "BoxFactory", DEPENDENT + "public class BoxFactory {\n    @jakarta.enterprise.inject.Produces\n"
                + "    Box<java.util.List<String>> box() {\n        return new Box<>();\n    }\n}");
        sources.put(READER_SOURCE, reader("Repo<java.util.List<String>>"));

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, readerGets("Box"));

        seen.assertEachIs(List.of("producer of vi.types.BoxFactory: java.lang.Object,"
                + " vi.types.Box<java.util.List<java.lang.String>>, vi.types.Repo<java.util.List<java.lang.String>>",
                "vi.types.BoxFactory: java.lang.Object, vi.types.BoxFactory", READER_TYPES));
    }

    /**
     * Producers of a {@code Box} whose type argument is an array of classes, of primitives or of a parameterized type,
     * a type variable, or a parameterized type of a bounded type variable have the types above it with that argument,
     * at build as at start, and so do producers of a {@code Crate<C> implements Repo<C[]>}, where an array argument
     * gives one array of both dimensions. At start the argument became {@code Object} (BUG-20261117-17).
     */
    @Test
    @DisplayName("a producer of a type with a type argument of every shape has the types above it at start as at build")
    void aProducerOfATypeWithATypeArgumentOfEveryShapeHasTheTypesAboveItAtStartAsAtBuild() throws Exception {
        var sources = withABoxProducer();
        putType(sources, "Crate", "public class Crate<C> implements Repo<C[]> {\n}");
        putType(sources, "Boxes", DEPENDENT + """
                public class Boxes {
                    @jakarta.enterprise.inject.Produces
                    Box<String[]> strings() {
                        return new Box<>();
                    }
                    @jakarta.enterprise.inject.Produces
                    Box<java.util.List<String>[]> lists() {
                        return new Box<>();
                    }
                    @jakarta.enterprise.inject.Produces
                    Box<int[][]> ints() {
                        return new Box<>();
                    }
                    @jakarta.enterprise.inject.Produces
                    <T> Box<T> any() {
                        return new Box<>();
                    }
                    @jakarta.enterprise.inject.Produces
                    <N extends Number> Box<java.util.List<N>> numbers() {
                        return new Box<>();
                    }
                    @jakarta.enterprise.inject.Produces
                    Crate<String[]> crate() {
                        return new Crate<>();
                    }
                    @jakarta.enterprise.inject.Produces
                    <T> Crate<T> anyCrate() {
                        return new Crate<>();
                    }
                }""");
        var expected = sortedConcat(List.of(
                "producer of vi.types.BoxFactory: java.lang.Object, vi.types.Box<java.lang.String>,"
                        + " vi.types.Repo<java.lang.String>",
                "producer of vi.types.Boxes: java.lang.Object, vi.types.Box<T>, vi.types.Repo<T>",
                "producer of vi.types.Boxes: java.lang.Object, vi.types.Box<int[][]>, vi.types.Repo<int[][]>",
                "producer of vi.types.Boxes: java.lang.Object, vi.types.Crate<T>, vi.types.Repo<T[]>",
                "producer of vi.types.Boxes: java.lang.Object, vi.types.Crate<java.lang.String[]>,"
                        + " vi.types.Repo<java.lang.String[][]>",
                "producer of vi.types.Boxes: java.lang.Object,"
                        + " vi.types.Box<java.util.List<N extends java.lang.Number>>,"
                        + " vi.types.Repo<java.util.List<N extends java.lang.Number>>",
                "producer of vi.types.Boxes: java.lang.Object, vi.types.Box<java.lang.String[]>,"
                        + " vi.types.Repo<java.lang.String[]>",
                "producer of vi.types.Boxes: java.lang.Object, vi.types.Box<java.util.List<java.lang.String>[]>,"
                        + " vi.types.Repo<java.util.List<java.lang.String>[]>",
                "vi.types.BoxFactory: java.lang.Object, vi.types.BoxFactory",
                "vi.types.Boxes: java.lang.Object, vi.types.Boxes"));
        var container = new ContainerTypes();

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, container);

        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * A type with a wildcard among its type arguments, at any depth, is no legal bean type (CDI 4.1, 2.2.1), and the
     * bean does not have its raw class either, at build, at start and frozen: {@code Eyrie<P> extends Perch<P>}, where
     * {@code Perch<P> implements Holder<List<? extends P>>}, is no {@code Holder}, as a bean and as a produced type.
     * That is the shape of the CDI TCK's {@code BeanTypesWithIllegalTypeTest}. A raw supertype erases the types above
     * it (JLS 4.8), wildcards and all: {@code RawEyrie<P> extends Perch}, raw, is a {@code Holder}, its own {@code P}
     * not the {@code P} in {@code Holder<List<? extends P>>}.
     */
    @Test
    @DisplayName("a type with a wildcard type argument goes with its raw class, unless a raw supertype erased it")
    void aTypeWithAWildcardTypeArgumentGoesWithItsRawClassUnlessARawSupertypeErasedIt() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, HOLDER, HOLDER_SOURCE);
        putType(sources, "Perch", "public class Perch<P> implements Holder<java.util.List<? extends P>> {\n}");
        putType(sources, "Eyrie", DEPENDENT + "public class Eyrie<P> extends Perch<P> {\n}");
        putType(sources, "RawEyrie", DEPENDENT + "@SuppressWarnings(\"rawtypes\")\n"
                + "public class RawEyrie<P> extends Perch {\n}");
        putType(sources, "EyrieFactory", DEPENDENT + """
                public class EyrieFactory {
                    @jakarta.enterprise.inject.Produces
                    <F> Eyrie<F> eyrie() {
                        return new Eyrie<>();
                    }
                }""");
        var factory = List.of("producer of vi.types.EyrieFactory: java.lang.Object, vi.types.Eyrie<F>,"
                        + " vi.types.Perch<F>", "vi.types.EyrieFactory: java.lang.Object, vi.types.EyrieFactory");
        var container = new ContainerTypes();

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, container);

        var expected = sortedConcat(factory, List.of(
                "vi.types.Eyrie: java.lang.Object, vi.types.Eyrie<P>, vi.types.Perch<P>",
                "vi.types.RawEyrie: java.lang.Object, vi.types.Holder, vi.types.Perch, vi.types.RawEyrie<P>"));
        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * A bean whose class extends a generic class raw has the raw types above it, which Java erases there (JLS 4.8):
     * {@code LegacyNames extends ArrayList} is a {@code List}, a {@code Collection} and an {@code Iterable}, and so is
     * {@code Legacy<E> extends ArrayList}, whose {@code E} is not the {@code E} of {@code List<E>}. A {@code @Typed}
     * class that implements {@code Base} raw is a {@code Holder}. The legal bean type rule (CDI 4.1, 2.2.1) dropped
     * {@code List<E>}, after the raw {@code List} had been dropped for it: no {@code List} was left, at build and at
     * start, and {@code Legacy} kept {@code List<E>}, its {@code E} taken for its own.
     */
    @Test
    @DisplayName("a bean of a class that extends a generic class raw has the raw types above it, at build as at start")
    void aBeanOfAClassThatExtendsAGenericClassRawHasTheRawTypesAboveItAtBuildAsAtStart() throws Exception {
        var sources = withRawSupertypes();
        putType(sources, "Legacy", DEPENDENT + "@SuppressWarnings(\"rawtypes\")\n"
                + "public class Legacy<E> extends java.util.ArrayList {\n}");
        putType(sources, "Base", "public interface Base<B> extends Holder<B> {\n}");
        putType(sources, "TypedRawUser", DEPENDENT + "@jakarta.enterprise.inject.Typed(Holder.class)\n"
                + "@SuppressWarnings(\"rawtypes\")\npublic class TypedRawUser implements Base {\n}");
        var others = List.of("vi.types.TypedRawUser: java.lang.Object, vi.types.Holder");
        var container = new ContainerTypes();

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, container);

        var expected = sortedConcat(others, RAW_SUPERTYPE_BEANS,
                List.of("vi.types.Legacy: " + ABOVE_ARRAY_LIST + "vi.types.Legacy<E>"));
        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * An injection point of {@code List}, {@code Collection}, {@code Iterable}, {@code List<Object>} or {@code Map}
     * resolves to a bean whose class extends {@code ArrayList} or {@code HashMap} raw, at build, at start and frozen:
     * its raw type is a legal bean type (CDI 4.1, 2.2.1), and a raw bean type is assignable to a parameterized type of
     * {@code Object} (5.2.4). Each was unsatisfied, the raw types lost with the illegal {@code List<E>} and
     * {@code Map<K, V>}.
     */
    @Test
    @DisplayName("an injection point of a raw supertype resolves to a bean that extends a generic class raw")
    void anInjectionPointOfARawSupertypeResolvesToABeanThatExtendsAGenericClassRaw() {
        assertAll(Stream.of(Map.entry("java.util.List", "LegacyNames"),
                Map.entry("java.util.Collection", "LegacyNames"), Map.entry("Iterable", "LegacyNames"),
                Map.entry("java.util.List<Object>", "LegacyNames"), Map.entry("java.util.Map", "LegacyMap"))
                .map(injected -> (Executable) () -> {
                    var sources = withRawSupertypes();
                    sources.put(READER_SOURCE, reader(injected.getKey()));

                    var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN,
                            readerGets(injected.getValue()));

                    seen.assertEachIs(sortedConcat(RAW_SUPERTYPE_BEANS, List.of(READER_TYPES)));
                }));
    }

    /**
     * A producer of {@code Outer<String>.Inner<Integer>}, where {@code Inner<I> implements Holder<O>}: the index names
     * the produced type {@code Outer$Inner<Integer>}, without the arguments of the class that encloses it, so
     * {@code O} is not known, and the producer has the raw {@code Holder}, at build as at start. The build fell back
     * to the raw {@code Holder} and the start kept {@code Holder<O>} (BUG-20261117-21).
     */
    @Test
    @DisplayName("a producer of an inner class of a generic class has the same types at build as at start")
    void aProducerOfAnInnerClassOfAGenericClassHasTheSameTypesAtBuildAsAtStart() throws Exception {
        var seen = seenThreeWays(withAnInnerClassProducer(), BeanTypeRecorder.class, BeanTypeRecorder.SEEN);

        seen.assertEachIs(List.of("producer of vi.types.InnerFactory: java.lang.Object, vi.types.Holder,"
                + " vi.types.Outer$Inner<java.lang.Integer>", INNER_FACTORY_TYPES));
    }

    /**
     * The producer of {@code Outer<String>.Inner<Integer>} is a {@code Holder<String>}, as {@code Bean.getTypes()}
     * says, which reads the producer method: the index names the produced type without {@code Outer<String>}, and the
     * descriptor has the raw {@code Holder}, at build and at start (BUG-20261117-21).
     */
    @Test
    @Disabled("BUG-20261117-21: the descriptor of a producer of an inner class of a generic class has a raw supertype")
    @DisplayName("a producer of an inner class of a generic class has the parameterized types above it")
    void aProducerOfAnInnerClassOfAGenericClassHasTheTypesAboveIt() throws Exception {
        var container = new ContainerTypes();

        var seen = seenThreeWays(withAnInnerClassProducer(), BeanTypeRecorder.class, BeanTypeRecorder.SEEN,
                container);

        var expected = List.of("producer of vi.types.InnerFactory: java.lang.Object,"
                + " vi.types.Holder<java.lang.String>, vi.types.Outer$Inner<java.lang.Integer>", INNER_FACTORY_TYPES);
        assertAll(() -> seen.assertEachIs(expected), () -> container.assertEachIs(expected));
    }

    /**
     * Two producers of {@code Box}es, {@code @Typed(Repo.class)}, of {@code Box<String>} and of {@code Box<Integer>}:
     * an injection point of {@code Repo<String>} resolves to the first, at build, at start and frozen, as
     * {@code Bean.getTypes()} says, which holds {@code Repo<String>} and {@code Repo<Integer>}. The descriptor of a
     * {@code @Typed} producer named its types raw, {@code Repo} for both: the injection point was ambiguous, and
     * unsatisfied once a raw bean type satisfied no other parameterization (BUG-20261117-19, BUG-20261117-16).
     */
    @Test
    @DisplayName("an injection point resolves to the @Typed producer of its parameterization")
    void anInjectionPointResolvesToTheTypedProducerOfItsParameterization() throws Exception {
        var sources = withABoxProducer();
        putType(sources, "BoxFactory", DEPENDENT + """
                public class BoxFactory {
                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.inject.Typed(Repo.class)
                    Box<String> strings() {
                        return new Box<>();
                    }

                    @jakarta.enterprise.inject.Produces
                    @jakarta.enterprise.inject.Typed(Repo.class)
                    Box<Integer> integers() {
                        return new Box<>();
                    }
                }""");
        sources.put(READER_SOURCE, reader(REPO_OF_STRING));

        var seen = seenThreeWays(sources, BeanTypeRecorder.class, BeanTypeRecorder.SEEN, readerGets("Box"));

        seen.assertEachIs(List.of("producer of vi.types.BoxFactory: java.lang.Object,"
                + " vi.types.Repo<java.lang.Integer>", "producer of vi.types.BoxFactory: java.lang.Object,"
                + " vi.types.Repo<java.lang.String>", "vi.types.BoxFactory: java.lang.Object, vi.types.BoxFactory",
                READER_TYPES));
    }

    /** {@link #withAVetoedPackage()}, with {@code Outer<O>.Inner<I> implements Holder<O>} and its producer. */
    private static Map<String, String> withAnInnerClassProducer() {
        var sources = withAVetoedPackage();
        putType(sources, HOLDER, HOLDER_SOURCE);
        putType(sources, "Outer", GENERIC_OUTER_SOURCE);
        putType(sources, "InnerFactory", DEPENDENT + """
                public class InnerFactory {
                    @jakarta.enterprise.inject.Produces
                    Outer<String>.Inner<Integer> inner() {
                        return new Outer<String>().new Inner<Integer>();
                    }
                }""");
        return sources;
    }

    /** {@link #withAVetoedPackage()}, with {@code vi.types.Holder} and two beans that extend a JDK class raw. */
    private static Map<String, String> withRawSupertypes() {
        var sources = withAVetoedPackage();
        putType(sources, HOLDER, HOLDER_SOURCE);
        putType(sources, "LegacyNames", DEPENDENT + "@SuppressWarnings(\"rawtypes\")\n"
                + "public class LegacyNames extends java.util.ArrayList {\n}");
        putType(sources, "LegacyMap", DEPENDENT + "@SuppressWarnings(\"rawtypes\")\n"
                + "public class LegacyMap extends java.util.HashMap {\n}");
        return sources;
    }

    /**
     * {@link #withAVetoedPackage()}, with {@code vi.types.Box<T> implements Repo<T>} and its producer, of
     * {@code Box<String>}.
     */
    private static Map<String, String> withABoxProducer() {
        var sources = withAVetoedPackage();
        putType(sources, "Repo", "public interface Repo<T> {\n}");
        putType(sources, "Box", "public class Box<T> implements Repo<T> {\n}");
        putType(sources, "BoxFactory", DEPENDENT + "public class BoxFactory {\n"
                + "    @jakarta.enterprise.inject.Produces\n"
                + "    Box<String> box() {\n        return new Box<>();\n    }\n}");
        return sources;
    }

    /**
     * Adds {@code ArrayShelf<S> implements Holder<S[]>} and the bean {@code ArrayLibrary extends ArrayShelf<String>}.
     */
    private static void withAnArrayLibrary(Map<String, String> sources) {
        putType(sources, "ArrayShelf", "public abstract class ArrayShelf<S> implements Holder<S[]> {\n}");
        putType(sources, "ArrayLibrary", DEPENDENT + "public class ArrayLibrary extends ArrayShelf<String> {\n}");
    }

    @SafeVarargs
    private static List<String> sortedConcat(List<String>... parts) {
        return java.util.Arrays.stream(parts).flatMap(List::stream).sorted().toList();
    }

    /**
     * Records the container's {@code Bean.getTypes()} of each bean of {@code vi.types}, and of each producer a class of
     * it declares, written as {@link BeanTypeRecorder} writes the types an extension sees: once for the container that
     * runs the extensions at start, once for the frozen one.
     */
    private static final class ContainerTypes implements StartedCheck {

        private final List<List<String>> seen = new ArrayList<>();

        @Override
        public void check(VaubanContainer container, ClassLoader loader) {
            seen.add(container.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE).stream()
                    .filter(bean -> bean.getBeanClass().getName().startsWith(TYPES))
                    .map(VetoedInterceptorCompileTimeTest::containerTypes).sorted().toList());
        }

        void assertEachIs(List<String> expected) {
            assertEquals(List.of(expected, expected), seen, "Bean.getTypes() at start, run and frozen");
        }
    }

    /**
     * The bean types of {@code bean}, after its class, and {@code producer of} for a producer: a class bean's
     * passivation id is the name of its class, a producer's names its method or field too.
     */
    private static String containerTypes(Bean<?> bean) {
        var classBean = bean instanceof PassivationCapable capable
                && capable.getId().equals(bean.getBeanClass().getName());
        return (classBean ? "" : "producer of ") + bean.getBeanClass().getName() + ": " + bean.getTypes().stream()
                .map(type -> reflectedName(type, new HashSet<>())).sorted().collect(Collectors.joining(", "));
    }

    /** {@code type} as {@link BeanTypeRecorder} writes the type an extension sees. */
    private static String reflectedName(java.lang.reflect.Type type, Set<java.lang.reflect.Type> visiting) {
        return switch (type) {
            case Class<?> array when array.isArray() -> reflectedName(array.getComponentType(), visiting) + "[]";
            case Class<?> plain -> plain.getName();
            case java.lang.reflect.ParameterizedType parameterized ->
                    ((Class<?>) parameterized.getRawType()).getName()
                            + java.util.Arrays.stream(parameterized.getActualTypeArguments())
                                    .map(argument -> reflectedName(argument, visiting))
                                    .collect(Collectors.joining(", ", "<", ">"));
            case java.lang.reflect.GenericArrayType array ->
                    reflectedName(array.getGenericComponentType(), visiting) + "[]";
            case java.lang.reflect.WildcardType wildcard -> wildcard.getLowerBounds().length > 0
                    ? "? super " + reflectedName(wildcard.getLowerBounds()[0], visiting)
                    : wildcard.getUpperBounds()[0] == Object.class ? "?"
                            : "? extends " + reflectedName(wildcard.getUpperBounds()[0], visiting);
            case java.lang.reflect.TypeVariable<?> variable -> variableName(variable, visiting);
            default -> type.getTypeName();
        };
    }

    /** A type variable with its bounds but {@code Object}, or its name alone when met again inside them. */
    private static String variableName(java.lang.reflect.TypeVariable<?> variable,
                                       Set<java.lang.reflect.Type> visiting) {
        if (!visiting.add(variable)) {
            return variable.getName();
        }
        var bounds = java.util.Arrays.stream(variable.getBounds()).filter(bound -> bound != Object.class)
                .map(bound -> reflectedName(bound, visiting)).collect(Collectors.joining(" & "));
        visiting.remove(variable);
        return bounds.isEmpty() ? variable.getName() : variable.getName() + " extends " + bounds;
    }

    /** {@link #withAVetoedPackage()}, with beans of {@code vi.types} whose supertypes take every shape of argument. */
    private static Map<String, String> withEveryShapeOfTypeArgument() {
        var sources = withAVetoedPackage();
        var types = Map.ofEntries(
                Map.entry(HOLDER, HOLDER_SOURCE),
                Map.entry("Pair", "public interface Pair<A, C> {\n}"),
                Map.entry("Outer", "public class Outer {\n    public interface Inner<I> {\n    }\n}"),
                Map.entry(WILD, "public interface Wild extends Pair<java.util.List<? extends Number>,"
                        + " java.util.List<? super Integer>>, Holder<java.util.Map<?, int[][]>> {\n}"),
                Map.entry("Base", "public interface Base<B> extends Holder<B> {\n}"),
                Map.entry("Shelf", "public abstract class Shelf<S> implements Holder<java.util.List<S>> {\n}"),
                Map.entry("BookShelf", "public abstract class BookShelf<K> extends Shelf<K> {\n}"),
                Map.entry("Shapes", DEPENDENT + "public class Shapes implements Wild, Outer.Inner<String[]> {\n}"),
                Map.entry("Ranked", DEPENDENT
                        + "public class Ranked<T extends Number & Comparable<T>> implements Holder<T> {\n}"),
                Map.entry("RawUser", DEPENDENT + "@SuppressWarnings(\"rawtypes\")\n"
                        + "public class RawUser implements Base {\n}"),
                Map.entry("Library", DEPENDENT + "public class Library extends BookShelf<String> {\n}"),
                Map.entry("Names", DEPENDENT + "public class Names extends java.util.ArrayList<String> {\n}"),
                Map.entry("Factory", DEPENDENT + "public class Factory {\n    @jakarta.enterprise.inject.Produces\n"
                        + "    Wild wild() {\n        return new Wild() {\n        };\n    }\n}"));
        types.forEach((name, declaration) -> putType(sources, name, declaration));
        return sources;
    }

    /** Adds to {@code sources} the class or interface {@code vi.types.name} that {@code declaration} declares. */
    private static void putType(Map<String, String> sources, String name, String declaration) {
        sources.put("vi/types/" + name + JAVA, "package vi.types;\n" + declaration + "\n");
    }

    /**
     * {@link #withAVetoedPackage()}, with {@code vi.types.Repo<T>} and two {@code @Dependent} beans,
     * {@code TextsImpl implements Texts} and {@code CountsImpl implements Counts}, where the interface {@code Texts}
     * extends {@code textsAbove} and {@code Counts} extends {@code countsAbove}. No annotation brings these interfaces
     * into the index.
     */
    private static Map<String, String> withTwoRepositories(String textsAbove, String countsAbove) {
        var sources = withAVetoedPackage();
        putType(sources, "Repo", "public interface Repo<T> {\n}");
        putType(sources, "Texts", "public interface Texts extends " + textsAbove + " {\n}");
        putType(sources, "Counts", "public interface Counts extends " + countsAbove + " {\n}");
        putType(sources, TEXTS_IMPL, DEPENDENT + "public class TextsImpl implements Texts {\n}");
        putType(sources, "CountsImpl", DEPENDENT + "public class CountsImpl implements Counts {\n}");
        return sources;
    }

    /**
     * {@code sources}, with the {@code @Dependent} beans {@code vi.types.Text implements Callable<String>} and
     * {@code Count implements Callable<Integer>}.
     */
    private static Map<String, String> withTwoCallables(Map<String, String> sources) {
        sources.put("vi/types/Text.java", callable("Text", "String", "\"text\""));
        sources.put("vi/types/Count.java", callable("Count", "Integer", "1"));
        return sources;
    }

    /** A {@code @Dependent} bean {@code vi.types.name}, a {@code Callable<type>} that returns {@code value}. */
    private static String callable(String name, String type, String value) {
        return """
                package vi.types;
                @jakarta.enterprise.context.Dependent
                public class %s implements java.util.concurrent.Callable<%s> {
                    @Override
                    public %s call() {
                        return %s;
                    }
                }
                """.formatted(name, type, type, value);
    }

    /** {@code vi.types.Reader}, a bean that injects a {@code type} and supplies the class of what it got. */
    private static String reader(String type) {
        return """
                package vi.types;
                @jakarta.enterprise.context.Dependent
                public class Reader implements java.util.function.Supplier<Object> {
                    @jakarta.inject.Inject
                    %s injected;
                    @Override
                    public Object get() {
                        return injected.getClass().getName();
                    }
                }
                """.formatted(type);
    }

    /** Checks that {@code vi.types.Reader} got the bean {@code vi.types.simpleName}. */
    private static StartedCheck readerGets(String simpleName) {
        return (container, loader) -> assertEquals(TYPES + simpleName,
                hello(container, loader.loadClass(TYPES + "Reader")), "the bean the injection point resolves to");
    }

    /**
     * Records each bean of {@code vi.types}, and each producer a class of it declares, with its bean types as
     * {@code @Registration} gives them, sorted: what the processor computes at build, and the container at start.
     */
    public static class BeanTypeRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void types(BeanInfo bean) {
            var declaring = bean.declaringClass().name();
            if (declaring.startsWith(TYPES)) {
                SEEN.add((bean.isClassBean() ? declaring : "producer of " + declaring) + ": "
                        + bean.types().stream().map(BeanTypeRecorder::nameOf).sorted()
                                .collect(Collectors.joining(", ")));
            }
        }

        private static String nameOf(Type type) {
            return nameOf(type, Set.of());
        }

        /**
         * The name of {@code type}; a type variable met again inside its own bounds, which has them as reflection
         * gives them (BUG-20261117-33), by its name alone, as the processor writes it.
         */
        private static String nameOf(Type type, Set<String> reading) {
            if (type.isParameterizedType()) {
                var parameterized = type.asParameterizedType();
                return parameterized.genericClass().declaration().name() + parameterized.typeArguments().stream()
                        .map(argument -> nameOf(argument, reading)).collect(Collectors.joining(", ", "<", ">"));
            }
            if (type.isWildcardType()) {
                // ? extends Object is ?, as reflection names it (reflectedName) and as the language model gives ?
                var wildcard = type.asWildcardType();
                var upperBound = wildcard.upperBound() == null ? "java.lang.Object"
                        : nameOf(wildcard.upperBound(), reading);
                if (!upperBound.equals("java.lang.Object")) {
                    return "? extends " + upperBound;
                }
                return wildcard.lowerBound() != null ? "? super " + nameOf(wildcard.lowerBound(), reading) : "?";
            }
            if (type.isTypeVariable()) {
                var variable = type.asTypeVariable();
                if (reading.contains(variable.name())) {
                    return variable.name();
                }
                var inner = new java.util.HashSet<>(reading);
                inner.add(variable.name());
                var bounds = variable.bounds().stream().map(bound -> nameOf(bound, Set.copyOf(inner)))
                        .filter(bound -> !bound.equals("java.lang.Object")).collect(Collectors.joining(" & "));
                return bounds.isEmpty() ? variable.name() : variable.name() + " extends " + bounds;
            }
            return ObserverRecorder.nameOf(type);
        }
    }

    /**
     * Records the beans {@code @Registration} gives it as an {@code Integer} or a {@code Serializable}: a producer by
     * its bean types, any other bean by its class.
     */
    public static class ProducedTypeRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Integer.class)
        public void integers(BeanInfo bean) {
            SEEN.add("integer " + described(bean));
        }

        @Registration(types = Serializable.class)
        public void serializables(BeanInfo bean) {
            SEEN.add(SERIALIZABLE + described(bean));
        }

        @Registration(types = int.class)
        public void ints(BeanInfo bean) {
            SEEN.add("int " + described(bean));
        }

        @Registration(types = String[].class)
        public void stringArrays(BeanInfo bean) {
            SEEN.add("strings " + described(bean));
        }

        private static String described(BeanInfo bean) {
            if (bean.isClassBean() || bean.isInterceptor()) {
                return bean.declaringClass().name();
            }
            return "producer of " + bean.types().stream().map(ObserverRecorder::nameOf)
                    .filter(name -> !name.equals(Object.class.getName())).sorted().collect(Collectors.joining(", "));
        }
    }

    /**
     * CDI 4.1 calls a {@code @Registration} method for each bean whose bean types contain one of its types, and the
     * bean types of a producer of a primitive or an array type are that type and {@code Object} (§2.2.1), a primitive
     * read as its wrapper too: a producer of {@code int} is an {@code int} and an {@code Integer}, not a
     * {@code Serializable}; one of {@code String[]} a {@code String[]}. The processor, the container at start and the
     * build that freezes the extensions matched a bean type by loading the class it names, and a primitive or an array
     * type names none: they called no method for either producer (BUG-20261117-11).
     */
    @Test
    @DisplayName("@Registration gets the producers of primitive and array types from the processor, at start and frozen")
    void registrationGetsTheProducersOfPrimitiveAndArrayTypesFromTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Numbers.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Numbers {
                    @jakarta.enterprise.inject.Produces
                    int count() {
                        return 1;
                    }
                    @jakarta.enterprise.inject.Produces
                    String[] names() {
                        return new String[0];
                    }
                }
                """);

        var seen = seenThreeWays(sources, ProducedTypeRecorder.class, ProducedTypeRecorder.SEEN);

        seen.assertEachIs(List.of("int producer of int", "integer producer of int",
                SERIALIZABLE + LOGGER, "strings producer of java.lang.String[]"));
    }

    /** Records the beans {@code @Registration} gives it as a {@code java.util.EventListener}. */
    public static class ListenerRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = java.util.EventListener.class)
        public void listeners(BeanInfo bean) {
            SEEN.add(bean.declaringClass().name());
        }
    }

    /**
     * CDI 4.1 calls a {@code @Registration} method for each bean whose bean types contain one of its types. The bean
     * types of a {@code @Typed} bean are those it names and {@code Object}: {@code vi.Narrow} is a {@code vi.Talker}
     * only, not the {@code EventListener} {@code vi.Talker} extends. The container matched a bean type by loading its
     * class and asking whether it is a subtype, and gave it (BUG-20261118-43).
     */
    @Test
    @DisplayName("@Registration does not give a @Typed bean as a supertype of its types, everywhere")
    void registrationGivesATypedBeanForItsTypesOnlyFromTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Talker.java", """
                package vi;
                public interface Talker extends java.util.EventListener {
                }
                """);
        sources.put("vi/Narrow.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                @jakarta.enterprise.inject.Typed(Talker.class)
                public class Narrow implements Talker {
                }
                """);
        sources.put("vi/Wide.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Wide implements Talker {
                }
                """);

        var seen = seenThreeWays(sources, ListenerRecorder.class, ListenerRecorder.SEEN);

        seen.assertEachIs(List.of("vi.Wide"));
    }

    /** Records the method, the event parameter, the bean and the qualifiers of each observer of {@code vi.Hearer}. */
    public static class ObserverDescriber implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void observers(ObserverInfo observer) {
            if (!observer.declaringClass().name().equals("vi.Hearer")) {
                return;
            }
            SEEN.add(observer.observerMethod().name() + " "
                    + ObserverRecorder.nameOf(observer.eventParameter().type()) + " position "
                    + observer.eventParameter().declaringMethod().parameters().indexOf(observer.eventParameter())
                    + " bean " + observer.bean().declaringClass().name() + " " + observer.qualifiers().stream()
                    .map(AnnotationInfo::name).toList());
        }
    }

    /**
     * CDI 4.1 gives an {@code ObserverInfo} its method, its event parameter, the bean that declares it and the
     * qualifiers of the observed event. The container gave none, in the processor, at start and frozen alike: an
     * extension that read them threw {@code NullPointerException} (BUG-20261117-09). An overload of the same name
     * that observes another event type is told apart by its event type.
     */
    @Test
    @DisplayName("an ObserverInfo gives its method, event parameter, bean and qualifiers, everywhere")
    void anObserverInfoGivesItsMethodParameterBeanAndQualifiersFromTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Loud.java", """
                package vi;
                @jakarta.inject.Qualifier
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Loud {
                }
                """);
        sources.put("vi/Hearer.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Hearer {
                    void hear(@jakarta.enterprise.event.Observes @Loud String text) {
                        // a qualified observer
                    }
                    void hear(jakarta.enterprise.inject.spi.BeanManager manager,
                            @jakarta.enterprise.event.Observes Integer number) {
                        // an overload of another event type, its event parameter second
                    }
                }
                """);

        var seen = seenThreeWays(sources, ObserverDescriber.class, ObserverDescriber.SEEN);

        seen.assertEachIs(List.of("hear java.lang.Integer position 1 bean vi.Hearer []",
                "hear java.lang.String position 0 bean vi.Hearer [vi.Loud]"));
    }

    /** Records the bean types, the qualifiers and the injection points of the interceptor {@code vi.Logger}. */
    public static class InterceptorDescriber implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void interceptors(InterceptorInfo interceptor) {
            if (!interceptor.declaringClass().name().equals(LOGGER)) {
                return;
            }
            interceptor.types().forEach(type -> SEEN.add("type " + ObserverRecorder.nameOf(type)));
            interceptor.qualifiers().forEach(qualifier -> SEEN.add("qualifier " + qualifier.name()));
            interceptor.injectionPoints().forEach(point -> SEEN.add("injection point "
                    + ObserverRecorder.nameOf(point.type())));
        }
    }

    /**
     * CDI 4.1 gives an interceptor's {@code BeanInfo} the bean types, the qualifiers and the injection points of its
     * class, as a bean's. The container gave none, in the processor, at start and frozen alike (BUG-20261117-04).
     */
    @Test
    @DisplayName("an InterceptorInfo gives the bean types, qualifiers and injection points of its class, everywhere")
    void anInterceptorInfoGivesItsTypesQualifiersAndInjectionPointsFromTheProcessorAtStartAndFrozen()
            throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Clock.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Clock {
                }
                """);
        sources.compute("vi/Logger.java", (_, source) -> withInjectedClock(source));

        var seen = seenThreeWays(sources, InterceptorDescriber.class, InterceptorDescriber.SEEN);

        seen.assertEachIs(List.of("injection point vi.Clock", "qualifier jakarta.enterprise.inject.Any",
                "qualifier jakarta.enterprise.inject.Default", "type java.io.Serializable", "type java.lang.Object",
                "type vi.Logger", "type vi.Wrapping"));
    }

    /** {@code source}, the class of which gets an {@code @Inject vi.Clock clock} field. */
    private static String withInjectedClock(String source) {
        assertNotNull(source, "no source of vi.Logger");
        var injected = source.replace("    @jakarta.interceptor.AroundInvoke",
                "    @jakarta.inject.Inject\n    vi.Clock clock;\n    @jakarta.interceptor.AroundInvoke");
        assertNotEquals(source, injected, () -> "no @AroundInvoke in:\n" + source);
        return injected;
    }

    /** Records the member, the disposer and the stereotypes of each bean of {@code vi.Stock} and {@code vi.Shown}. */
    public static class MemberRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void members(BeanInfo bean) {
            var declaring = bean.declaringClass().name();
            if (!declaring.equals("vi.Stock") && !declaring.equals("vi.Shown")) {
                return;
            }
            var disposer = bean.disposer() == null ? "no disposer" : "disposer " + bean.disposer().disposerMethod()
                    .name() + "(" + bean.disposer().disposedParameter().type().asClass().declaration().simpleName()
                    + ")";
            var stereotypes = bean.stereotypes().stream().map(stereotype -> (stereotype.defaultScope() == null
                    ? "no scope" : stereotype.defaultScope().name()) + " priority " + stereotype.priority()
                    + (stereotype.isAlternative() ? " alternative" : "") + (stereotype.isNamed() ? " named" : ""))
                    .collect(Collectors.joining(", ", "[", "]"));
            String member;
            if (bean.isProducerMethod()) {
                member = "method " + bean.producerMethod().name();
            } else if (bean.isProducerField()) {
                member = "field " + bean.producerField().name();
            } else {
                member = "class " + declaring;
            }
            SEEN.add(member + ", " + disposer + ", " + stereotypes);
        }
    }

    /**
     * CDI 4.1 gives the {@code BeanInfo} of a producer its method or field and its disposer, and that of every bean its
     * stereotypes. The container gave {@code null} for the three members and no stereotype, in the processor, at start
     * and frozen alike (BUG-20261117-13).
     */
    @Test
    @DisplayName("a BeanInfo gives its producer member, its disposer and its stereotypes, everywhere")
    void aBeanInfoGivesItsProducerMemberDisposerAndStereotypesFromTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Show.java", """
                package vi;
                @jakarta.enterprise.inject.Stereotype
                @jakarta.enterprise.context.ApplicationScoped
                @jakarta.enterprise.inject.Alternative
                @jakarta.annotation.Priority(7)
                @jakarta.inject.Named
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Show {
                }
                """);
        sources.put("vi/Plain.java", """
                package vi;
                @jakarta.enterprise.inject.Stereotype
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Plain {
                }
                """);
        sources.put("vi/Shown.java", """
                package vi;
                @Show
                public class Shown {
                }
                """);
        sources.put("vi/Stock.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Stock {
                    @jakarta.enterprise.inject.Produces
                    Long level = 1L;
                    @jakarta.enterprise.inject.Produces
                    @Plain
                    String label() {
                        return "label";
                    }
                    void drop(@jakarta.enterprise.inject.Disposes String label) {
                        // Nothing to release.
                    }
                }
                """);

        var seen = seenThreeWays(sources, MemberRecorder.class, MemberRecorder.SEEN);

        seen.assertEachIs(List.of(
                "class vi.Shown, no disposer, [jakarta.enterprise.context.ApplicationScoped priority 7 alternative"
                        + " named]",
                "class vi.Stock, no disposer, []",
                "field level, no disposer, []",
                "method label, disposer drop(String), [no scope priority null]"));
    }

    /** Records the disposer of each producer of {@code vi.Lists}. */
    public static class DisposerRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = java.util.List.class)
        public void lists(BeanInfo bean) {
            if (bean.isProducerMethod() && bean.declaringClass().name().equals("vi.Lists")) {
                SEEN.add(bean.producerMethod().name() + " disposed by " + bean.disposer().disposerMethod().name());
            }
        }
    }

    /**
     * A disposer method disposes of the producer whose bean type is its disposed parameter's type (CDI 4.1, 3.4.2), as
     * the container pairs them when it destroys a produced instance: {@code List<String>} and {@code List<Integer>}
     * are two types. The {@code BeanInfo} matched the erasure, and gave both producers the first disposer
     * (BUG-20261117-13).
     */
    @Test
    @DisplayName("a BeanInfo gives a producer of a parameterized type its own disposer, everywhere")
    void aBeanInfoGivesAProducerOfAParameterizedTypeItsOwnDisposerFromTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/Lists.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Lists {
                    void dropNames(@jakarta.enterprise.inject.Disposes java.util.List<String> names) {
                        // Nothing to release.
                    }
                    void dropCounts(@jakarta.enterprise.inject.Disposes java.util.List<Integer> counts) {
                        // Nothing to release.
                    }
                    @jakarta.enterprise.inject.Produces
                    java.util.List<String> names() {
                        return java.util.List.of();
                    }
                    @jakarta.enterprise.inject.Produces
                    java.util.List<Integer> counts() {
                        return java.util.List.of();
                    }
                }
                """);

        var seen = seenThreeWays(sources, DisposerRecorder.class, DisposerRecorder.SEEN);

        seen.assertEachIs(List.of("counts disposed by dropCounts", "names disposed by dropNames"));
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

    /**
     * Records, in {@code @Synthesis}, the supertypes of each class {@link #SUPERTYPE_PROBES} names, as the language
     * model gives them through {@code Types.ofClass(name).declaration()}, and those of {@code vi.types.Supplying}
     * through the generic class of a {@code Supplying<String>}. It adds the interfaces and the enum, which no
     * annotation brings into the index: the container indexes a class an extension adds, and the processor describes
     * one the compilation compiles.
     */
    public static class SupertypeRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            List.of("Probe", "Archive", "Level").forEach(name -> classes.add(TYPES + name));
        }

        @Synthesis
        public void supertypes(Types types) {
            for (var probe : SUPERTYPE_PROBES) {
                SEEN.add(supertypesOf(types.ofClass(TYPES + probe).declaration()));
            }
            var supplying = types.parameterized(types.ofClass(TYPES + SUPPLYING),
                    types.ofClass(String.class.getName()));
            SEEN.add(THROUGH_GENERIC_CLASS + supertypesOf(supplying.genericClass().declaration()));
        }

        private static String supertypesOf(ClassInfo type) {
            var superClass = type.superClass();
            return type.name() + ": superClass=" + (superClass == null ? "null" : described(superClass))
                    + "; superInterfaces=" + type.superInterfaces().stream().map(SupertypeRecorder::described)
                            .collect(Collectors.joining(", ", "[", "]"));
        }

        /** A type with its kind, and for a parameterized type the kinds of its arguments. */
        private static String described(Type type) {
            var described = type.kind() + " " + BeanTypeRecorder.nameOf(type);
            return type.isParameterizedType() ? described + " of " + type.asParameterizedType().typeArguments()
                    .stream().map(argument -> argument.kind().name()).collect(Collectors.joining(", ")) : described;
        }
    }

    /**
     * An extension sees the supertypes a class declares, with their type arguments, in the processor, at start and
     * frozen: a superclass, two superinterfaces in declaration order (Mansart's repository shape), a type variable,
     * wildcards inside an argument, an array argument, an enum's {@code Enum<E>}. A supertype the class names raw stays
     * a class, and so
     * does {@code Object}. The language model gave every supertype raw (BUG-20261117-22). An interface has no
     * superclass: the processor gave none, the container {@code Object} (BUG-20261117-32). The interfaces and the enum
     * come in through {@code ScannedClasses}, as Mansart's repositories do.
     */
    @Test
    @DisplayName("an extension sees the generic supertypes of a class in the processor, at start and frozen")
    void anExtensionSeesTheGenericSupertypesOfAClassInTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Probe", "public interface Probe extends java.util.function.Supplier<String> {\n}");
        putType(sources, "Base", "public class Base<B> {\n}");
        putType(sources, "Extending", DEPENDENT + "public class Extending extends Base<java.util.List<String>> {\n}");
        putType(sources, "Crud", "public interface Crud<E, K> {\n}");
        putType(sources, "Labelled", "public interface Labelled<L> {\n}");
        putType(sources, "Note", "public class Note {\n}");
        putType(sources, "Archive", "public interface Archive extends Crud<Note, Long>,"
                + " Labelled<java.util.List<String>> {\n}");
        putType(sources, SUPPLYING, DEPENDENT + """
                public class Supplying<T> implements java.util.function.Supplier<T> {
                    @Override
                    public T get() {
                        return null;
                    }
                }""");
        putType(sources, HOLDER, HOLDER_SOURCE);
        putType(sources, "Shapes", DEPENDENT + "public class Shapes implements"
                + " Holder<java.util.Map<? extends Number, ? super Integer>>, Labelled<int[][]> {\n}");
        putType(sources, "Raw", DEPENDENT + "@SuppressWarnings(\"rawtypes\")\n"
                + "public class Raw extends java.util.ArrayList {\n}");
        putType(sources, "Plain", DEPENDENT + "public class Plain {\n}");
        putType(sources, "Level", "public enum Level {\n    LOW\n}");
        var supplying = TYPES + "Supplying: " + OBJECT_SUPERCLASS
                + "; superInterfaces=[PARAMETERIZED_TYPE java.util.function.Supplier<T> of TYPE_VARIABLE]";
        var expected = sorted(List.of(
                TYPES + "Probe: " + NO_SUPERCLASS + "; superInterfaces=[PARAMETERIZED_TYPE"
                        + " java.util.function.Supplier<java.lang.String> of CLASS]",
                TYPES + "Extending: superClass=PARAMETERIZED_TYPE vi.types.Base<java.util.List<java.lang.String>>"
                        + " of PARAMETERIZED_TYPE; " + NO_SUPERINTERFACE,
                TYPES + "Archive: " + NO_SUPERCLASS + "; superInterfaces=[PARAMETERIZED_TYPE"
                        + " vi.types.Crud<vi.types.Note, java.lang.Long> of CLASS, CLASS, PARAMETERIZED_TYPE"
                        + " vi.types.Labelled<java.util.List<java.lang.String>> of PARAMETERIZED_TYPE]",
                supplying,
                TYPES + "Shapes: " + OBJECT_SUPERCLASS + "; superInterfaces=[PARAMETERIZED_TYPE vi.types.Holder"
                        + "<java.util.Map<? extends java.lang.Number, ? super java.lang.Integer>>"
                        + " of PARAMETERIZED_TYPE, PARAMETERIZED_TYPE vi.types.Labelled<int[][]> of ARRAY]",
                TYPES + "Raw: superClass=CLASS java.util.ArrayList; " + NO_SUPERINTERFACE,
                TYPES + "Plain: " + OBJECT_SUPERCLASS + "; " + NO_SUPERINTERFACE,
                TYPES + "Level: superClass=PARAMETERIZED_TYPE java.lang.Enum<vi.types.Level> of CLASS; "
                        + NO_SUPERINTERFACE,
                THROUGH_GENERIC_CLASS + supplying));

        var seen = seenThreeWays(sources, SupertypeRecorder.class, SupertypeRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * The constructor of a local class has the outer instance, which javac marks mandated, and the parameters its
     * source declares, with their annotations, at start and frozen; not the values the class captures, which javac
     * adds last (BUG-20261118-32). The processor does not reach a local class, which has no canonical name.
     */
    @Test
    @DisplayName("a local class's constructor has its outer instance and its declared parameters, at start and frozen")
    void aLocalClassConstructorHasItsDeclaredParameters() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Maker", DEPENDENT + """
                public class Maker {
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    public @interface Marked {
                    }
                    public Object make(int count) {
                        class Local {
                            final String label;
                            Local(@Marked String label) {
                                this.label = label + count;
                            }
                        }
                        return new Local("x");
                    }
                }""");

        var seen = seenThreeWays(sources, LocalConstructorRecorder.class, LocalConstructorRecorder.SEEN);

        var expected = List.of("[vi.types.Maker arg0 [], java.lang.String arg1 [vi.types.Maker$Marked]]");
        assertAll(() -> assertEquals(List.of(), seen.processor(), "in the processor, which does not reach it"),
                () -> assertEquals(expected, seen.start(), "at start"),
                () -> assertEquals(expected, seen.build(), "frozen"));
    }

    /**
     * A record's canonical constructor, compact or explicit, has its parameters with their generic types in the
     * processor, at start and frozen, and an explicit one its type annotations: javac marks every parameter of a
     * compact one mandated, which is no outer instance (BUG-20261118-32, review round 2). A compact one's type
     * annotations are BUG-20261118-34's.
     */
    @Test
    @DisplayName("a record's canonical constructor keeps its generic parameters and type annotations, three ways")
    void aRecordConstructorKeepsItsGenericParameters() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Marked", """
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                public @interface Marked {
                }""");
        putType(sources, "Compact", """
                public record Compact(java.util.List<String> names, int size) {
                    public Compact {
                        names = java.util.List.copyOf(names);
                    }
                }""");
        putType(sources, "Explicit", """
                public record Explicit(java.util.List<String> names) {
                    public Explicit(java.util.@Marked List<String> names) {
                        this.names = java.util.List.copyOf(names);
                    }
                }""");

        var seen = seenThreeWays(sources, RecordConstructorRecorder.class, RecordConstructorRecorder.SEEN);

        seen.assertEachIs(List.of("Compact [java.util.List<java.lang.String> [] [], int [] []]",
                "Explicit [java.util.List<java.lang.String> [vi.types.Marked] []]"));
    }

    /**
     * The type annotations of a compact constructor's parameters, which javac 25 writes into the constructor with a
     * {@code FIELD} target where they have a path, are on a qualified type or on an array: they are its record
     * components', each on its parameter, in the processor, at start and frozen; a top-level one on a plain type once
     * (BUG-20261118-34).
     */
    @Test
    @DisplayName("a compact constructor's type annotations, with a path, qualified, on an array, three ways")
    void aCompactConstructorTypeAnnotations() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Marked", """
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                public @interface Marked {
                }""");
        putType(sources, "Compact", """
                public record Compact(java.util.@Marked List<@Marked String> names, @Marked String plain,
                        String @Marked [] tags, int size) {
                    public Compact {
                        names = java.util.List.copyOf(names);
                    }
                }""");
        putType(sources, "Explicit", """
                public record Explicit(java.util.List<String> names) {
                }""");

        var seen = seenThreeWays(sources, RecordConstructorRecorder.class, RecordConstructorRecorder.SEEN);

        seen.assertEachIs(List.of("Compact [java.util.List<java.lang.String> [vi.types.Marked] [vi.types.Marked],"
                        + " java.lang.String [vi.types.Marked] [], java.lang.String[] [vi.types.Marked] [], int [] []]",
                "Explicit [java.util.List<java.lang.String> [] []]"));
    }

    /** Records the parameter types of the records' constructors, with their annotations and their arguments'. */
    public static class RecordConstructorRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(TYPES + "Compact");
            classes.add(TYPES + "Explicit");
        }

        @Synthesis
        public void constructors(Types types) {
            for (var name : List.of("Compact", "Explicit")) {
                types.ofClass(TYPES + name).declaration().constructors().forEach(constructor -> SEEN.add(name + " "
                        + constructor.parameters().stream().map(parameter -> parameter.type() + " "
                                + parameter.type().annotations().stream().map(AnnotationInfo::name).toList() + " "
                                + argumentAnnotations(parameter.type())).toList()));
            }
        }

        private static List<String> argumentAnnotations(Type type) {
            return type.isParameterizedType() ? type.asParameterizedType().typeArguments().stream()
                    .flatMap(argument -> argument.annotations().stream()).map(AnnotationInfo::name).toList()
                    : List.of();
        }
    }

    /** Records the parameters of the constructor of {@code vi.types.Maker}'s local class. */
    public static class LocalConstructorRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void parameters(Types types) {
            types.ofClass(TYPES + "Maker$1Local").declaration().constructors().forEach(constructor -> SEEN.add(
                    constructor.parameters().stream().map(parameter -> parameter.type() + " " + parameter.name() + " "
                            + parameter.annotations().stream().map(AnnotationInfo::name).toList())
                            .toList().toString()));
        }
    }

    /**
     * A type variable the language model reaches through a type has the bounds its type parameter declares,
     * {@code java.lang.Object} for none, in the processor, at start and frozen alike: the bound of another type
     * variable, the type of a field, a method's own type variable as its parameter and return types, and a variable
     * met again inside its own bound, which has the bound too (BUG-20261117-33).
     */
    @Test
    @DisplayName("a type variable reached through a type has its bounds in the processor, at start and frozen")
    void aTypeVariableReachedThroughATypeHasItsBoundsThreeWays() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Bounded", DEPENDENT + """
                public class Bounded<A, B extends A, S extends Comparable<S>> {
                    B chained;
                    S selfBounded;
                    <T> T method(T value) {
                        return value;
                    }
                }""");

        var seen = seenThreeWays(sources, BoundsRecorder.class, BoundsRecorder.SEEN);

        seen.assertEachIs(List.of("chained B extends A extends java.lang.Object",
                "method T extends java.lang.Object (T extends java.lang.Object)",
                "selfBounded S extends java.lang.Comparable<S extends java.lang.Comparable<S>>"));
    }

    /** Records the bounds of the type variables {@code vi.types.Bounded}'s members are typed by. */
    public static class BoundsRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void bounds(Types types) {
            var bounded = types.ofClass(TYPES + "Bounded").declaration();
            bounded.fields().forEach(field -> SEEN.add(field.name() + " " + of(field.type(), 2)));
            bounded.methods().stream().filter(method -> method.name().equals("method"))
                    .forEach(method -> SEEN.add(method.name() + " " + of(method.returnType(), 2) + " ("
                            + of(method.parameters().getFirst().type(), 2) + ")"));
        }

        /** A type, with the bounds of its type variables down to {@code depth} of them, then their names. */
        private static String of(Type type, int depth) {
            if (type.isParameterizedType()) {
                return type.asParameterizedType().genericClass().declaration().name() + type.asParameterizedType()
                        .typeArguments().stream().map(argument -> of(argument, depth))
                        .collect(Collectors.joining(", ", "<", ">"));
            }
            if (type.isTypeVariable()) {
                var variable = type.asTypeVariable();
                return depth == 0 ? variable.name() : variable.name() + " extends " + variable.bounds().stream()
                        .map(bound -> of(bound, depth - 1)).collect(Collectors.joining(" & "));
            }
            return type.asClass().declaration().name();
        }
    }

    /**
     * Records, in {@code @Synthesis}, each wildcard of {@code vi.types.Wild} as the language model gives it, with both
     * its bounds: in the arguments of its superinterfaces, of the types of its fields, and of its method's return and
     * parameter types, and whether it equals {@code Types.wildcardUnbounded()}.
     */
    public static class WildcardRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void wildcards(Types types) {
            var wild = types.ofClass(TYPES + WILD).declaration();
            var unbounded = types.wildcardUnbounded();
            for (var superInterface : wild.superInterfaces()) {
                recordWildcard("superinterface " + superInterface.asParameterizedType().genericClass().declaration()
                        .simpleName(), superInterface, unbounded);
            }
            wild.fields().forEach(field -> recordWildcard("field " + field.name(), field.type(), unbounded));
            for (var method : wild.methods()) {
                if (method.name().equals(ECHO)) {
                    recordWildcard("return of " + ECHO, method.returnType(), unbounded);
                    recordWildcard("parameter of " + ECHO, method.parameters().getFirst().type(), unbounded);
                }
            }
        }

        private static void recordWildcard(String where, Type type, Type unbounded) {
            var wildcard = wildcardIn(type);
            SEEN.add(where + ": upperBound=" + bound(wildcard.upperBound()) + ", lowerBound="
                    + bound(wildcard.lowerBound()) + (wildcard.equals(unbounded) ? EQUALS_UNBOUNDED : ""));
        }

        /** The first wildcard of {@code type}, a parameterized type, searched through its first type arguments. */
        private static WildcardType wildcardIn(Type type) {
            var argument = type.asParameterizedType().typeArguments().getFirst();
            return argument.isWildcardType() ? argument.asWildcardType() : wildcardIn(argument);
        }

        private static String bound(Type bound) {
            return bound == null ? "null" : bound.kind() + " " + BeanTypeRecorder.nameOf(bound);
        }
    }

    /**
     * Records, in {@code @Synthesis}, the declarations Mansart walks from a repository interface: from
     * {@code vi.types.Archive}, its superinterface {@code Crud} and Crud's {@code Basic}, which no annotation brings
     * into the index; a class of the JDK; and the declarations {@code superInterfacesDeclarations()} and
     * {@code superClassDeclaration()} reach (BUG-20261117-31).
     */
    public static class OutsideRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(TYPES + ARCHIVE);
        }

        @Synthesis
        public void outside(Types types) {
            var archive = types.ofClass(TYPES + ARCHIVE).declaration();
            SEEN.add("Archive superInterfacesDeclarations " + names(archive.superInterfacesDeclarations()));
            for (var crudType : archive.superInterfaces()) {
                var crud = crudType.asParameterizedType().genericClass().declaration();
                SEEN.add(declared(crud));
                SEEN.add("Crud superInterfacesDeclarations " + names(crud.superInterfacesDeclarations()));
                for (var basicType : crud.superInterfaces()) {
                    SEEN.add(declared(basicType.asParameterizedType().genericClass().declaration()));
                }
            }
            SEEN.add(declared(types.ofClass(Supplier.class.getName()).declaration()));
            var object = types.ofClass(TYPES + PLAIN).declaration().superClassDeclaration();
            SEEN.add("Plain superClassDeclaration " + (object == null ? null : object.name()));
        }

        private static String declared(ClassInfo type) {
            return SupertypeRecorder.supertypesOf(type) + "; typeParameters=" + type.typeParameters()
                    + (type.isInterface() ? "; interface" : "; class");
        }

        private static List<String> names(List<ClassInfo> declarations) {
            return declarations.stream().map(ClassInfo::name).toList();
        }
    }

    /**
     * Records, in {@code @Synthesis}, the modifiers of {@code vi.types.Plain} and of its nested class {@code Inner} as
     * {@code java.lang.reflect.Modifier} reads them (BUG-20261117-37).
     */
    public static class ModifierRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void modifiers(Types types) {
            for (var name : List.of(TYPES + PLAIN, TYPES + PLAIN + "$Inner")) {
                var modifiers = types.ofClass(name).declaration().modifiers();
                SEEN.add(name + ": " + java.lang.reflect.Modifier.toString(modifiers));
            }
        }
    }

    /**
     * An extension sees {@code ?} as {@code ? extends Object}, as the language model asks: "{@code ?} is equivalent to
     * {@code ? extends Object} and is represented as such. Therefore, either {@code upperBound()} or
     * {@code lowerBound()} always returns non-{@code null}." The same in the processor, at start and frozen, whether
     * the source writes {@code ?} or {@code ? extends Object}, in a supertype's argument as in the type of a field, a
     * parameter or a return. Both had no bound at start and frozen, and the processor gave {@code Object} to the
     * second only (BUG-20261117-34).
     */
    @Test
    @DisplayName("an extension sees ? and ? extends Object as ? extends Object in the processor, at start and frozen")
    void anExtensionSeesAnUnboundedWildcardAsExtendingObjectInTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, HOLDER, HOLDER_SOURCE);
        putType(sources, "Labelled", "public interface Labelled<L> {\n}");
        putType(sources, WILD, DEPENDENT + """
                public class Wild implements Holder<java.util.List<?>>, Labelled<java.util.List<? extends Object>> {
                    public java.util.List<?> unbounded;
                    public java.util.List<? extends Object> extendsObject;
                    public java.util.List<? super Integer> superInteger;

                    public java.util.List<? extends Object> echo(java.util.List<?> list) {
                        return list;
                    }
                }""");
        var extendsObject = ": upperBound=CLASS java.lang.Object, lowerBound=null" + EQUALS_UNBOUNDED;
        var expected = sorted(List.of("superinterface Holder" + extendsObject,
                "superinterface Labelled" + extendsObject, "field unbounded" + extendsObject,
                "field extendsObject" + extendsObject,
                "field superInteger: upperBound=null, lowerBound=CLASS java.lang.Integer",
                "return of echo" + extendsObject, "parameter of echo" + extendsObject));

        var seen = seenThreeWays(sources, WildcardRecorder.class, WildcardRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * An interceptor may inject the bean it intercepts as {@code @Intercepted Bean<?>}, which the source may write
     * {@code Bean<? extends Object>}, the same type: the processor reports nothing of either, and the container deploys
     * both, its extensions run at start as frozen. All three refused both (BUG-20261117-41): the check of built-in
     * metadata read the wildcard without bounds the index gives both forms (BUG-20261117-34) as a bounded one, and the
     * processor, which cannot load a class it compiles, did not see the interceptor. The container starts without a
     * call, which would create the interceptor (BUG-20261117-47).
     */
    @ParameterizedTest(name = "@Intercepted Bean<{0}>")
    @ValueSource(strings = {"?", "? extends Object"})
    @DisplayName("an interceptor that injects @Intercepted Bean<?> deploys in the processor, at start and frozen")
    void anInterceptorThatInjectsTheBeanItInterceptsDeploysInTheProcessorAtStartAndFrozen(String argument)
            throws Exception {
        assertEachPlaceServes(withAWatcher(argument), null, DEPLOYED);
    }

    /**
     * An interceptor may inject its own metadata, {@code Interceptor<Itself>}: the processor, which cannot load a class
     * it compiles, did not see the interceptor and refused it (BUG-20261117-41). The container starts without a call.
     */
    @Test
    @DisplayName("an interceptor that injects its Interceptor metadata deploys in the processor, at start and frozen")
    void anInterceptorThatInjectsItsOwnMetadataDeploysInTheProcessorAtStartAndFrozen() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        putMetadataInterceptor(sources, "Introspector", "Introspected",
                "jakarta.enterprise.inject.spi.Interceptor<Introspector>");

        assertEachPlaceServes(sources, null, DEPLOYED);
    }

    /**
     * A bean may inject its own metadata, {@code Bean<Itself>} (CDI 4.1, 5.5.8 "Bean metadata", in CDI Lite): the
     * processor reports nothing of it, and the container gives it, its extensions run at start as frozen
     * (BUG-20261117-47).
     */
    @Test
    @DisplayName("a bean gets its own Bean metadata in the processor, at start and frozen")
    void aBeanGetsItsOwnBeanMetadata() throws Exception {
        var sources = Map.of("vi/SelfAware.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class SelfAware implements java.util.function.Supplier<String> {
                    @jakarta.inject.Inject
                    jakarta.enterprise.inject.spi.Bean<SelfAware> metadata;

                    @Override
                    public String get() {
                        return metadata.getBeanClass().getName();
                    }
                }
                """);

        assertEachPlaceServes(sources, "vi.SelfAware", "vi.SelfAware");
    }

    /**
     * An interceptor may inject its own metadata, {@code Interceptor<Itself>} (CDI 4.1, 5.5.8): the processor reports
     * nothing of it, and the container gives it, its extensions run at start as frozen (BUG-20261117-47; in the
     * processor, BUG-20261117-41 too).
     */
    @Test
    @DisplayName("an interceptor gets its own Interceptor metadata in the processor, at start and frozen")
    void anInterceptorGetsItsOwnInterceptorMetadata() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        putMetadataInterceptor(sources, "Introspector", "Introspected",
                "jakarta.enterprise.inject.spi.Interceptor<Introspector>");

        assertEachPlaceServes(sources, "vi.IntrospectedService", "Introspector(vi.Introspector: hello)");
    }

    /**
     * An interceptor may inject the bean it intercepts, {@code @Intercepted Bean<?>} (CDI 4.1, 5.5.8), written
     * {@code Bean<? extends Object>} too, and read its class when it intercepts a call (BUG-20261117-47; the injection
     * point was refused before BUG-20261117-41).
     */
    @ParameterizedTest(name = "@Intercepted Bean<{0}>")
    @ValueSource(strings = {"?", "? extends Object"})
    @DisplayName("an interceptor gets the metadata of the bean it intercepts in the processor, at start and frozen")
    void anInterceptorGetsTheMetadataOfTheBeanItIntercepts(String argument) throws Exception {
        assertEachPlaceServes(withAWatcher(argument), WATCHED_SERVICE, WATCHED_HELLO);
    }

    /**
     * Two intercepted {@code @Dependent} beans a bean injects through its constructor, both bound to
     * {@code vi.Watcher}: each instance has its own interceptor instance, a dependent object of the one instance it
     * intercepts (CDI 4.1, 6.4.1), and its {@code @Intercepted Bean} is its own bean. Both were created with the
     * creational context of the bean that injects them, which held the interceptor instances by interceptor class
     * only: the second call went through the first one's interceptor, whose {@code @Intercepted Bean} named the
     * first (BUG-20261118-36).
     */
    @Test
    @DisplayName("two intercepted beans injected into one bean have an interceptor each, in the processor, at start and"
            + " frozen")
    void twoInterceptedBeansInjectedIntoOneBeanHaveAnInterceptorEach() throws Exception {
        var sources = withAWatcher("?");
        sources.put("vi/OtherWatchedService" + JAVA, boundBean("OtherWatchedService", "Watched"));
        sources.put("vi/Pair" + JAVA, """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class Pair implements java.util.function.Supplier<String> {
                    private final WatchedService first;
                    private final OtherWatchedService second;

                    @jakarta.inject.Inject
                    public Pair(WatchedService first, OtherWatchedService second) {
                        this.first = first;
                        this.second = second;
                    }

                    @Override
                    public String get() {
                        return first.get() + " | " + second.get();
                    }
                }
                """);

        assertEachPlaceServes(sources, "vi.Pair", WATCHED_HELLO + " | Watcher(vi.OtherWatchedService: hello)");
    }

    /** {@link #withAVetoedPackage()}, with {@code vi.Watcher}, which injects {@code @Intercepted Bean<argument>}. */
    private static Map<String, String> withAWatcher(String argument) {
        var sources = withAVetoedPackage();
        putMetadataInterceptor(sources, "Watcher", "Watched", INTERCEPTED_BEAN + "<" + argument + ">");
        return sources;
    }

    /**
     * Adds to {@code sources} the binding {@code vi.binding}, the interceptor {@code vi.name} for it, which injects a
     * {@code metadata} of type {@code type} and wraps what it intercepts in {@code name(<the bean class of metadata>:
     * ...)}, and {@code vi.bindingService}, a bean bound to it.
     */
    private static void putMetadataInterceptor(Map<String, String> sources, String name, String binding, String type) {
        sources.put("vi/" + binding + JAVA, binding(binding));
        sources.put("vi/" + name + JAVA, """
                package vi;
                @%s
                @jakarta.interceptor.Interceptor
                @jakarta.annotation.Priority(jakarta.interceptor.Interceptor.Priority.APPLICATION)
                public class %s {
                    @jakarta.inject.Inject
                    %s metadata;

                    @jakarta.interceptor.AroundInvoke
                    Object wrap(jakarta.interceptor.InvocationContext invocation) throws Exception {
                        return "%s(" + metadata.getBeanClass().getName() + ": " + invocation.proceed() + ")";
                    }
                }
                """.formatted(binding, name, type, name));
        sources.put("vi/" + binding + "Service" + JAVA, boundBean(binding + "Service", binding));
    }

    /** What the processor printed of built-in metadata, as an error or as a warning. */
    private static List<String> metadataMessages(ProcessorCompiler.Result result) {
        return result.messages().stream().filter(message -> message.contains("built-in metadata")).toList();
    }

    /**
     * Checks each place on {@code sources}: the processor reports nothing of built-in metadata and compiles them, and
     * a container started on them, its extensions run at start, then frozen, answers {@code expected} from
     * {@code beanClass}, or only starts when {@code beanClass} is {@code null} ({@link #DEPLOYED}). The container
     * starts on classes compiled with the processor's validation off, so that each place gives its own verdict.
     */
    private void assertEachPlaceServes(Map<String, String> sources, String beanClass, String expected)
            throws IOException {
        var validated = ProcessorCompiler.compile(workDir.resolve("validated"), sources);
        var unvalidated = ProcessorCompiler.compile(workDir.resolve("unvalidated"), sources,
                List.of("-Avauban.validation=false"));
        assertTrue(unvalidated.success(), () -> "compilation failed: " + unvalidated.messages());
        registerExtension(unvalidated, RegistrationRecorder.class);

        assertAll(() -> assertEquals(List.of(), metadataMessages(validated), "what the processor reports"),
                () -> assertTrue(validated.success(), () -> "compilation failed: " + validated.messages()),
                () -> assertServes(unvalidated, false, beanClass, expected),
                () -> assertServes(unvalidated, true, beanClass, expected));
    }

    /** Checks what {@code beanClass} answers in a container started on {@code result}, frozen first or not. */
    private void assertServes(ProcessorCompiler.Result result, boolean frozen, String beanClass, String expected) {
        var place = frozen ? "frozen" : "at start, run";
        assertEquals(expected, assertDoesNotThrow(() -> startedAnswer(result, frozen, beanClass), place), place);
    }

    /** What {@code beanClass} answers in a container started on {@code result}, frozen first or not. */
    private Object startedAnswer(ProcessorCompiler.Result result, boolean frozen, String beanClass) throws Exception {
        if (frozen) {
            try (var loader = loader(result)) {
                var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                        .freezeExtensions(result.classes());
                assertTrue(outcome.frozen(), outcome.reason());
            }
        }
        try (var loader = loader(result); var container = VaubanContainer.builder().classLoader(loader)
                .scanClasspath().build()) {
            assertEquals(frozen, container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            return beanClass == null ? DEPLOYED : hello(container, loader.loadClass(beanClass));
        }
    }

    /**
     * An extension sees the declaration of a class outside the index as the index would have described it, in the
     * processor, at start and frozen: Mansart's {@code CrudRepository} and {@code BasicRepository}, with their type
     * parameters and generic superinterfaces, a class of the JDK, and the declarations above a class. At start and
     * frozen, every one was an empty stub; in the processor, the classes the compilation compiles were described, the
     * JDK's were stubs and the declarations above a class stopped at the index (BUG-20261117-31). The beans and their
     * types do not change, and the start takes the frozen record.
     */
    @Test
    @DisplayName("an extension sees a class outside the index described, in the processor, at start and frozen")
    void anExtensionSeesAClassOutsideTheIndexDescribedInTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Basic", """
                public interface Basic<T, K> {
                }""");
        putType(sources, "Crud", """
                public interface Crud<T, K> extends Basic<T, K> {
                }""");
        putType(sources, "Note", """
                public class Note {
                }""");
        putType(sources, ARCHIVE, """
                public interface Archive extends Crud<Note, Long> {
                }""");
        putType(sources, "Archivist", DEPENDENT + """
                public class Archivist implements Archive {
                }""");
        putType(sources, PLAIN, DEPENDENT + """
                public class Plain {
                }""");
        var parameters = "; typeParameters=[T extends java.lang.Object, K extends java.lang.Object]; interface";
        var expected = sorted(List.of("Archive superInterfacesDeclarations [vi.types.Crud]",
                TYPES + "Crud: " + NO_SUPERCLASS + "; superInterfaces=[PARAMETERIZED_TYPE vi.types.Basic<T, K> of"
                        + " TYPE_VARIABLE, TYPE_VARIABLE]" + parameters,
                "Crud superInterfacesDeclarations [vi.types.Basic]",
                TYPES + "Basic: " + NO_SUPERCLASS + "; " + NO_SUPERINTERFACE + parameters,
                "java.util.function.Supplier: " + NO_SUPERCLASS + "; " + NO_SUPERINTERFACE
                        + "; typeParameters=[T extends java.lang.Object]; interface",
                "Plain superClassDeclaration java.lang.Object"));
        var beans = new ContainerTypes();

        var seen = seenThreeWays(sources, OutsideRecorder.class, OutsideRecorder.SEEN, beans);

        assertAll(() -> seen.assertEachIs(expected), () -> beans.assertEachIs(List.of("vi.types.Archivist:"
                + " java.lang.Object, vi.types.Archive, vi.types.Archivist, vi.types.Basic<vi.types.Note,"
                + " java.lang.Long>, vi.types.Crud<vi.types.Note, java.lang.Long>",
                "vi.types.Plain: java.lang.Object, vi.types.Plain")));
    }

    /**
     * {@code ClassInfo.modifiers()} reads as {@code java.lang.reflect.Modifier} reads a class's modifiers, the same in
     * the processor, at start and frozen. At start and frozen, it gives the flags of the class file: {@code ACC_SUPER}
     * reads as {@code synchronized}, and a nested class lacks the {@code static} its {@code InnerClasses} entry carries
     * (BUG-20261117-37).
     */
    @Test
    @Disabled("BUG-20261117-37: at start, ClassInfo.modifiers() gives the class file's flags")
    @DisplayName("an extension sees a class's modifiers the same in the processor, at start and frozen")
    void anExtensionSeesTheModifiersOfAClassInTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, PLAIN, DEPENDENT + """
                public class Plain {
                    public static class Inner {
                    }
                }""");

        var seen = seenThreeWays(sources, ModifierRecorder.class, ModifierRecorder.SEEN);

        seen.assertEachIs(sorted(List.of(TYPES + "Plain: public", TYPES + "Plain$Inner: public static")));
    }

    /**
     * The methods an extension sees of a class with a lambda and a bridge method, the same in the processor, at start
     * and frozen: a class outside the index, and a bean. The compiler shows the methods the source declares; at start
     * and frozen, the class file adds the bridge {@code get()} returning {@code Object} and the lambda's
     * {@code lambda$get$0} (BUG-20261117-43).
     */
    @Test
    @Disabled("BUG-20261117-43: at start and frozen, the language model shows the synthetic methods of a class file")
    @DisplayName("an extension sees the same methods of a class with a lambda and a bridge, in the processor, at"
            + " start and frozen")
    void anExtensionSeesNoSyntheticMethodInTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withAVetoedPackage();
        var body = """
                implements java.util.function.Supplier<String> {
                    @Override
                    public String get() {
                        Runnable noop = () -> { };
                        noop.run();
                        return "told";
                    }
                }""";
        putType(sources, TELLER, "public class Teller " + body);
        putType(sources, COUNTER, DEPENDENT + "public class Counter " + body);

        var seen = seenThreeWays(sources, MethodRecorder.class, MethodRecorder.SEEN);

        seen.assertEachIs(sorted(List.of(TYPES + COUNTER + " methods [get]", TYPES + TELLER + " methods [get]")));
    }

    /** Records, in {@code @Synthesis}, the names of the methods of {@code Teller} and of the bean {@code Counter}. */
    public static class MethodRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void methods(Types types) {
            for (var name : List.of(TYPES + TELLER, TYPES + COUNTER)) {
                SEEN.add(name + " methods " + types.ofClass(name).declaration().methods().stream()
                        .map(jakarta.enterprise.lang.model.declarations.MethodInfo::name).sorted().toList());
            }
        }
    }

    /** The class whose methods the method member recorders read (BUG-20261117-51 to -54). */
    private static final String MEMBERS = "Members";

    /**
     * {@link #withAVetoedPackage()}, with the bean {@code vi.types.Members}: a generic class whose methods declare
     * type parameters, generic {@code throws} types, and are static or not, and its constructors.
     */
    private static Map<String, String> withMethodMembers() {
        var sources = withAVetoedPackage();
        putType(sources, MEMBERS, DEPENDENT + """
                public class Members<C extends Number, E extends Exception> {
                    public Members() {
                    }
                    public Members(int size) {
                    }
                    public <T, U extends C, W extends Number & CharSequence,
                            X extends java.util.List<? super String>> void typed() {
                    }
                    public static <S extends Comparable<S>> S sorted(S value) {
                        return value;
                    }
                    public <F extends Throwable> void throwing() throws java.io.IOException, E, F {
                    }
                    public void plain() throws java.io.IOException {
                    }
                }""");
        return sources;
    }

    /** {@code type} by its name: a class or a type variable by its name, the rest as the language model writes it. */
    private static String memberTypeName(Type type) {
        return switch (type.kind()) {
            case CLASS -> type.asClass().declaration().name();
            case PARAMETERIZED_TYPE -> type.asParameterizedType().genericClass().declaration().name()
                    + type.asParameterizedType().typeArguments().stream()
                            .map(VetoedInterceptorCompileTimeTest::memberTypeName)
                            .collect(Collectors.joining(", ", "<", ">"));
            case TYPE_VARIABLE -> type.asTypeVariable().name();
            case WILDCARD_TYPE -> wildcardName(type.asWildcardType());
            default -> type.toString();
        };
    }

    private static String wildcardName(WildcardType wildcard) {
        if (wildcard.lowerBound() != null) {
            return "? super " + memberTypeName(wildcard.lowerBound());
        }
        return wildcard.upperBound() == null ? "?" : "? extends " + memberTypeName(wildcard.upperBound());
    }

    /**
     * Records, in {@code @Synthesis}, the type parameters each method of {@code vi.types.Members} declares, with their
     * bounds, and the bounds of a type variable of the class a bound names (BUG-20261117-51).
     */
    public static class MethodTypeParameterRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void typeParameters(Types types) {
            for (var method : types.ofClass(TYPES + MEMBERS).declaration().methods()) {
                SEEN.add(method.name() + method.typeParameters().stream()
                        .map(variable -> variable.name() + " extends " + variable.bounds().stream()
                                .map(MethodTypeParameterRecorder::bound).collect(Collectors.joining(" & ")))
                        .collect(Collectors.joining(", ", "<", ">")));
            }
        }

        private static String bound(Type bound) {
            return bound.isTypeVariable()
                    ? bound.asTypeVariable().name() + bound.asTypeVariable().bounds().stream()
                            .map(VetoedInterceptorCompileTimeTest::memberTypeName).toList()
                    : memberTypeName(bound);
        }
    }

    /**
     * {@code MethodInfo.typeParameters()} gives the type parameters a method declares, in declaration order, each
     * with the bounds it declares, {@code java.lang.Object} for one that declares none, in the processor, at start and
     * frozen: a bound may name a type variable of the class, with its own bound, be an intersection, or a
     * parameterized type with a wildcard. It was empty all three ways, though the bytecode index read them
     * (BUG-20261117-51).
     */
    @Test
    @DisplayName("an extension sees the type parameters of a method in the processor, at start and frozen")
    void anExtensionSeesTheTypeParametersOfAMethodInTheProcessorAtStartAndFrozen() throws Exception {
        var expected = List.of("plain<>",
                "sorted<S extends java.lang.Comparable<S>>",
                "throwing<F extends java.lang.Throwable>",
                "typed<T extends java.lang.Object, U extends C[java.lang.Number], W extends java.lang.Number"
                        + " & java.lang.CharSequence, X extends java.util.List<? super java.lang.String>>");

        var seen = seenThreeWays(withMethodMembers(), MethodTypeParameterRecorder.class,
                MethodTypeParameterRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * Records, in {@code @Synthesis}, the types each method of {@code vi.types.Members} declares it throws, each
     * with its kind, and a type variable with its bounds (BUG-20261117-52).
     */
    public static class ThrowsTypeRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Synthesis
        public void throwsTypes(Types types) {
            for (var method : types.ofClass(TYPES + MEMBERS).declaration().methods()) {
                SEEN.add(method.name() + " throws " + method.throwsTypes().stream()
                        .map(type -> type.kind() + " " + memberTypeName(type) + (type.isTypeVariable()
                                ? type.asTypeVariable().bounds().stream()
                                        .map(VetoedInterceptorCompileTimeTest::memberTypeName).toList()
                                : ""))
                        .toList());
            }
        }
    }

    /**
     * {@code MethodInfo.throwsTypes()} gives the types a method declares it throws as it declares them, in the
     * processor, at start and frozen: a type variable of the method or of its class stays a type variable, with its
     * bound. At start and frozen, it was the erasure the class file's {@code Exceptions} attribute names
     * (BUG-20261117-52).
     */
    @Test
    @DisplayName("an extension sees the generic throws types of a method in the processor, at start and frozen")
    void anExtensionSeesTheGenericThrowsTypesOfAMethodInTheProcessorAtStartAndFrozen() throws Exception {
        var expected = List.of("plain throws [CLASS java.io.IOException]",
                "sorted throws []",
                "throwing throws [CLASS java.io.IOException, TYPE_VARIABLE E[java.lang.Exception],"
                        + " TYPE_VARIABLE F[java.lang.Throwable]]",
                "typed throws []");

        var seen = seenThreeWays(withMethodMembers(), ThrowsTypeRecorder.class, ThrowsTypeRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /** The classes whose methods and constructors {@link ReceiverTypeRecorder} reads. */
    private static final List<String> RECEIVER_PROBES = List.of(MEMBERS, "Simple");

    /** Adds the classes {@link #RECEIVER_PROBES} names to the deployment, whatever annotates them. */
    private static void addReceiverProbes(ScannedClasses classes) {
        RECEIVER_PROBES.forEach(name -> classes.add(TYPES + name));
    }

    /**
     * Records, in {@code @Synthesis}, the receiver type of each method and constructor of the classes
     * {@link #RECEIVER_PROBES} names: a constructor as {@code new/} and its parameter count (BUG-20261117-53).
     */
    public static class ReceiverTypeRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            addReceiverProbes(classes);
        }

        @Synthesis
        public void receiverTypes(Types types) {
            for (var probe : RECEIVER_PROBES) {
                var type = types.ofClass(TYPES + probe).declaration();
                Stream.concat(type.constructors().stream(), type.methods().stream()).forEach(method -> {
                    var receiver = method.receiverType();
                    SEEN.add(probe + "#" + (method.isConstructor() ? "new/" + method.parameters().size()
                            : method.name()) + " receiver "
                            + (receiver == null ? "null" : receiver.kind() + " " + memberTypeName(receiver)));
                });
            }
        }
    }

    /** {@link #withMethodMembers()}, with {@code vi.types.Simple}, a class that declares no type parameter. */
    private static Map<String, String> withReceivers() {
        var sources = withMethodMembers();
        putType(sources, "Simple", """
                public class Simple {
                    public Simple() {
                    }
                    public void run() {
                    }
                    public void annotated(Simple this) {
                    }
                    public static Simple make() {
                        return new Simple();
                    }
                }""");
        return sources;
    }

    /**
     * {@code MethodInfo.receiverType()} is the type of the declaring class for an instance method, with its type
     * variables as type arguments for a generic class, whether the method declares its receiver parameter or not,
     * and {@code null} for a static method and for a constructor of a top-level class, in the processor, at start and
     * frozen. It was {@code null} for every method (BUG-20261117-53).
     */
    @Test
    @DisplayName("an extension sees the receiver type of a method in the processor, at start and frozen")
    void anExtensionSeesTheReceiverTypeOfAMethodInTheProcessorAtStartAndFrozen() throws Exception {
        var members = "PARAMETERIZED_TYPE vi.types.Members<C, E>";
        var simple = "CLASS vi.types.Simple";
        var expected = sorted(List.of("Members#new/0 receiver null", "Members#new/1 receiver null",
                "Members#plain receiver " + members, "Members#sorted receiver null",
                "Members#throwing receiver " + members, "Members#typed receiver " + members,
                "Simple#annotated receiver " + simple, "Simple#make receiver null", "Simple#new/0 receiver null",
                "Simple#run receiver " + simple));

        var seen = seenThreeWays(withReceivers(), ReceiverTypeRecorder.class, ReceiverTypeRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * Records, in {@code @Synthesis}, the name and the return type of each constructor of the classes
     * {@link #RECEIVER_PROBES} names (BUG-20261117-54).
     */
    public static class ConstructorRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            addReceiverProbes(classes);
        }

        @Synthesis
        public void constructors(Types types) {
            for (var probe : RECEIVER_PROBES) {
                for (var constructor : types.ofClass(TYPES + probe).declaration().constructors()) {
                    var returned = constructor.returnType();
                    SEEN.add(probe + "/" + constructor.parameters().size() + " name " + constructor.name()
                            + " returns " + returned.kind() + " " + memberTypeName(returned));
                }
            }
        }
    }

    /**
     * A constructor's {@code MethodInfo.name()} is the binary name of its class, and its {@code returnType()} the
     * class type of its class, as the language model asks, in the processor, at start and frozen. They were
     * {@code <init>} and {@code void} (BUG-20261117-54).
     */
    @Test
    @DisplayName("an extension sees a constructor named and typed after its class, the three ways")
    void anExtensionSeesAConstructorNamedAndTypedAfterItsClassInTheProcessorAtStartAndFrozen() throws Exception {
        var members = " name vi.types.Members returns CLASS vi.types.Members";
        var expected = List.of("Members/0" + members, "Members/1" + members,
                "Simple/0 name vi.types.Simple returns CLASS vi.types.Simple");

        var seen = seenThreeWays(withReceivers(), ConstructorRecorder.class, ConstructorRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /** The inner class whose constructor {@link InnerReceiverRecorder} reads (BUG-20261118-01). */
    private static final String INNER = TYPES + "Enclosing$Inner";

    /** Records, in {@code @Synthesis}, the receiver type of the constructor of {@link #INNER}. */
    public static class InnerReceiverRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(INNER);
        }

        @Synthesis
        public void receiverType(Types types) {
            for (var constructor : types.ofClass(INNER).declaration().constructors()) {
                var receiver = constructor.receiverType();
                SEEN.add("receiver " + (receiver == null ? "null" : receiver.kind() + " " + memberTypeName(receiver)));
            }
        }
    }

    /**
     * The constructor of an inner class may declare a receiver parameter, of the class that encloses it: its
     * {@code receiverType()} is that class, as {@code Constructor.getAnnotatedReceiverType()} gives it, in the
     * processor, at start and frozen (BUG-20261118-01).
     */
    @Test
    @DisplayName("an extension sees the receiver type of an inner class's constructor")
    void anExtensionSeesTheReceiverTypeOfAnInnerClassConstructor() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Enclosing", """
                public class Enclosing {
                    public class Inner {
                        public Inner() {
                        }
                    }
                }""");

        var seen = seenThreeWays(sources, InnerReceiverRecorder.class, InnerReceiverRecorder.SEEN);

        seen.assertEachIs(List.of("receiver CLASS vi.types.Enclosing"));
    }

    /**
     * An inner class sees the type variables of the class that encloses it with the bounds they declare, and its
     * constructor's receiver type is the enclosing class, parameterized by them, in the processor, at start and
     * frozen (BUG-20261118-01).
     */
    @Test
    @DisplayName("an inner class sees its enclosing class's type variables with their bounds, three ways")
    void anInnerClassSeesItsEnclosingClassTypeVariables() throws Exception {
        var sources = withAVetoedPackage();
        putType(sources, "Enclosing", """
                public class Enclosing<T extends Number> {
                    public class Inner {
                        T value;
                        public Inner() {
                        }
                    }
                }""");

        var seen = seenThreeWays(sources, EnclosingVariableRecorder.class, EnclosingVariableRecorder.SEEN);

        seen.assertEachIs(List.of("receiver vi.types.Enclosing<T extends java.lang.Number>",
                "value T extends java.lang.Number"));
    }

    /** Records the type of {@link #INNER}'s field and the receiver type of its constructor. */
    public static class EnclosingVariableRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(INNER);
        }

        @Synthesis
        public void enclosingVariables(Types types) {
            var inner = types.ofClass(INNER).declaration();
            inner.fields().forEach(field -> SEEN.add(field.name() + " " + field.type()));
            inner.constructors().forEach(constructor -> SEEN.add("receiver " + constructor.receiverType()));
        }
    }

    /** Throws in {@code @Discovery}, as an extension that meets a problem it cannot get past. */
    public static class ThrowingDiscovery implements BuildCompatibleExtension {

        static final String CAUSE = "no configuration to discover from";

        @Discovery
        public void discover(ScannedClasses scanned) {
            throw new IllegalStateException(CAUSE);
        }
    }

    /**
     * An exception a {@code @Discovery} method throws is a definition error, as CDI 4.1 treats one of a
     * {@code BeforeBeanDiscovery} observer: the build fails, the start fails, and the freeze records nothing, so that
     * the start runs the extension and fails again. Each names the extension and the exception (BUG-20261118-04).
     */
    @Test
    @DisplayName("an exception of @Discovery fails the build, the start and the freeze")
    void anExceptionOfDiscoveryFailsTheBuildTheStartAndTheFreeze() throws Exception {
        var failure = "@Discovery of " + ThrowingDiscovery.class.getName() + " failed: "
                + IllegalStateException.class.getName() + ": " + ThrowingDiscovery.CAUSE;

        var started = discoveryFailsThreeWays(ThrowingDiscovery.class, failure);

        assertEquals(ThrowingDiscovery.CAUSE, started.getCause().getMessage());
    }

    /** Declares a {@code @Discovery} parameter of a type the phase does not support, and records each call. */
    public static class UnsupportedDiscoveryParameter implements BuildCompatibleExtension {

        static final List<String> CALLED = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Discovery
        public void discover(Types types) {
            CALLED.add("discover with " + types);
        }
    }

    /**
     * A {@code @Discovery} parameter of a type the phase does not support is reported before the call, naming the
     * extension, the method and the type, as the other phases report theirs: a definition error. The method is not
     * called with {@code null} (BUG-20261118-22).
     */
    @Test
    @DisplayName("a @Discovery parameter of an unsupported type fails the build, the start and the freeze")
    void aDiscoveryParameterOfAnUnsupportedTypeFailsTheBuildTheStartAndTheFreeze() throws Exception {
        var failure = "@Discovery method " + UnsupportedDiscoveryParameter.class.getName()
                + ".discover has invalid parameter type: " + Types.class.getName();
        UnsupportedDiscoveryParameter.CALLED.clear();

        discoveryFailsThreeWays(UnsupportedDiscoveryParameter.class, failure);

        List<String> called = UnsupportedDiscoveryParameter.CALLED;
        assertEquals(List.of(), called, "the method is not called");
    }

    /** Reports an error through the {@code Messages} of {@code @Discovery}. */
    public static class ReportingDiscovery implements BuildCompatibleExtension {

        static final String ERROR = "nothing to discover";

        @Discovery
        public void discover(Messages messages) {
            messages.error(ERROR);
        }
    }

    /**
     * An error a {@code @Discovery} method reports through {@code Messages} is a deployment problem, as the Javadoc
     * of {@code Messages} says, in the processor, at start and frozen (BUG-20261118-22).
     */
    @Test
    @DisplayName("an error @Discovery reports through Messages fails the build, the start and the freeze")
    void anErrorDiscoveryReportsThroughMessagesFailsTheBuildTheStartAndTheFreeze() throws Exception {
        failsThreeWays(withAVetoedPackage(), ReportingDiscovery.class, ReportingDiscovery.ERROR,
                DeploymentException.class);
    }

    /** Records the class {@code Types} gives for each {@code @Dependent} listener {@code @Enhancement} sees. */
    public static class TypesEnhancer implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Enhancement(types = java.util.EventListener.class, withSubtypes = true, withAnnotations = Dependent.class)
        public void listeners(ClassInfo type, Types types) {
            SEEN.add(types.ofClass(type).declaration().name());
        }
    }

    /** Reports through {@code Messages} each {@code @Dependent} listener {@code @Enhancement} sees. */
    public static class MessagesEnhancer implements BuildCompatibleExtension {

        @Enhancement(types = java.util.EventListener.class, withSubtypes = true, withAnnotations = Dependent.class)
        public void listeners(ClassInfo type, Messages messages) {
            messages.error("no listener allowed: " + type.name());
        }
    }

    /**
     * {@code @Enhancement} gets the {@code Types} its Javadoc lists, from the processor, at start and frozen; it got
     * {@code null} (BUG-20261118-23).
     */
    @Test
    @DisplayName("@Enhancement gets Types, from the processor, at start and frozen")
    void enhancementGetsTypesFromTheProcessorAtStartAndFrozen() throws Exception {
        var seen = seenThreeWays(withListenerSubtypes(), TypesEnhancer.class, TypesEnhancer.SEEN);

        seen.distinct().assertEachIs(List.of("vi.Impl", "vi.Other"));
    }

    /**
     * {@code @Enhancement} gets the {@code Messages} its Javadoc lists, and an error it reports fails the build, the
     * start and the freeze, as the deployment problem the phase reports today (BUG-20261118-23).
     */
    @Test
    @DisplayName("an error @Enhancement reports through Messages fails the build, the start and the freeze")
    void anErrorEnhancementReportsThroughMessagesFailsTheBuildTheStartAndTheFreeze() throws Exception {
        failsThreeWays(withListenerSubtypes(), MessagesEnhancer.class, "no listener allowed: vi.Impl",
                DeploymentException.class);
    }

    /** {@link #failsThreeWays} for an extension that fails in {@code @Discovery}, with a definition error. */
    private RuntimeException discoveryFailsThreeWays(Class<? extends BuildCompatibleExtension> extension,
                                                     String failure) throws Exception {
        return failsThreeWays(withAVetoedPackage(), extension, failure, DefinitionException.class);
    }

    /**
     * Checks that {@code extension} fails on {@code sources} with {@code failure} three ways: the build fails, the
     * start fails with {@code thrown}, and the freeze records nothing, so that the start runs the extension and fails
     * again; returns the exception of the first start.
     */
    private RuntimeException failsThreeWays(Map<String, String> sources,
                                            Class<? extends BuildCompatibleExtension> extension, String failure,
                                            Class<? extends RuntimeException> thrown) throws Exception {
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(extension));

        var processed = ProcessorCompiler.compile(workDir.resolve("processed"), sources, List.of(), processor);
        var result = compile(sources);
        registerExtension(result, extension);
        try (var loader = loader(result)) {
            var started = assertThrows(thrown,
                    VaubanContainer.builder().classLoader(loader).scanClasspath()::build, "the container starts");
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(result.classes());
            var afterTheFreeze = assertThrows(thrown,
                    VaubanContainer.builder().classLoader(loader).scanClasspath()::build, "it starts after the freeze");

            assertAll(() -> assertFalse(processed.success(), "the build fails"),
                    () -> assertTrue(processed.messages().stream().anyMatch(m -> m.contains(failure)),
                            () -> String.join("\n", processed.messages())),
                    () -> assertTrue(started.getMessage().contains(failure), started.getMessage()),
                    () -> assertFalse(outcome.frozen(), "the freeze records nothing"),
                    () -> assertTrue(outcome.reason().contains(failure), outcome.reason()),
                    () -> assertTrue(afterTheFreeze.getMessage().contains(failure), afterTheFreeze.getMessage()));
            return started;
        }
    }

    /** The class whose annotations {@link AnnotationMemberRecorder} reads (BUG-20261117-55). */
    private static final String DEFAULTED = TYPES + "Defaulted";

    /**
     * Records, in {@code @Synthesis}, the members of each annotation of {@link #DEFAULTED}, and of each annotation
     * nested in them, as {@link #membersOf} writes them (BUG-20261117-55).
     */
    public static class AnnotationMemberRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(DEFAULTED);
        }

        @Synthesis
        public void members(Types types) {
            for (var annotation : types.ofClass(DEFAULTED).declaration().annotations()) {
                SEEN.add(membersOf(annotation));
                annotation.members().values().stream().filter(AnnotationMember::isNestedAnnotation)
                        .forEach(member -> SEEN.add("nested " + membersOf(member.asNestedAnnotation())));
            }
        }
    }

    /**
     * {@code annotation} by its simple name, whether it has the member {@code rank}, and each member it shows,
     * sorted, each read back through {@code member(name)}.
     */
    private static String membersOf(AnnotationInfo annotation) {
        var members = new TreeMap<>(annotation.members()).keySet().stream()
                .map(name -> name + "=" + memberText(annotation.member(name))).collect(Collectors.joining(" "));
        return "@" + simpleName(annotation.name()) + " rank " + annotation.hasMember("rank") + ": " + members;
    }

    /** A member value: a string or an int as written, an array element by element, a nested annotation by name. */
    private static String memberText(AnnotationMember member) {
        return switch (member.kind()) {
            case STRING -> member.asString();
            case INT -> String.valueOf(member.asInt());
            case ARRAY -> member.asArray().stream().map(VetoedInterceptorCompileTimeTest::memberText).toList()
                    .toString();
            case NESTED_ANNOTATION -> "@" + simpleName(member.asNestedAnnotation().name());
            default -> member.kind().toString();
        };
    }

    private static String simpleName(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    /**
     * {@link #withAVetoedPackage()}, with the annotations {@code vi.types.Labelled}, whose members {@code rank} and
     * {@code tags} declare defaults, and {@code vi.types.Paired}, whose member {@code second} defaults to a nested
     * {@code @Labelled}; and {@code vi.types.Defaulted}, annotated with both, writing no member that has a default
     * but one in a nested annotation.
     */
    private static Map<String, String> withAnnotationDefaults() {
        var sources = withAVetoedPackage();
        var runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n";
        putType(sources, "Labelled", runtime + """
                public @interface Labelled {
                    String value();
                    int rank() default 3;
                    String[] tags() default {"a", "b"};
                }""");
        putType(sources, "Paired", runtime + """
                public @interface Paired {
                    Labelled first();
                    Labelled second() default @Labelled("q");
                }""");
        putType(sources, "Defaulted", """
                @Labelled("x")
                @Paired(first = @Labelled(value = "p", rank = 7))
                public class Defaulted {
                }""");
        return sources;
    }

    /**
     * An annotation shows the members it does not write with the defaults its type declares, through
     * {@code members()}, {@code member(name)} and {@code hasMember(name)}, a nested annotation too, in the processor,
     * at start and frozen. It showed the members it writes alone (BUG-20261117-55).
     */
    @Test
    @DisplayName("an extension sees the default values of an annotation in the processor, at start and frozen")
    void anExtensionSeesTheDefaultValuesOfAnAnnotationInTheProcessorAtStartAndFrozen() throws Exception {
        var expected = sorted(List.of("@Labelled rank true: rank=3 tags=[a, b] value=x",
                "@Paired rank false: first=@Labelled second=@Labelled",
                "nested @Labelled rank true: rank=7 tags=[a, b] value=p",
                "nested @Labelled rank true: rank=3 tags=[a, b] value=q"));

        var seen = seenThreeWays(withAnnotationDefaults(), AnnotationMemberRecorder.class,
                AnnotationMemberRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /** The class whose repeated annotations {@link RepeatableRecorder} reads (BUG-20261117-56). */
    private static final String TAGGED = TYPES + "Tagged";

    /**
     * Records, in {@code @Synthesis}, the values of the {@link Tag} each declaration of {@link #TAGGED} shows through
     * {@code repeatableAnnotation}, and of the {@link Mark} its field {@code marks} shows on its type argument
     * (BUG-20261117-56).
     */
    public static class RepeatableRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(TAGGED);
        }

        @Synthesis
        public void repeated(Types types) {
            var tagged = types.ofClass(TAGGED).declaration();
            SEEN.add("class " + tags(tagged.repeatableAnnotation(Tag.class)));
            for (var method : tagged.methods()) {
                SEEN.add("method " + method.name() + " " + tags(method.repeatableAnnotation(Tag.class)));
                method.parameters().forEach(parameter -> SEEN.add("parameter of " + method.name() + " "
                        + tags(parameter.repeatableAnnotation(Tag.class))));
            }
            for (var field : tagged.fields()) {
                SEEN.add("field " + field.name() + " " + tags(field.repeatableAnnotation(Tag.class)));
            }
            var marked = tagged.fields().stream().filter(field -> field.name().equals("marks")).findFirst()
                    .orElseThrow().type().asParameterizedType().typeArguments().getFirst();
            SEEN.add("type argument " + tags(marked.repeatableAnnotation(Mark.class)));
        }
    }

    /** The {@code value} of each annotation, sorted. */
    private static List<String> tags(Collection<AnnotationInfo> annotations) {
        return annotations.stream().map(annotation -> annotation.value().asString()).sorted().toList();
    }

    /**
     * {@link #withAVetoedPackage()}, with {@code vi.types.Tagged}, which repeats {@link Tag} on itself, a method, a
     * parameter and a field, writes it once on a method and both repeated and in its container on another, and
     * repeats {@link Mark} on a type argument.
     */
    private static Map<String, String> withRepeatedAnnotations() {
        var sources = withAVetoedPackage();
        putType(sources, "Tagged", """
                import %1$s;
                import %2$s;
                @Tag("a") @Tag("b")
                public class Tagged {
                    @Tag("f") @Tag("g")
                    public String field;
                    public java.util.List<@Mark("1") @Mark("2") String> marks;
                    @Tag("m") @Tag("n")
                    public void run(@Tag("p") @Tag("q") String value) {
                    }
                    @Tag("single")
                    public void single() {
                    }
                    @Tag("x") @Tag.List({@Tag("y"), @Tag("z")})
                    public void mixed() {
                    }
                }""".formatted(Tag.class.getName(), Mark.class.getName()));
        return sources;
    }

    /**
     * {@code repeatableAnnotation} gives the annotations of the type written on the declaration, those its container
     * holds when it is repeated, and both when both are written, as {@code getAnnotationsByType} does, on a class, a
     * method, a parameter, a field and a type use, in the processor, at start and frozen. It gave the annotation when
     * written once, and nothing when repeated (BUG-20261117-56).
     */
    @Test
    @DisplayName("an extension sees the repeated annotations in their container in the processor, at start and frozen")
    void anExtensionSeesTheRepeatedAnnotationsInTheProcessorAtStartAndFrozen() throws Exception {
        var expected = sorted(List.of("class [a, b]", "field field [f, g]", "field marks []", "method mixed [x, y, z]",
                "method run [m, n]", "method single [single]", "parameter of run [p, q]", "type argument [1, 2]"));

        var seen = seenThreeWays(withRepeatedAnnotations(), RepeatableRecorder.class, RepeatableRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * Records, in {@code @Synthesis}, the annotations the type argument of the field {@code marks} of {@link #TAGGED}
     * shows, and those its field {@code field} shows, each with its {@code value} as {@link #memberText} writes it
     * (BUG-20261118-26).
     */
    public static class RepeatedTypeUseRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(TAGGED);
        }

        @Synthesis
        public void shown(Types types) {
            for (var field : types.ofClass(TAGGED).declaration().fields()) {
                var type = field.type();
                var shown = field.name().equals("marks")
                        ? type.asParameterizedType().typeArguments().getFirst().annotations() : field.annotations();
                SEEN.add(field.name() + " " + shown.stream()
                        .map(annotation -> simpleName(annotation.name()) + " " + memberText(annotation.value()))
                        .toList());
            }
        }
    }

    /**
     * A type use that repeats an annotation shows the container that holds them, as the class file records it and as
     * a declaration shows it, in the processor, at start and frozen. The processor showed each repeated annotation on
     * its own (BUG-20261118-26).
     */
    @Test
    @DisplayName("an extension sees the container of an annotation repeated on a type use the three ways")
    void anExtensionSeesTheContainerOfAnAnnotationRepeatedOnATypeUse() throws Exception {
        var expected = List.of("field [Tag$List [@Tag, @Tag]]", "marks [Mark$List [@Mark, @Mark]]");

        var seen = seenThreeWays(withRepeatedAnnotations(), RepeatedTypeUseRecorder.class,
                RepeatedTypeUseRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /**
     * Records, in {@code @Synthesis}, the annotations the package of {@link #DEFAULTED} shows, as {@link #membersOf}
     * writes them, and what its other annotation methods answer for {@code @Labelled} (BUG-20261117-57).
     */
    public static class PackageAnnotationRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(DEFAULTED);
        }

        @Synthesis
        public void annotations(Types types) {
            var pkg = types.ofClass(DEFAULTED).declaration().packageInfo();
            pkg.annotations().forEach(annotation -> SEEN.add(pkg.name() + " " + membersOf(annotation)));
            SEEN.add(pkg.name() + " has @Labelled "
                    + pkg.hasAnnotation(annotation -> annotation.name().equals(TYPES + "Labelled")));
        }
    }

    /**
     * A package shows the annotations its {@code package-info} declares, in the processor, at start and frozen. It
     * showed none (BUG-20261117-57).
     */
    @Test
    @DisplayName("an extension sees the annotations of a package in the processor, at start and frozen")
    void anExtensionSeesTheAnnotationsOfAPackageInTheProcessorAtStartAndFrozen() throws Exception {
        var sources = withAnnotationDefaults();
        sources.put("vi/types/package-info" + JAVA, "@Labelled(\"pkg\")\npackage vi.types;\n");
        var expected = List.of("vi.types @Labelled rank true: rank=3 tags=[a, b] value=pkg",
                "vi.types has @Labelled true");

        var seen = seenThreeWays(sources, PackageAnnotationRecorder.class, PackageAnnotationRecorder.SEEN);

        seen.assertEachIs(expected);
    }

    /** The class whose annotations {@link RetentionRecorder} reads (BUG-20261118-28). */
    private static final String RETAINED = TYPES + "Retained";

    /**
     * Records, in {@code @Synthesis}, the annotations {@link #RETAINED}, its field, its method, its parameter and its
     * package show, and, in {@code @Enhancement}, each class that carries {@link ClassRetained} (BUG-20261118-28).
     */
    public static class RetentionRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Discovery
        public void discovery(ScannedClasses classes) {
            classes.add(RETAINED);
        }

        @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = ClassRetained.class)
        public void classRetained(ClassInfo type) {
            SEEN.add("enhanced " + type.name());
        }

        @Synthesis
        public void shown(Types types) {
            var retained = types.ofClass(RETAINED).declaration();
            SEEN.add("class " + names(retained.annotations()));
            SEEN.add("package " + names(retained.packageInfo().annotations()));
            retained.fields().forEach(field -> SEEN.add("field " + names(field.annotations())));
            for (var method : retained.methods()) {
                SEEN.add("method " + names(method.annotations()));
                method.parameters().forEach(parameter -> SEEN.add("parameter " + names(parameter.annotations())));
            }
        }

        private static List<String> names(Collection<AnnotationInfo> annotations) {
            return annotations.stream().map(annotation -> simpleName(annotation.name())).sorted().toList();
        }
    }

    /**
     * {@link #withAVetoedPackage()}, with {@code vi.types.Retained}, whose class, field, method, parameter and package
     * carry {@link ClassRetained} and {@link SourceRetained} besides {@code @Deprecated}, which is runtime-retained.
     */
    private static Map<String, String> withRetainedAnnotations() {
        var sources = withAVetoedPackage();
        var all = "@%s @%s @Deprecated".formatted(ClassRetained.class.getName(), SourceRetained.class.getName());
        putType(sources, "Retained", """
                %1$s
                public class Retained {
                    %1$s
                    public String field;
                    %1$s
                    public void run(%1$s String value) {
                    }
                }""".formatted(all));
        sources.put("vi/types/package-info" + JAVA, all + "\npackage vi.types;\n");
        return sources;
    }

    /**
     * The language model shows the runtime-retained annotations alone, those the class file keeps for reflection, in
     * the processor, at start and frozen, and an {@code @Enhancement} of a class-retained annotation is called for no
     * class. The processor showed the class- and source-retained ones too, and called it (BUG-20261118-28).
     */
    @Test
    @DisplayName("an extension sees the runtime-retained annotations alone in the processor, at start and frozen")
    void anExtensionSeesTheRuntimeRetainedAnnotationsAloneInTheProcessorAtStartAndFrozen() throws Exception {
        var expected = List.of("class [Deprecated]", "field [Deprecated]", "method [Deprecated]",
                "package [Deprecated]", "parameter [Deprecated]");

        var seen = seenThreeWays(withRetainedAnnotations(), RetentionRecorder.class, RetentionRecorder.SEEN);

        seen.assertEachIs(expected);
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

    /** What an extension records when the processor runs it, when the container runs it at start, and frozen. */
    private record SeenThreeWays(List<String> processor, List<String> start, List<String> build) {

        void assertEachIs(List<String> expected) {
            assertAll(() -> assertEquals(expected, processor, "what the extension sees in the processor"),
                    () -> assertEquals(expected, start, "what the extension sees at start, run"),
                    () -> assertEquals(expected, build, "what the extension sees at build, frozen"));
        }

        /** What each saw, each element once. */
        SeenThreeWays distinct() {
            return new SeenThreeWays(processor.stream().distinct().toList(), start.stream().distinct().toList(),
                    build.stream().distinct().toList());
        }
    }

    /** What a test checks of each container {@link #seenThreeWays} starts, given the loader of the compiled classes. */
    @FunctionalInterface
    private interface StartedCheck {
        void check(VaubanContainer container, ClassLoader loader) throws Exception;
    }

    /** {@link #seenThreeWays(Map, Class, List, StartedCheck)}, checking nothing more of the containers it starts. */
    private SeenThreeWays seenThreeWays(Map<String, String> sources, Class<? extends BuildCompatibleExtension> extension,
                                        List<String> seen) throws Exception {
        return seenThreeWays(sources, extension, seen, (container, loader) -> assertNotNull(container));
    }

    /**
     * Compiles {@code sources} twice: once with a processor that runs {@code extension}, and once without, for the
     * container to run it at start, and for the build to freeze it, after which it runs no more at start. Each time,
     * {@code seen} is what the extension recorded. {@code check} runs on the container started with the extension run
     * at start, and on the one started frozen.
     */
    private SeenThreeWays seenThreeWays(Map<String, String> sources, Class<? extends BuildCompatibleExtension> extension,
                                        List<String> seen, StartedCheck check) throws Exception {
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(extension));
        seen.clear();
        var processed = ProcessorCompiler.compile(workDir.resolve("processed"), sources, List.of(), processor);
        assertTrue(processed.success(), () -> "compilation failed: " + processed.messages());
        var inProcessor = sorted(seen);

        var result = compile(sources);
        registerExtension(result, extension);
        seen.clear();
        try (var loader = loader(result); var container = VaubanContainer.builder().classLoader(loader)
                .scanClasspath().build()) {
            assertFalse(container.extensionsAtStart().frozen());
            assertEquals(LOGGED_HELLO, hello(container, loader.loadClass(LOGGED_SERVICE)));
            check.check(container, loader);
        }
        var atStart = sorted(seen);

        seen.clear();
        try (var loader = loader(result)) {
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(result.classes());
            assertTrue(outcome.frozen(), outcome.reason());
        }
        var atBuild = sorted(seen);
        seen.clear();
        try (var loader = loader(result); var container = VaubanContainer.builder().classLoader(loader)
                .scanClasspath().build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertEquals(LOGGED_HELLO, hello(container, loader.loadClass(LOGGED_SERVICE)));
            check.check(container, loader);
        }
        assertEquals(List.of(), seen, "a frozen extension does not run at start");
        return new SeenThreeWays(inProcessor, atStart, atBuild);
    }

    /** Lists {@code extension} as a service of the compiled classes, so that the container finds it. */
    private static void registerExtension(ProcessorCompiler.Result result, Class<?> extension) throws IOException {
        var services = result.classes().resolve("META-INF/services/" + BuildCompatibleExtension.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, extension.getName() + "\n");
    }

    private static List<String> recorded() {
        return sorted(RegistrationRecorder.SEEN);
    }

    private static List<String> sorted(List<String> seen) {
        return seen.stream().sorted().toList();
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

    /**
     * {@link #withAVetoedPackage()}, where both interceptors extend {@code vi.Wrapping}, an abstract class the
     * compilation compiles that implements {@link Serializable}, which no bean has: no annotation brings it into the
     * index.
     */
    private static Map<String, String> withASerializableInterceptor() {
        var sources = withAVetoedPackage();
        sources.put("vi/Wrapping.java", """
                package vi;
                public abstract class Wrapping implements java.io.Serializable {
                }
                """);
        sources.compute("vi/Logger.java", (_, source) -> extendsWrapping(source, "Logger"));
        sources.compute("vi/quiet/QuietLogger.java", (_, source) -> extendsWrapping(source, "QuietLogger"));
        return sources;
    }

    /** {@code source} where the class {@code name} extends {@code vi.Wrapping}; fails when there is no such class. */
    private static String extendsWrapping(String source, String name) {
        assertNotNull(source, () -> "no source of " + name);
        var extended = source.replace("public class " + name + " {", "public class " + name + " extends vi.Wrapping {");
        assertNotEquals(source, extended, () -> "no class " + name + " to extend in:\n" + source);
        return extended;
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
