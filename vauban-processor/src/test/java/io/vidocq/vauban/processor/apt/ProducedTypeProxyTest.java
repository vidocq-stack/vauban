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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client proxy the processor generates for a normal-scoped producer is the proxy of the type the producer
 * produces, which the container looks up by that type at start. The processor reads the produced type off the
 * producer's bean types, a set in no order. They hold the classes and interfaces above that type, those the
 * compilation compiles too since BUG-20261117-03, and the processor took the first class, or the first public
 * interface, it met: the proxy of a supertype, under the key of that supertype, which the container does not look up
 * (BUG-20261117-15). Each producer here has seven classes, or seven interfaces, above its type, so a pick in the order
 * of the set misses at least one of the four in nearly every run.
 */
@DisplayName("A producer's client proxy is the proxy of the type it produces (BUG-20261117-15)")
class ProducedTypeProxyTest {

    /** The number of classes, or interfaces, above each produced type. */
    private static final int ABOVE = 7;

    private static final String JAVA = ".java";

    private static final Pattern KEY = Pattern.compile("\\(key ([\\w.$]+)\\)");

    private static ProcessorCompiler.Result result;

    @BeforeAll
    static void compile(@TempDir Path workDir) throws IOException {
        var sources = new LinkedHashMap<String, String>();
        for (var chain : List.of("A", "B")) {
            addClassChain(sources, chain);
        }
        for (var root : List.of("J", "K")) {
            addInterfaceHierarchy(sources, root);
        }
        addTypedInterfaces(sources);
        sources.put("app/Producers.java", """
                package app;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.enterprise.inject.Produces;
                @jakarta.enterprise.context.Dependent
                public class Producers {
                    @Produces @ApplicationScoped
                    app.c.A8 a() { return new app.c.A8(); }
                    @Produces @ApplicationScoped
                    app.c.B8 b() { return new app.c.B8(); }
                    @Produces @ApplicationScoped
                    app.i.J8 j() { return new app.i.J8() { }; }
                    @Produces @ApplicationScoped
                    app.i.K8 k() { return new app.i.K8() { }; }
                    @Produces @ApplicationScoped
                    @jakarta.enterprise.inject.Typed({app.t.T1.class, app.t.T2.class, app.t.T3.class, app.t.T4.class,
                            app.t.T5.class, app.t.T6.class, app.t.T7.class, app.t.T8.class})
                    app.t.Made t() { return new app.t.Made(); }
                    @Produces
                    String twin(jakarta.enterprise.inject.spi.BeanManager manager, String[] labels, int size) {
                        return "twin";
                    }
                    @Produces @ApplicationScoped @jakarta.inject.Named("twin")
                    @jakarta.enterprise.inject.Typed({app.t.T1.class, app.t.T2.class})
                    app.t.Made twin() { return new app.t.Made(); }
                    @Produces
                    String[] labels() { return new String[0]; }
                    @Produces
                    int size() { return 0; }
                }
                """);
        result = ProcessorCompiler.compile(workDir, sources);
    }

    /** {@code app.c.<chain>1} to {@code <chain>8}, each class extending the one before it. */
    private static void addClassChain(Map<String, String> sources, String chain) {
        sources.put("app/c/" + chain + 1 + JAVA, """
                package app.c;
                public class %s1 {
                    public String name() {
                        return "%s";
                    }
                }
                """.formatted(chain, chain));
        for (int level = 2; level <= ABOVE + 1; level++) {
            sources.put("app/c/" + chain + level + JAVA,
                    "package app.c;\npublic class " + chain + level + " extends " + chain + (level - 1) + " {\n}\n");
        }
    }

    /** {@code app.i.<root>8}, an interface that extends {@code <root>1} to {@code <root>7}. */
    private static void addInterfaceHierarchy(Map<String, String> sources, String root) {
        var above = new ArrayList<String>();
        for (int level = 1; level <= ABOVE; level++) {
            above.add(root + level);
            sources.put("app/i/" + root + level + JAVA, "package app.i;\npublic interface " + root + level
                    + " {\n    default int level() {\n        return " + level + ";\n    }\n}\n");
        }
        var produced = root + (ABOVE + 1);
        sources.put("app/i/" + produced + JAVA, "package app.i;\npublic interface " + produced + " extends "
                + String.join(", ", above) + " {\n    @Override\n    default int level() {\n        return 0;\n"
                + "    }\n}\n");
    }

