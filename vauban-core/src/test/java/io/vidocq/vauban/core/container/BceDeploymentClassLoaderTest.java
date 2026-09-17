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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vauban#98 — a deployment spans several class loaders, and the build-compatible
 * extension phase must see all of them.
 *
 * <p>{@code mvn vidocq:dev} is the shape that breaks: the application's own classes are
 * defined by the layer's loader (passed as {@code -Dvidocq.app.path=target/classes})
 * while its libraries stay on the JVM module path. Handing the BCE phase the loader of
 * the FIRST bean class means the extension can read every bean's metadata from the index
 * but can only load half of them, so {@code InvokerFactory.createInvoker} throws
 * {@code ClassNotFoundException} for the other half and CDI 4.1 invokers silently fall
 * back to reflection.
 *
 * <p>The deployment is rebuilt here from two {@link URLClassLoader}s over temporary class
 * output — one "library", one "application" — that cannot see each other's classes.
 */
@DisplayName("BCE phase — deployment-wide class resolution (vauban#98)")
class BceDeploymentClassLoaderTest {

    @Dependent
    public static class LibraryBean {
        public String ping() {
            return "library";
        }
    }

    @Dependent
    public static class ApplicationBean {
        public String ping() {
            return "application";
        }
    }

    /**
     * Stands in for {@code langchain4j-cdi-mcp-invoker-cdi41}: it asks the container for an
     * invoker on every bean it is registered for, and records what came back.
     */
    public static class InvokerProbeBce implements BuildCompatibleExtension {

        static final List<String> built = Collections.synchronizedList(new ArrayList<>());
        static final Map<String, String> failures = Collections.synchronizedMap(new LinkedHashMap<>());

        @Registration(types = Object.class)
        public void onBean(BeanInfo bean, InvokerFactory invokers) {
            var name = bean.declaringClass().name();
            if (!name.equals(LibraryBean.class.getName()) && !name.equals(ApplicationBean.class.getName())) {
                return;
            }
            var ping = bean.declaringClass().methods().stream()
                    .filter(m -> "ping".equals(m.name()))
                    .findFirst()
                    .orElse(null);
            if (ping == null) {
                failures.put(name, "no ping() method in the index");
                return;
            }
            try {
                invokers.createInvoker(bean, ping);
                built.add(name);
            } catch (RuntimeException e) {
                failures.put(name, e + (e.getCause() != null ? " / " + e.getCause() : ""));
            }
        }
    }

    /**
     * Delegates everything to the test's own loader EXCEPT the deployment classes, so each
     * of the two deployment loaders below really defines its own and really cannot see the
     * other's — the test classpath would otherwise hand both to everyone.
     */
    static final class HidingLoader extends ClassLoader {

        private final Set<String> hiddenClasses;
        private final Set<String> hiddenResources;

