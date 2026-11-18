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
import io.vidocq.vauban.core.extensions.VaubanInvoker;
import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.invoke.Invoker;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invoker the build describes calls its method through a class the build generated, in the processor and when
 * {@code vauban:generate} freezes the extensions, and resolves no {@code java.lang.reflect.Method}; the invoker an
 * extension builds at start stays reflective. Each calls the same methods the same way (CDI 4.1, "Invokers"; the CDI
 * Lite TCK {@code invokers} tests): a method of a superclass, a package-private one, an interface default method, a
 * generic, a varargs and a static method, primitive arguments widened and a primitive return boxed, {@code void},
 * a checked exception thrown as it is, and looked-up arguments of a parameterized and a qualified type. A private
 * method has no invoker (BUG-20261118-37). A looked-up argument of an inner class of a generic class,
 * {@code Outer<String>.Inner<Integer>}, keeps its owner: an {@code Outer<Long>.Inner<Integer>} does not satisfy it
 * (BUG-20261118-49), and so does one of a plain inner class, {@code Outer<String>.Plain} (BUG-20261118-56). A
 * looked-up argument of a method the bean class inherits takes the qualifier the config of
 * the bean class gave its parameter, {@code @Named("x")} on {@code Base.pick(String)} through {@code Service}
 * (BUG-20261118-55); so does one of {@code far}, a protected method of a superclass of another package, which no
 * generated class can call: the frozen record keeps that parameter's annotations (review round 3).
 */
@DisplayName("BUG-20261118-37 - the invoker the build describes is generated, and calls no Method")
class GeneratedInvokerCompileTimeTest {

    private static final String SERVICE = "inv.Service";
    private static final String DISPOSED = "inv.Disposed";
    private static final String LOOKED_UP_TARGET = "usedAndDestroyed";
    private static final List<String> METHODS = List.of("inherited", "packaged", "greet", "identity", "joined",
            "twice", "sum", "nothing", "fail", "ite", "lookedUp", "owned", "plain", "pick", "far", "hidden");

    @TempDir
    Path workDir;

    /** Where the extension ran: what the invokers call through, and whether the start took a frozen record. */
    private enum Place {
        PROCESSOR(true, false), FROZEN(true, true), START(false, false);

        final boolean generated;
        final boolean frozen;

