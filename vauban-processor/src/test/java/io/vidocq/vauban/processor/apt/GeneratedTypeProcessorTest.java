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
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-50: a type another processor generates in the first round is an error type when Vauban's processor
 * first scans the classes that name it, and {@code VaubanProcessor.indexOnce} never scans them again once the type
 * exists. The scan reads a field, a parameter or a return type of that type as {@code java.lang.Object}
 * ({@code ElementScanner.typeMirrorToTypeInfo} has no case for an error type), and a supertype by its simple name. A
 * class the other processor generates is scanned in a later round, when the types it names exist: a generated bean, a
 * generated injection point and a MapStruct mapper's generated implementation are read right.
 */
@DisplayName("BUG-20261117-50 - in the processor, a type another processor generates in the first round")
class GeneratedTypeProcessorTest {

    private static final String NOT_READ = "BUG-20261117-50: the processor reads a type another processor generates in "
            + "round 1 as java.lang.Object";
    private static final String BAZ = "p.Baz_";
    private static final String BAZ_SOURCE = "package p;\npublic interface Baz_ {\n    String hello();\n}\n";
    /** A bean another processor generates, which injects {@code Baz_}: it is scanned when {@code Baz_} exists. */
    private static final String BAZ_USER = "p.BazUser_";
    private static final String BAZ_USER_SOURCE = """
            package p;
            @jakarta.enterprise.context.ApplicationScoped
            public class BazUser_ {
                @jakarta.inject.Inject
                Baz_ baz;
            }
            """;
    private static final String GARAGE = "p.Garage";
    private static final String IMMUTABLE_CAR = """
            package p;
            public final class ImmutableCar implements Car {
                private final String name;
                private ImmutableCar(String name) {
                    this.name = name;
                }
                public static ImmutableCar of(String name) {
                    return new ImmutableCar(name);
                }
                @Override
                public String name() {
                    return name;
                }
            }
            """;

    @TempDir
    Path directory;

    @BeforeEach
    void clear() {
        Recorder.SEEN.clear();
    }

    /** Records the bean {@code p.Foo}, every producer and every observer {@code @Registration} gives it. */
    public static class Recorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void beans(BeanInfo bean) {
            var declaring = bean.declaringClass();
            if (bean.isProducerMethod()) {
                SEEN.add("producer in " + declaring.name() + " types " + names(bean.types()));
            } else if ("p.Foo".equals(declaring.name())) {
                SEEN.add("p.Foo superClass " + name(declaring.superClass()) + ", superInterfaces "
                        + declaring.superInterfaces().stream().map(Recorder::name).toList() + ", types "
                        + names(bean.types()));
            }
        }

        @Registration(types = Object.class)
        public void observers(ObserverInfo observer) {
            SEEN.add("observer " + observer.declaringClass().name() + " of " + name(observer.eventType()));
        }

        private static List<String> names(java.util.Collection<Type> types) {
            return types.stream().map(Recorder::name).sorted().toList();
        }