        HidingLoader(ClassLoader delegate, Set<String> hiddenClasses) {
            super("hiding", delegate);
            this.hiddenClasses = hiddenClasses;
            this.hiddenResources = hiddenClasses.stream()
                    .map(HidingLoader::resourceName)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        static String resourceName(String className) {
            return className.replace('.', '/') + ".class";
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hiddenClasses.contains(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return hiddenResources.contains(name) ? null : super.getResource(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return hiddenResources.contains(name) ? null : super.getResourceAsStream(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return hiddenResources.contains(name) ? Collections.emptyEnumeration() : super.getResources(name);
        }
    }

    /** Copies a compiled class into {@code root}, the way an exploded archive carries it. */
    private static void materialize(Class<?> clazz, Path root) throws IOException {
        var resource = HidingLoader.resourceName(clazz.getName());
        var target = root.resolve(resource);
        Files.createDirectories(target.getParent());
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("cannot read the compiled form of " + clazz.getName());
            Files.write(target, in.readAllBytes());
        }
    }

    /** A loader that defines the given classes itself, and nothing else of the deployment. */
    private URLClassLoader isolatedLoader(String name, ClassLoader hiding, Class<?>... classes) throws IOException {
        Path root = Files.createTempDirectory("vauban-98-" + name + "-");
        for (var clazz : classes) {
            materialize(clazz, root);
        }
        return new URLClassLoader(name, new URL[] {root.toUri().toURL()}, hiding);
    }

    private HidingLoader hidingParent() {
        return new HidingLoader(
                getClass().getClassLoader(),
                Set.of(LibraryBean.class.getName(), ApplicationBean.class.getName()));
    }

    @BeforeEach
    void reset() {
        InvokerProbeBce.built.clear();
        InvokerProbeBce.failures.clear();
    }

    @Test
    @DisplayName("an invoker is built for a bean of every loader of the deployment, not only the first one's")
    void invokersAreBuiltAcrossEveryLoaderOfTheDeployment() throws Exception {
        var hiding = hidingParent();

        try (var libraryLoader = isolatedLoader("library", hiding, LibraryBean.class);
                var applicationLoader = isolatedLoader("application", hiding, ApplicationBean.class)) {

            Class<?> libraryBean = libraryLoader.loadClass(LibraryBean.class.getName());
            Class<?> applicationBean = applicationLoader.loadClass(ApplicationBean.class.getName());

            // The two sides really are separate: this is the deployment vidocq:dev produces.
            assertEquals(libraryLoader, libraryBean.getClassLoader());
            assertEquals(applicationLoader, applicationBean.getClassLoader());

            try (VaubanContainer container = VaubanContainer.builder()
                    .addBeanClass(libraryBean) // first in the list — its loader was the one handed to the BCE phase
                    .addBeanClass(applicationBean)
                    .addBeanClass(InvokerProbeBce.class)
                    .build()) {

                assertTrue(InvokerProbeBce.failures.isEmpty(),
                        "no invoker may fail to build: " + InvokerProbeBce.failures);
                assertEquals(List.of(LibraryBean.class.getName(), ApplicationBean.class.getName()),
                        InvokerProbeBce.built.stream().sorted(
                                        java.util.Comparator.comparing(n -> n.contains("$Library") ? 0 : 1))
                                .toList(),
                        "both beans must get an invoker");
            }
        }
    }

    @Test
    @DisplayName("a class the container already holds wins over a loader that would also find it")
    void knownClassWinsOverALoaderThatWouldAlsoFindIt() throws Exception {
        var hiding = hidingParent();
        try (var one = isolatedLoader("one", hiding, LibraryBean.class);
                var other = isolatedLoader("other", hiding, LibraryBean.class)) {

            Class<?> held = one.loadClass(LibraryBean.class.getName());
            Class<?> otherCopy = other.loadClass(LibraryBean.class.getName());
            assertNotSame(held, otherCopy, "the two loaders must really define two distinct classes");

            var resolution = DeploymentClassLoader.forDeployment(List.of(held), null, other);

            assertSame(held, resolution.loadClass(LibraryBean.class.getName()),
                    "the container's own Class must win over the loader that would also answer");
        }
    }

    @Test
    @DisplayName("without a known class, the configured loader comes before the context loader")
    void configuredLoaderComesBeforeTheContextLoader() throws Exception {
        var hiding = hidingParent();
        try (var configured = isolatedLoader("configured", hiding, LibraryBean.class);
                var context = isolatedLoader("context", hiding, LibraryBean.class)) {

            Class<?> fromConfigured = configured.loadClass(LibraryBean.class.getName());
            Class<?> fromContext = context.loadClass(LibraryBean.class.getName());
            assertNotSame(fromConfigured, fromContext);

            assertSame(fromConfigured,
                    DeploymentClassLoader.forDeployment(List.of(), configured, context)
                            .loadClass(LibraryBean.class.getName()),
                    "the loader set on the builder is consulted first");
            assertSame(fromContext,
                    DeploymentClassLoader.forDeployment(List.of(), null, context)
                            .loadClass(LibraryBean.class.getName()),
                    "with no configured loader, the build's context loader answers");
        }
    }

    @Test
    @DisplayName("a bean class's own loader stays the last resort, and an unknown class is still not found")
    void beanClassLoaderIsTheLastResort() throws Exception {
        var hiding = hidingParent();
        try (var application = isolatedLoader("last-resort", hiding, LibraryBean.class, ApplicationBean.class)) {

            Class<?> held = application.loadClass(LibraryBean.class.getName());
            // Neither known nor reachable from the context loader: only the bean class's own
            // loader can answer — the historical behaviour, kept as the final fallback.
            var resolution = DeploymentClassLoader.forDeployment(List.of(held), null, hiding);

            Class<?> viaLastResort = resolution.loadClass(ApplicationBean.class.getName());
            assertSame(application, viaLastResort.getClassLoader(),
                    "the loader of a bean class must still answer what nothing else can");

            assertThrows(ClassNotFoundException.class,
                    () -> resolution.loadClass("io.vidocq.vauban.core.container.NoSuchDeploymentClass"),
                    "a class no part of the deployment carries is still not found");
        }
    }

    @Test
    @DisplayName("a deployment with no loader at all still resolves the platform classes")
    void aDeploymentWithoutAnyLoaderStillResolvesPlatformClasses() throws Exception {
        var resolution = DeploymentClassLoader.forDeployment(List.of(), null, null);

        assertSame(String.class, resolution.loadClass("java.lang.String"),
                "with no delegate, the parent system loader must still answer");
        assertThrows(ClassNotFoundException.class,
                () -> resolution.loadClass("io.vidocq.vauban.core.container.NoSuchDeploymentClass"));
    }
}