    /**
     * {@code app.t.T1} to {@code T8}, unrelated interfaces, and {@code app.t.Made}, which implements them in the order
     * {@code T8} to {@code T1}: the order in which the container meets them above {@code Made}.
     */
    private static void addTypedInterfaces(Map<String, String> sources) {
        var implemented = new ArrayList<String>();
        for (int level = ABOVE + 1; level >= 1; level--) {
            implemented.add("T" + level);
            sources.put("app/t/T" + level + JAVA, "package app.t;\npublic interface T" + level
                    + " {\n    default int level() {\n        return " + level + ";\n    }\n}\n");
        }
        sources.put("app/t/Made" + JAVA, "package app.t;\npublic class Made implements " + String.join(", ", implemented)
                + " {\n    @Override\n    public int level() {\n        return 0;\n    }\n}\n");
    }

    /** The keys of the producer proxies the processor generated, as its notes give them. */
    private static Set<String> proxyKeys() {
        var keys = new TreeSet<String>();
        for (var message : result.messages()) {
            if (message.contains("Generated build-time producer proxy")
                    || message.contains("Generated build-time interface proxy")) {
                var key = KEY.matcher(message);
                if (key.find()) {
                    keys.add(key.group(1));
                }
            }
        }
        return keys;
    }

    @Test
    @DisplayName("each producer gets the proxy of its own type, and no class or interface above it gets one")
    void eachProducerGetsTheProxyOfTheTypeItProduces() {
        assertTrue(result.success(), () -> String.join("\n", result.messages()));

        assertEquals(Set.of("app.c.A8_ClientProxy", "app.c.B8_ClientProxy", "app.i.J8_ClientProxy",
                "app.i.K8_ClientProxy", "app.t.T2_ClientProxy", "app.t.T8_ClientProxy"), proxyKeys(),
                () -> String.join("\n", result.messages()));
    }

    /**
     * A producer whose {@code @Typed} names interfaces none of which extends all the others: the container proxies the
     * first of them it meets above the produced class, {@code T8}, which {@code Made} implements first
     * ({@code InterceptorBeanWrapper.resolveProxyTargetClass}). The processor generates that proxy, and the container
     * uses it. The processor took the first of the producer's bean types, a set in no order: any of the eight
     * (BUG-20261117-15). The same for one of two overloaded producer methods, {@code twin()}, found by its parameters
     * after the other one, and {@code @Typed({T1.class, T2.class})}: the proxy of {@code T2}.
     */
    @Test
    @DisplayName("a producer typed with unrelated interfaces gets the proxy the container looks up")
    void aProducerTypedWithUnrelatedInterfacesGetsTheProxyTheContainerLooksUp() throws Exception {
        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        assertTrue(proxyKeys().containsAll(Set.of("app.t.T2_ClientProxy", "app.t.T8_ClientProxy")),
                () -> String.join("\n", result.messages()));

        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader()); var container = VaubanContainer.builder().classLoader(loader)
                .scanClasspath().build()) {
            var manager = container.getBeanManager();
            var t8 = loader.loadClass("app.t.T8");
            var t2 = loader.loadClass("app.t.T2");

            assertAll(() -> assertBuildTimeProxy(manager, manager.resolve(manager.getBeans(t8)), t8),
                    () -> assertBuildTimeProxy(manager, manager.resolve(manager.getBeans("twin")), t2));
        }
    }

    private static void assertBuildTimeProxy(jakarta.enterprise.inject.spi.BeanManager manager,
            jakarta.enterprise.inject.spi.Bean<?> bean, Class<?> type) {
        var reference = manager.getReference(bean, type, manager.createCreationalContext(bean));

        assertFalse(java.lang.reflect.Proxy.isProxyClass(reference.getClass()),
                () -> "the container fell back to a java.lang.reflect.Proxy: no build-time proxy of " + type.getName());
        assertTrue(type.isInstance(reference), reference.getClass()::getName);
    }
}