        private static String name(Type type) {
            return type.isClass() ? type.asClass().declaration().name() : type.toString();
        }
    }

    /** Generates its sources in the first round, as MapStruct and Immutables do. */
    static final class Generator extends AbstractProcessor {

        private final Map<String, String> sources;
        private boolean generated;

        Generator(Map<String, String> sources) {
            this.sources = sources;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (!generated) {
                generated = true;
                sources.forEach(this::write);
            }
            return false;
        }

        private void write(String name, String source) {
            try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                writer.write(source);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * An injection point of a generated type is an injection point of {@code Object}, which every bean satisfies: the
     * build fails with "Ambiguous dependency: field User.baz of type ClassType[name=java.lang.Object]".
     */
    @Test
    @Disabled(NOT_READ)
    @DisplayName("an injection point of a generated type")
    void anInjectionPointOfAGeneratedType() throws Exception {
        var result = compile(Map.of("p/User.java", """
                package p;
                @jakarta.enterprise.context.ApplicationScoped
                public class User implements java.util.function.Supplier<String> {
                    @jakarta.inject.Inject
                    Baz_ baz;
                    public String get() {
                        return baz.hello();
                    }
                }
                """), Map.of(BAZ, BAZ_SOURCE, "p.BazBean_", """
                package p;
                @jakarta.enterprise.context.ApplicationScoped
                public class BazBean_ implements Baz_ {
                    public String hello() {
                        return "hi";
                    }
                }
                """));

        assertCompiles(result);
        assertEquals("hi", served(result, "p.User"));
    }

    /**
     * An observer of a generated type observes {@code Object}: the observer method the processor generates passes an
     * {@code Object} to it, and does not compile ("incompatible types: java.lang.Object cannot be converted to
     * p.Baz_").
     */
    @Test
    @Disabled(NOT_READ)
    @DisplayName("an observer of a generated type")
    void anObserverOfAGeneratedType() throws IOException {
        var result = compile(Map.of("p/Listener.java", """
                package p;
                @jakarta.enterprise.context.ApplicationScoped
                public class Listener {
                    void on(@jakarta.enterprise.event.Observes Baz_ event) {
                    }
                }
                """), Map.of(BAZ, BAZ_SOURCE));

        assertCompiles(result);
        assertEquals(List.of("observer p.Listener of " + BAZ), List.copyOf(Recorder.SEEN));
    }

    /**
     * Immutables generates {@code ImmutableCar} from a hand-written {@code Car}. Produced and injected by that name,
     * the injection point is one of {@code Object}, which the producer and every bean satisfy: the build fails with
     * "Ambiguous dependency: field Garage.car of type ClassType[name=java.lang.Object]". Produced by that name and
     * injected as {@code Car}, it works.
     */
    @Test
    @Disabled(NOT_READ)
    @DisplayName("Immutables: a value produced and injected by the name of its generated class")
    void anImmutablesValueProducedAndInjectedByItsGeneratedName() throws Exception {
        var result = compile(Map.of("p/Car.java", """
                package p;
                public interface Car {
                    String name();
                }
                """, "p/CarFactory.java", """
                package p;
                @jakarta.enterprise.context.ApplicationScoped
                public class CarFactory {
                    @jakarta.enterprise.inject.Produces
                    ImmutableCar car() {
                        return ImmutableCar.of("a");
                    }
                }
                """, "p/Garage.java", """
                package p;
                @jakarta.enterprise.context.ApplicationScoped
                public class Garage implements java.util.function.Supplier<String> {
                    @jakarta.inject.Inject
                    ImmutableCar car;
                    public String get() {
                        return car.name();
                    }
                }
                """), Map.of("p.ImmutableCar", IMMUTABLE_CAR));

        assertCompiles(result);
        assertEquals("a", served(result, GARAGE));
    }

    /**
     * A producer of a generated type builds and starts, and the bean another processor generates gets it injected; but
     * an extension reads the producer's types as {@code [java.lang.Object]}.
     */
    @Test
    @Disabled(NOT_READ)
    @DisplayName("an extension sees the generated type of a producer")
    void anExtensionSeesTheGeneratedTypeOfAProducer() throws IOException {
        var result = compile(Map.of("p/Factory.java", """
                package p;
                @jakarta.enterprise.context.ApplicationScoped
                public class Factory {
                    @jakarta.enterprise.inject.Produces
                    Baz_ baz() {
                        return () -> "hi";
                    }
                }
                """), Map.of(BAZ, BAZ_SOURCE, BAZ_USER, BAZ_USER_SOURCE));

        assertCompiles(result);
        assertEquals(List.of("producer in p.Factory types [java.lang.Object, " + BAZ + "]"),
                List.copyOf(Recorder.SEEN));
    }

    /**
     * A bean whose superclass and interface are generated builds and starts, and the bean another processor generates
     * gets it injected by that interface; but an extension reads them by their simple names, {@code Base_} and
     * {@code Baz_}, which name no class, and the bean's types with them. It read {@code java.lang.Object} before
     * BUG-20261117-31.
     */
    @Test
    @Disabled(NOT_READ)
    @DisplayName("an extension sees the generated supertypes of a bean")
    void anExtensionSeesTheGeneratedSupertypesOfABean() throws IOException {
        var result = compile(Map.of("p/Foo.java", """
                package p;
                @jakarta.enterprise.context.ApplicationScoped
                public class Foo extends Base_ implements Baz_ {
                    public String hello() {
                        return "hi";
                    }
                }
                """), Map.of(BAZ, BAZ_SOURCE, "p.Base_", "package p;\npublic abstract class Base_ {\n}\n", BAZ_USER,
                BAZ_USER_SOURCE));

        assertCompiles(result);
        assertEquals(List.of("p.Foo superClass p.Base_, superInterfaces [" + BAZ + "], types [java.lang.Object, "
                + "p.Base_, " + BAZ + ", p.Foo]"), List.copyOf(Recorder.SEEN));
    }

    private ProcessorCompiler.Result compile(Map<String, String> sources, Map<String, String> generated)
            throws IOException {
        var vauban = new VaubanProcessor();
        vauban.overrideBceClasses(List.of(Recorder.class));
        return ProcessorCompiler.compile(directory, sources, List.of(), List.of(new Generator(generated), vauban));
    }

    private static void assertCompiles(ProcessorCompiler.Result result) {
        assertTrue(result.success(), () -> "the application must compile: " + result.messages());
    }

    /** What the bean {@code beanClass}, a {@code Supplier}, gives once the container starts on the compiled classes. */
    private String served(ProcessorCompiler.Result result, String beanClass) throws Exception {
        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()}, getClass().getClassLoader());
                var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            return String.valueOf(((Supplier<?>) container.select(loader.loadClass(beanClass))).get());
        }
    }
}