        Place(boolean generated, boolean frozen) {
            this.generated = generated;
            this.frozen = frozen;
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void forget() {
        InvokersBce.BUILT.clear();
        InvokersBce.REFUSED.clear();
    }

    /** The invokers of the methods {@link #METHODS} names, the arguments of {@code lookedUp} looked up. */
    public static class InvokersBce implements BuildCompatibleExtension {
        static final Map<String, InvokerInfo> BUILT = new ConcurrentHashMap<>();
        static final List<String> REFUSED = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            if (bean.isClassBean() && bean.declaringClass().name().equals(DISPOSED)) {
                bean.declaringClass().methods().stream().filter(method -> method.name().equals("use")).findFirst()
                        .ifPresent(use -> BUILT.put(LOOKED_UP_TARGET,
                                invokers.createInvoker(bean, use).withInstanceLookup().build()));
            }
            if (!bean.isClassBean() || !bean.declaringClass().name().equals(SERVICE)) {
                return;
            }
            for (var method : methodsOf(bean.declaringClass())) {
                if (!METHODS.contains(method.name())) {
                    continue;
                }
                try {
                    var builder = invokers.createInvoker(bean, method);
                    if (method.name().equals("lookedUp")) {
                        builder.withArgumentLookup(0).withArgumentLookup(1);
                    }
                    if (Set.of("owned", "plain", "pick", "far").contains(method.name())) {
                        builder.withArgumentLookup(0);
                    }
                    BUILT.put(method.name(), builder.build());
                } catch (DeploymentException _) {
                    REFUSED.add(method.name());
                }
            }
        }

        /**
         * The methods {@code type} declares, and those of its superclasses and superinterfaces: what
         * {@code ClassInfo.methods()} should return, and does not (BUG-20261117-42).
         */
        private static List<MethodInfo> methodsOf(ClassInfo type) {
            var methods = new java.util.ArrayList<MethodInfo>();
            var seen = new java.util.HashSet<String>();
            var pending = new java.util.ArrayDeque<ClassInfo>(List.of(type));
            while (!pending.isEmpty()) {
                var next = pending.pop();
                if (!seen.add(next.name())) {
                    continue;
                }
                next.methods().stream().filter(method -> method.declaringClass().name().equals(next.name()))
                        .forEach(methods::add);
                if (next.superClassDeclaration() != null) {
                    pending.push(next.superClassDeclaration());
                }
                pending.addAll(next.superInterfacesDeclarations());
            }
            return methods;
        }

        /**
         * Qualifies {@code @Named("x")}, through {@code Service}'s config, the parameter of {@code pick}, which
         * {@code Service} inherits from {@code Base}, and that of {@code far}, which it inherits from {@code Remote}.
         */
        @Enhancement(types = Object.class, withSubtypes = true)
        public void enhance(ClassConfig type) {
            if (!type.info().name().equals(SERVICE)) {
                return;
            }
            type.methods().stream().filter(method -> Set.of("pick", "far").contains(method.info().name()))
                    .forEach(method -> method
                    .parameters().getFirst().addAnnotation(jakarta.enterprise.inject.literal.NamedLiteral.of("x")));
        }

        @Synthesis
        public void synthesis(SyntheticComponents components) {
            components.addBean(InvokerHolder.class).type(InvokerHolder.class)
                    .withParam("names", BUILT.keySet().toArray(String[]::new))
                    .withParam("invokers", BUILT.values().toArray(InvokerInfo[]::new))
                    .createWith(InvokerHolderCreator.class);
        }
    }

    /** The invokers, by method name. */
    public record InvokerHolder(Map<String, Invoker<Object, Object>> invokers) {
    }

    /** Makes the holder of the invokers the extension recorded. */
    public static class InvokerHolderCreator implements SyntheticBeanCreator<InvokerHolder> {
        @Override
        @SuppressWarnings("unchecked")
        public InvokerHolder create(Instance<Object> lookup, Parameters params) {
            var names = params.get("names", String[].class);
            var invokers = params.get("invokers", Invoker[].class);
            var map = new LinkedHashMap<String, Invoker<Object, Object>>();
            for (int i = 0; i < names.length; i++) {
                map.put(names[i], invokers[i]);
            }
            return new InvokerHolder(map);
        }
    }

    @Test
    @DisplayName("the processor generates the invoker classes, and each invoker calls its method")
    void theProcessorGeneratesTheInvokerClasses() throws Exception {
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(InvokersBce.class));
        var result = ProcessorCompiler.compile(workDir, sources(), List.of(), processor);
        assertTrue(result.success(), () -> "compilation failed: " + result.messages());

        try (var classes = Files.list(result.classes().resolve("inv"))) {
            assertEquals(15, classes.filter(file -> file.getFileName().toString().contains("$$VaubanInvoker$"))
                    .count(), "the invoker classes, one per method but the private one");
        }
        assertInvokersCall(result, Place.PROCESSOR);
    }

    @Test
    @DisplayName("frozen by vauban:generate, the invokers call their method through generated classes")
    void theFrozenInvokersCallThroughGeneratedClasses() throws Exception {
        var result = compiledWithTheExtensionAtStart();
        try (var loader = loader(result)) {
            var outcome = VaubanContainer.builder().classLoader(loader).scanClasspath()
                    .freezeExtensions(result.classes());
            assertTrue(outcome.frozen(), outcome.reason());
        }
        assertInvokersCall(result, Place.FROZEN);
    }

