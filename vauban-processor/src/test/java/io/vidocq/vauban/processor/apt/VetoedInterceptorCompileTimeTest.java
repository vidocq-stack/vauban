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
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the build generates calls no vetoed interceptor (BUG-20261116-12). The {@code $$Intercepted} subclass the
 * processor writes for a bean with an interceptor binding hands each call to the interceptors the container resolves
 * at run time, so a vetoed interceptor, which the container does not know, is never in its chain. The processor lists
 * no vetoed class in {@code vauban-beans.list}, the classes the container of a compiled application indexes, and
 * writes no way to create one.
 */
@DisplayName("A vetoed interceptor is woven into nothing the build generates (BUG-20261116-12)")
class VetoedInterceptorCompileTimeTest {

    private static final String VETOED = "@jakarta.enterprise.inject.Vetoed";

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
        assertTrue(beans.contains("vi.Logger"), beans);
        assertFalse(beans.contains("vi.Vetoed"), beans);
        assertFalse(components(result).contains("vi.Vetoed"), "the processor creates no vetoed interceptor");
        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader());
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals("Logger(hello)", hello(container, loader.loadClass("vi.LoggedService")));
            assertEquals("hello", hello(container, loader.loadClass("vi.MutedService")),
                    "a bean only a vetoed interceptor would intercept");
        }
    }

    /**
     * The container skips the classes of a package whose {@code package-info} carries {@code @Vetoed}, when its index
     * holds that {@code package-info}. The processor indexes types only, and lists the classes of a vetoed package in
     * {@code vauban-beans.list} as beans, an interceptor among them (BUG-20261117-01).
     */
    @Test
    @Disabled("BUG-20261117-01: the annotation processor ignores a package's @Vetoed")
    @DisplayName("neither a bean nor an interceptor of a vetoed package is part of a compiled application")
    void aVetoedPackageHoldsNoBeanAndNoInterceptor() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        sources.put("vi/Logged.java", binding("Logged"));
        sources.put("vi/Logger.java", wrappingInterceptor("vi", "Logger", "Logged", "", 0));
        sources.put("vi/LoggedService.java", boundBean("LoggedService", "Logged"));
        sources.put("vi/quiet/package-info.java", VETOED + "\npackage vi.quiet;\n");
        sources.put("vi/quiet/QuietLogger.java", wrappingInterceptor("vi.quiet", "QuietLogger", "vi.Logged", "", 1));
        sources.put("vi/quiet/QuietBean.java", """
                package vi.quiet;
                @jakarta.enterprise.context.Dependent
                public class QuietBean {
                }
                """);

        var result = compile(sources);

        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader());
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertEquals(Set.of(), container.getBeanManager().getBeans(loader.loadClass("vi.quiet.QuietBean")),
                    "a bean class of the vetoed package");
            assertEquals("Logger(hello)", hello(container, loader.loadClass("vi.LoggedService")));
        }
    }

    // ---- helpers ----

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
