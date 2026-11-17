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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Serializable;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the build generates calls no vetoed interceptor (BUG-20261116-12). The {@code $$Intercepted} subclass the
 * processor writes for a bean with an interceptor binding hands each call to the interceptors the container resolves
 * at run time, so a vetoed interceptor, which the container does not know, is never in its chain. The processor lists
 * no vetoed class in {@code vauban-beans.list}, the classes the container of a compiled application indexes, and
 * writes no way to create one. A class of a vetoed package is vetoed too (BUG-20261117-01). An extension the processor
 * runs gets from {@code @Registration} the interceptors and the observers the compilation compiles, as the container
 * gives them at start and frozen (BUG-20261117-02, BUG-20261117-05), and from {@code @Enhancement} the subtypes of
 * its types (BUG-20261117-07).
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
            SEEN.add("interceptor " + interceptor.declaringClass().name());
        }

        @Registration(types = Traced.class)
        public void bindings(InterceptorInfo interceptor) {
            SEEN.add("binding " + interceptor.declaringClass().name());
        }

        @Registration(types = Serializable.class)
        public void serializables(BeanInfo bean) {
            SEEN.add("serializable " + bean.declaringClass().name());
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
        var expected = List.of("interceptor " + LOGGER, "interceptor vi.Tracer", "serializable " + LOGGER);

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
     * loading it, which a class the compilation compiles does not on the processor, so the processor's bean types stop
     * there: an extension it runs does not see {@code vi.SerialService} as a {@code Serializable}, which the container
     * sees at start (BUG-20261117-03).
     */
    @Test
    @Disabled("BUG-20261117-03: the processor's bean types stop at a superclass the compilation compiles")
    @DisplayName("an extension run by the processor sees the types a bean has through a superclass it compiles")
    void anExtensionRunByTheProcessorSeesTheTypesABeanHasThroughASuperclassItCompiles() throws Exception {
        var sources = withASerializableInterceptor();
        sources.put("vi/SerialService.java", """
                package vi;
                @jakarta.enterprise.context.Dependent
                public class SerialService extends Wrapping {
                }
                """);
        var serialService = "serializable vi.SerialService";

        var seen = seenThreeWays(sources, InterceptorRecorder.class, InterceptorRecorder.SEEN);

        assertTrue(seen.start().contains(serialService), () -> "at start: " + seen.start());
        assertTrue(seen.processor().contains(serialService), () -> "in the processor: " + seen.processor());
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

    /**
     * Compiles {@code sources} twice: once with a processor that runs {@code extension}, and once without, for the
     * container to run it at start, and for the build to freeze it, after which it runs no more at start. Each time,
     * {@code seen} is what the extension recorded.
     */
    private SeenThreeWays seenThreeWays(Map<String, String> sources, Class<? extends BuildCompatibleExtension> extension,
                                        List<String> seen) throws Exception {
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