    @Test
    @DisplayName("built at start, the invokers resolve their method and call it the same way")
    void theInvokersBuiltAtStartCallTheSameWay() throws Exception {
        assertInvokersCall(compiledWithTheExtensionAtStart(), Place.START);
    }

    /**
     * In a named module that exports and opens nothing but the package of {@code far}'s class, which no generated
     * class can call, the start makes the generated class through the lookup the
     * generated provider of its package grants, and calls each method through it.
     */
    @Test
    @DisplayName("in a module that opens nothing, the generated invokers call their method")
    void inAModuleThatOpensNothing() throws Exception {
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(InvokersBce.class));
        var result = ProcessorCompiler.compile(workDir, sources(), List.of(), processor);
        assertTrue(result.success(), () -> "compilation failed: " + result.messages());
        var descriptor = workDir.resolve("module-info.java");
        Files.writeString(descriptor, """
                module inv.app {
                    requires io.vidocq.vauban.api;
                    requires jakarta.cdi;
                    requires jakarta.inject;
                    // far: no generated class can call it, its invoker stays reflective
                    opens inv.other to io.vidocq.vauban.core;
                    provides io.vidocq.vauban.api.VaubanComponentProvider with inv._VaubanComponents;
                }
                """);
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var options = List.of("-proc:none", "--release", "25", "--module-path", ProcessorCompiler.classPath(),
                    "--patch-module", "inv.app=" + result.classes(), "-d", result.classes().toString());
            assertTrue(compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjects(descriptor)).call(), diagnostics.getDiagnostics().toString());
        }
        var configuration = ModuleLayer.boot().configuration().resolve(
                java.lang.module.ModuleFinder.of(result.classes()), java.lang.module.ModuleFinder.of(),
                Set.of("inv.app"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, getClass().getClassLoader());
        var loader = layer.findLoader("inv.app");
        try (var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            var invokers = container.select(InvokerHolder.class).invokers();
            var generated = ((VaubanInvoker) invokers.get("packaged")).generatedClass();
            assertTrue(generated.getModule().isNamed(), "the generated class is in the application's module");
            assertTrue(!generated.getModule().isOpen("inv") && !generated.getModule().isExported("inv"),
                    "the package of the generated class is neither exported nor opened");
            assertCalls(invokers, container.select(loader.loadClass(SERVICE)));
        }
    }

    private ProcessorCompiler.Result compiledWithTheExtensionAtStart() throws IOException {
        var result = ProcessorCompiler.compile(workDir, sources());
        assertTrue(result.success(), () -> "compilation failed: " + result.messages());
        var services = result.classes().resolve("META-INF/services/" + BuildCompatibleExtension.class.getName());
        Files.createDirectories(services.getParent());
        Files.writeString(services, InvokersBce.class.getName() + "\n");
        return result;
    }

    /** Starts a container on {@code result} and calls each invoker, as the extension built it at {@code place}. */
    private void assertInvokersCall(ProcessorCompiler.Result result, Place place) throws Exception {
        try (var loader = loader(result); var container = VaubanContainer.builder().classLoader(loader)
                .scanClasspath().build()) {
            assertEquals(place.frozen, container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            var invokers = container.select(InvokerHolder.class).invokers();
            var service = loader.loadClass(SERVICE).getConstructor().newInstance();
            var expected = new java.util.HashSet<>(METHODS.subList(0, METHODS.size() - 1));
            expected.add(LOOKED_UP_TARGET);
            assertEquals(expected, invokers.keySet(), "the invokers");
            assertTrue(InvokersBce.REFUSED.contains("hidden"), "the private method has no invoker");
            for (var invoker : invokers.entrySet()) {
                var vauban = (VaubanInvoker) invoker.getValue();
                if (place.generated && !invoker.getKey().equals("far")) {
                    assertNull(vauban.method(), "a generated invoker resolved its method");
                    assertNotNull(vauban.generatedClass(), "the generated class");
                } else {
                    assertNotNull(vauban.method(), "an invoker built at start resolves its method");
                }
            }
            assertCalls(invokers, service);
            assertTheDependentTargetIsDestroyed(invokers.get(LOOKED_UP_TARGET), loader);
        }
    }

    /**
     * An invoker whose instance is looked up creates a {@code @Dependent} target for the call and destroys it once the
     * call is over (CDI 4.1, "Configuring lookups").
     */
    @SuppressWarnings("unchecked")
    private static void assertTheDependentTargetIsDestroyed(Invoker<Object, Object> invoker, ClassLoader loader)
            throws Exception {
        var events = (List<String>) loader.loadClass(DISPOSED).getField("EVENTS").get(null);
        events.clear();
        assertEquals("used", invoker.invoke(null, new Object[0]));
        assertEquals(List.of("used", "destroyed"), events, "the target, used then destroyed");
    }

    private static void assertCalls(Map<String, Invoker<Object, Object>> invokers, Object service) {
        assertAll(
                () -> assertEquals("base v", invokers.get("inherited").invoke(service, new Object[] {"v"})),
                () -> assertEquals("packaged", invokers.get("packaged").invoke(service, null)),
                () -> assertEquals("hello w", invokers.get("greet").invoke(service, new Object[] {"w"})),
                () -> assertEquals(5, invokers.get("identity").invoke(service, new Object[] {5})),
                () -> assertEquals("a+b", invokers.get("joined").invoke(service,
                        new Object[] {new String[] {"a", "b"}})),
                () -> assertEquals(6, invokers.get("twice").invoke(service, new Object[] {3})),
                () -> assertEquals(6, invokers.get("twice").invoke(service, new Object[] {(short) 3}), "widened"),
                () -> {
                    var twice = invokers.get("twice");
                    var narrowed = new Object[] {3L};
                    assertThrows(RuntimeException.class, () -> twice.invoke(service, narrowed), "narrowed");
                },
                () -> assertEquals(3L, invokers.get("sum").invoke(null, new Object[] {1, 2.0f}), "static, widened"),
                () -> assertNull(invokers.get("nothing").invoke(service, new Object[0]), "void"),
                () -> {
                    var ite = invokers.get("ite");
                    var none = new Object[0];
                    var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                            () -> ite.invoke(service, none), "an InvocationTargetException the method throws");
                    assertEquals("user", thrown.getMessage(), "rethrown as it is, not unwrapped");
                },
                () -> {
                    var fail = invokers.get("fail");
                    var none = new Object[0];
                    assertEquals("checked", assertThrows(IOException.class, () -> fail.invoke(service, none))
                            .getMessage());
                },
                () -> assertEquals("held x", invokers.get("lookedUp").invoke(service, new Object[2]), "lookups"),
                () -> assertEquals("text", invokers.get("owned").invoke(service, new Object[1]),
                        "the looked-up Outer<String>.Inner<Integer>, not the Outer<Long> one (BUG-20261118-49)"),
                () -> assertEquals("plain text", invokers.get("plain").invoke(service, new Object[1]),
                        "the looked-up Outer<String>.Plain, not the Outer<Long> one (BUG-20261118-56)"),
                () -> assertEquals("x", invokers.get("pick").invoke(service, new Object[1]),
                        "the @Named(\"x\") String the config of Service gave the inherited parameter (BUG-20261118-55)"),
                () -> assertEquals("x", invokers.get("far").invoke(service, new Object[1]),
                        "the same, for an invoker no generated class can call (BUG-20261118-55, review round 3)"));
    }

    private URLClassLoader loader(ProcessorCompiler.Result result) throws IOException {
        return new URLClassLoader(new URL[] {result.classes().toUri().toURL()}, getClass().getClassLoader());
    }

    private static Map<String, String> sources() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("inv/other/Remote.java", """
                package inv.other;

                public class Remote {
                    protected String far(String value) {
                        return value;
                    }
                }
                """);
        sources.put("inv/Base.java", """
                package inv;

                public class Base<T> extends inv.other.Remote {
                    public String inherited(T value) {
                        return "base " + value;
                    }

                    String packaged() {
                        return "packaged";
                    }

                    public String pick(String value) {
                        return value;
                    }
                }
                """);
        sources.put("inv/Greeting.java", """
                package inv;

                public interface Greeting {
                    default String greet(String name) {
                        return "hello " + name;
                    }
                }
                """);
        sources.put("inv/Holder.java", """
                package inv;

                public record Holder<T>(T value) {
                }
                """);
        sources.put("inv/Outer.java", """
                package inv;

                public class Outer<O> {
                    public class Inner<I> {
                        public final String tag;

                        public Inner(String tag) {
                            this.tag = tag;
                        }
                    }

                    public class Plain {
                        public final String tag;

                        public Plain(String tag) {
                            this.tag = tag;
                        }
                    }
                }
                """);
        sources.put("inv/Producers.java", """
                package inv;

                import jakarta.enterprise.context.Dependent;
                import jakarta.enterprise.inject.Produces;
                import jakarta.inject.Named;

                @Dependent
                public class Producers {
                    @Produces
                    Holder<String> held() {
                        return new Holder<>("held");
                    }

                    @Produces
                    Holder<Integer> other() {
                        return new Holder<>(0);
                    }

                    @Produces
                    Outer<String>.Inner<Integer> text() {
                        return new Outer<String>().new Inner<Integer>("text");
                    }

                    @Produces
                    Outer<Long>.Inner<Integer> number() {
                        return new Outer<Long>().new Inner<Integer>("number");
                    }

                    @Produces
                    Outer<String>.Plain plainText() {
                        return new Outer<String>().new Plain("plain text");
                    }

                    @Produces
                    Outer<Long>.Plain plainNumber() {
                        return new Outer<Long>().new Plain("plain number");
                    }

                    @Produces
                    @Named("x")
                    String x() {
                        return "x";
                    }

                    @Produces
                    @Named("y")
                    String y() {
                        return "y";
                    }
                }
                """);
        sources.put("inv/Disposed.java", """
                package inv;

                import jakarta.annotation.PreDestroy;
                import jakarta.enterprise.context.Dependent;

                @Dependent
                public class Disposed {
                    public static final java.util.List<String> EVENTS = new java.util.ArrayList<>();

                    public String use() {
                        EVENTS.add("used");
                        return "used";
                    }

                    @PreDestroy
                    void destroy() {
                        EVENTS.add("destroyed");
                    }
                }
                """);
        sources.put("inv/Service.java", """
                package inv;

                import jakarta.enterprise.context.Dependent;
                import jakarta.inject.Named;

                @Dependent
                public class Service extends Base<String> implements Greeting {
                    private String hidden() {
                        return "hidden";
                    }

                    public <N extends Number> N identity(N value) {
                        return value;
                    }

                    public String joined(String... parts) {
                        return String.join("+", parts);
                    }

                    public int twice(int value) {
                        return 2 * value;
                    }

                    public static long sum(long a, double b) {
                        return a + (long) b;
                    }

                    public void nothing() {
                        // returns nothing
                    }

                    public String fail() throws java.io.IOException {
                        throw new java.io.IOException("checked");
                    }

                    public String ite() throws java.lang.reflect.InvocationTargetException {
                        throw new java.lang.reflect.InvocationTargetException(new RuntimeException("inner"), "user");
                    }

                    public String lookedUp(Holder<String> holder, @Named("x") String named) {
                        return holder.value() + " " + named;
                    }

                    public String owned(Outer<String>.Inner<Integer> inner) {
                        return inner.tag;
                    }

                    public String plain(Outer<String>.Plain plain) {
                        return plain.tag;
                    }
                }
                """);
        return sources;
    }
}
