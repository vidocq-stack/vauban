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
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.PassivationCapable;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
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
 * for the injection points the build resolves, as at start (BUG-20261117-03).
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
    /** {@code vi.types.Outer<O>}, whose inner class {@code Inner<I>} is a {@code Holder<O>}. */
    private static final String GENERIC_OUTER_SOURCE = """
            public class Outer<O> {
                public class Inner<I> implements Holder<O> {
                }
            }""";

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
     * {@code @Registration} sees a class as {@code @Enhancement} left it: the container gives it the {@code @Deprecated}
     * an extension added, once, though it calls the {@code @Enhancement} method twice (BUG-20261117-08), at start and
     * frozen. The processor gives it none (BUG-20261117-10).
     */
    @Test
    @Disabled("BUG-20261117-10: in the processor, @Registration does not see the annotations @Enhancement added")
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
    @Disabled("BUG-20261117-08: the container calls an @Enhancement method twice for each type, at start and frozen")
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
     * has them too; it differs in one place: the type of a generic bean class itself, named raw in the descriptor,
     * {@code Ranked}, and with its type variables by {@code Bean.getTypes()}.
     */
    @Test
    @DisplayName("the processor's bean types equal the container's for every shape of type argument")
    void theProcessorsBeanTypesEqualTheContainersForEveryShapeOfTypeArgument() throws Exception {
        var ranked = "vi.types.Ranked: java.lang.Object, vi.types.Holder<T extends java.lang.Number &"
                + " java.lang.Comparable<T>>, ";
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

        assertAll(() -> seen.assertEachIs(sortedConcat(factory, unchanged, List.of(ranked + "vi.types.Ranked"))),
                () -> container.assertEachIs(sortedConcat(factory, unchanged, List.of(ranked
                        + "vi.types.Ranked<T extends java.lang.Number & java.lang.Comparable<T>>"))));
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

        assertAll(() -> seen.assertEachIs(sortedConcat(factory, List.of(
                        "vi.types.Eyrie: java.lang.Object, vi.types.Eyrie, vi.types.Perch<P>",
                        "vi.types.RawEyrie: java.lang.Object, vi.types.Holder, vi.types.Perch, vi.types.RawEyrie"))),
                () -> container.assertEachIs(sortedConcat(factory, List.of(
                        "vi.types.Eyrie: java.lang.Object, vi.types.Eyrie<P>, vi.types.Perch<P>",
                        "vi.types.RawEyrie: java.lang.Object, vi.types.Holder, vi.types.Perch,"
                                + " vi.types.RawEyrie<P>"))));
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

        assertAll(() -> seen.assertEachIs(sortedConcat(others, RAW_SUPERTYPE_BEANS,
                        List.of("vi.types.Legacy: " + ABOVE_ARRAY_LIST + "vi.types.Legacy"))),
                () -> container.assertEachIs(sortedConcat(others, RAW_SUPERTYPE_BEANS,
                        List.of("vi.types.Legacy: " + ABOVE_ARRAY_LIST + "vi.types.Legacy<E>"))));
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
     * {@code @Typed} producer names its types raw, {@code Repo} for both, and the injection point is ambiguous
     * (BUG-20261117-19).
     */
    @Test
    @Disabled("BUG-20261117-19: the descriptor of a @Typed producer names its types raw")
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

    /** {@link #withAVetoedPackage()}, with {@code vi.types.Box<T> implements Repo<T>} and its producer, of {@code Box<String>}. */
    private static Map<String, String> withABoxProducer() {
        var sources = withAVetoedPackage();
        putType(sources, "Repo", "public interface Repo<T> {\n}");
        putType(sources, "Box", "public class Box<T> implements Repo<T> {\n}");
        putType(sources, "BoxFactory", DEPENDENT + "public class BoxFactory {\n    @jakarta.enterprise.inject.Produces\n"
                + "    Box<String> box() {\n        return new Box<>();\n    }\n}");
        return sources;
    }

    /** Adds {@code ArrayShelf<S> implements Holder<S[]>} and the bean {@code ArrayLibrary extends ArrayShelf<String>}. */
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
    private static String variableName(java.lang.reflect.TypeVariable<?> variable, Set<java.lang.reflect.Type> visiting) {
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
                Map.entry("Wild", "public interface Wild extends Pair<java.util.List<? extends Number>,"
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
            if (type.isParameterizedType()) {
                var parameterized = type.asParameterizedType();
                return parameterized.genericClass().declaration().name() + parameterized.typeArguments().stream()
                        .map(BeanTypeRecorder::nameOf).collect(Collectors.joining(", ", "<", ">"));
            }
            if (type.isWildcardType()) {
                var wildcard = type.asWildcardType();
                if (wildcard.upperBound() != null) {
                    return "? extends " + nameOf(wildcard.upperBound());
                }
                return wildcard.lowerBound() != null ? "? super " + nameOf(wildcard.lowerBound()) : "?";
            }
            if (type.isTypeVariable()) {
                var variable = type.asTypeVariable();
                var bounds = variable.bounds().stream().map(BeanTypeRecorder::nameOf)
                        .collect(Collectors.joining(" & "));
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

        private static String described(BeanInfo bean) {
            if (bean.isClassBean() || bean.isInterceptor()) {
                return bean.declaringClass().name();
            }
            return "producer of " + bean.types().stream().map(ObserverRecorder::nameOf)
                    .filter(name -> !name.equals(Object.class.getName())).sorted().collect(Collectors.joining(", "));
        }
    }

    /**
     * CDI 4.1 calls a {@code @Registration} method for each bean with a bean type assignable to one of its types, and a
     * primitive type is its wrapper there (§2.2.1): a producer of {@code int} is an {@code Integer} and a
     * {@code Serializable}, a producer of {@code String[]} a {@code Serializable}. The processor, the container at
     * start and the build that freezes the extensions match a bean type by loading the class it names, and a primitive
     * or an array type names none: they call neither method for either producer (BUG-20261117-11).
     */
    @Test
    @Disabled("BUG-20261117-11: a BeanInfo of a primitive or an array bean type matches no @Registration type")
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

        seen.assertEachIs(List.of("integer producer of int", "serializable producer of int",
                "serializable producer of java.lang.String[]", SERIALIZABLE + LOGGER));
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
