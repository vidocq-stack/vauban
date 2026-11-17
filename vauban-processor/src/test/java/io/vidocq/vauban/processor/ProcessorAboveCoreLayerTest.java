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
package io.vidocq.vauban.processor;

import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.processor.apt.ProcessorCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The processor in a module layer above the one that holds vauban-core, as javac's {@code --processor-module-path}
 * puts it in a JVM whose module path holds vauban-core: javac defines the processor's module in a layer of its own,
 * a child of the boot layer, and that module reads vauban-core from the boot layer. A qualified export reaches only
 * the modules its layer, or a layer below it, holds when the layer is defined, so {@code exports
 * io.vidocq.vauban.core.codegen to io.vidocq.vauban.processor} did not reach this one, and the first class of
 * {@code io.vidocq.vauban.core.codegen} or {@code io.vidocq.vauban.core.proxy} the processor used threw
 * {@link IllegalAccessError} (BUG-20261117-23): {@code CompiledSupertypes} for every compilation, the client proxy
 * classes for a normal-scoped bean, {@code BeanMembers} for an intercepted method that carries an annotation. Foy's
 * tests run javac so.
 */
@DisplayName("The processor in a layer above vauban-core's (BUG-20261117-23)")
class ProcessorAboveCoreLayerTest {

    private static final String PROCESSOR_MODULE = "io.vidocq.vauban.processor";

    @Test
    @DisplayName("compiles a bean of the dependent scope")
    void compilesABeanOfTheDependentScope(@TempDir Path dir) throws IOException {
        var result = compile(dir, Map.of("app/Greeter.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Greeter {
                    public String greet() {
                        return "hello";
                    }
                }
                """));

        assertGenerated(result, "_VaubanComponents");
    }

    @Test
    @DisplayName("generates the client proxy of a normal-scoped bean")
    void generatesTheClientProxyOfANormalScopedBean(@TempDir Path dir) throws IOException {
        var result = compile(dir, Map.of("app/Shop.java", """
                package app;
                @jakarta.enterprise.context.ApplicationScoped
                public class Shop {
                    public String name() {
                        return "shop";
                    }
                }
                """));

        assertGenerated(result, "Shop_ClientProxy");
    }

    @Test
    @DisplayName("generates the subclass of an intercepted bean")
    void generatesTheSubclassOfAnInterceptedBean(@TempDir Path dir) throws IOException {
        var result = compile(dir, Map.of("app/Logged.java", """
                package app;
                @jakarta.interceptor.InterceptorBinding
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.METHOD})
                public @interface Logged {
                }
                """, "app/Logger.java", """
                package app;
                @Logged
                @jakarta.interceptor.Interceptor
                @jakarta.annotation.Priority(1)
                public class Logger {
                    @jakarta.interceptor.AroundInvoke
                    Object log(jakarta.interceptor.InvocationContext context) throws Exception {
                        return context.proceed();
                    }
                }
                """, "app/Ledger.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Ledger {
                    @Logged
                    public int total() {
                        return 1;
                    }
                }
                """));

        assertGenerated(result, "Ledger$$Intercepted");
    }

    /** Compiles {@code sources} with the processor of a layer above vauban-core's, and asserts it succeeded. */
    private static ProcessorCompiler.Result compile(Path dir, Map<String, String> sources) throws IOException {
        var processor = processorAboveCore();
        var result = ProcessorCompiler.compile(dir, sources, List.of(), processor);
        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        return result;
    }

    /**
     * A Vauban processor whose module javac would define: in a layer of its own, a child of the boot layer, which holds
     * vauban-core. The test's own processor module, with the tests patched in, is in the boot layer, beside
     * vauban-core: a qualified export reaches it.
     */
    private static Processor processorAboveCore() {
        assertTrue(BeanDiscovery.class.getModule().isNamed(), "vauban-core is a module of the test's module path");
        var boot = ModuleLayer.boot();
        var configuration = boot.configuration().resolveAndBind(ModuleFinder.of(processorClasses()), ModuleFinder.of(),
                Set.of(PROCESSOR_MODULE));
        var layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        // javac's own module uses the service; the test's module, which looks the processor up here, does not
        ProcessorAboveCoreLayerTest.class.getModule().addUses(Processor.class);
        var processor = ServiceLoader.load(layer, Processor.class).stream()
                .filter(provider -> provider.type().getModule().getLayer() == layer)
                .map(ServiceLoader.Provider::get).findFirst().orElseThrow();
        assertNotSame(BeanDiscovery.class.getModule().getLayer(), processor.getClass().getModule().getLayer());
        return processor;
    }

    /** The processor's own classes, the module {@code vauban-processor} builds. */
    private static Path processorClasses() {
        try {
            return Path.of(VaubanProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Asserts that the compilation wrote a class or a source whose name contains {@code name}. */
    private static void assertGenerated(ProcessorCompiler.Result result, String name) {
        try (var classes = Files.walk(result.classes()); var generated = Files.walk(result.generated())) {
            var written = Stream.concat(classes, generated).map(path -> path.getFileName().toString()).toList();
            assertTrue(written.stream().anyMatch(file -> file.contains(name)), () -> name + " not in " + written);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
