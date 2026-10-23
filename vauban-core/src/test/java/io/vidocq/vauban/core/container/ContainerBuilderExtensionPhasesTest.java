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
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the container builder runs the phases of build compatible extensions and reads its build-time resources: the
 * causes of failing extensions, classes an extension adds, a custom context without a public constructor, and
 * class loaders whose resources cannot be listed or hold malformed lines.
 */
@DisplayName("VaubanContainerBuilder: extension phases and build-time resources")
class ContainerBuilderExtensionPhasesTest {

    private static final String RUNTIME_LIST = "META-INF/vauban-bce-runtime.list";

    @Dependent
    public static class Plain {
        public String id() {
            return "plain";
        }
    }

    /** Fails while enhancing {@link Plain}. */
    public static class FirstFailingExtension implements BuildCompatibleExtension {
        @Enhancement(types = Plain.class)
        public void enhance(ClassConfig plain) {
            throw new IllegalStateException("first problem");
        }
    }

    /** Fails while enhancing {@link Plain} too. */
    public static class SecondFailingExtension implements BuildCompatibleExtension {
        @Enhancement(types = Plain.class)
        public void enhance(ClassConfig plain) {
            throw new IllegalArgumentException("second problem");
        }
    }

    /** Only deployed because {@link AddingExtension} adds it. */
    @Dependent
    public static class Extra {
        public String id() {
            return "extra";
        }
    }

    /** Adds a class that does not exist, one the deployment already has, and one it does not. */
    public static class AddingExtension implements BuildCompatibleExtension {
        @Discovery
        public void discover(ScannedClasses scanned) {
            scanned.add("io.vidocq.vauban.core.container.NoSuchScannedClass");
            scanned.add(Plain.class.getName());
            scanned.add(Extra.class.getName());
        }
    }

    @Scope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Cached {
    }

    /** A context with no public constructor: the container creates it through its private lookup. */
    public static class CachedContext implements AlterableContext {
        private final Map<Contextual<?>, Object> instances = new ConcurrentHashMap<>();

        CachedContext() {
            // not public on purpose
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return Cached.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) instances.computeIfAbsent(contextual, c -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) instances.get(contextual);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            instances.remove(contextual);
        }
    }

    public static class CachedScopeExtension implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(Cached.class, CachedContext.class);
        }
    }

    @Cached
    public static class CachedBean {
        public String id() {
            return "cached";
        }
    }

    @Test
    @DisplayName("extensions failing a phase: the first exception is the cause, the others are suppressed")
    void deploymentErrorCauses() {
        var builder = VaubanContainer.builder()
                .addBeanClass(Plain.class)
                .addBeanClass(FirstFailingExtension.class)
                .addBeanClass(SecondFailingExtension.class);

        var thrown = assertThrows(DeploymentException.class, builder::build);

        assertNotNull(thrown.getCause());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals(java.util.Set.of("first problem", "second problem"),
                java.util.Set.of(thrown.getCause().getMessage(), thrown.getSuppressed()[0].getMessage()));
    }

    @Test
    @DisplayName("a class an extension adds is deployed, one that does not exist is skipped, one already deployed is kept")
    void extensionAddedClasses() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(Plain.class)
                .addBeanClass(AddingExtension.class)
                .build()) {

            assertEquals("plain", container.select(Plain.class).id());
            assertEquals("extra", container.select(Extra.class).id());
        }
    }

    @Test
    @DisplayName("a custom context without a public constructor is still installed")
    void contextWithoutPublicConstructor() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(CachedScopeExtension.class)
                .addBeanClass(CachedBean.class)
                .build()) {

            var first = container.select(CachedBean.class);

            assertEquals("cached", first.id());
            assertSame(first, container.select(CachedBean.class), "the custom context holds a single instance");
        }
    }

    /** A loader that cannot list the frozen enhancement patches, and serves the replay list from a file. */
    private static final class ResourceLoader extends ClassLoader {
        private final URL runtimeList;

        ResourceLoader(URL runtimeList) {
            super(ContainerBuilderExtensionPhasesTest.class.getClassLoader());
            this.runtimeList = runtimeList;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (runtimeList != null && name.equals(RUNTIME_LIST)) {
                return Collections.enumeration(java.util.List.of(runtimeList));
            }
            if (name.equals(io.vidocq.vauban.core.extensions.EnhancementPatchSerializer.PATCH_PATH)) {
                throw new IOException("resources of " + name + " cannot be listed");
            }
            return super.getResources(name);
        }
    }

    @Test
    @DisplayName("a class loader that cannot list the enhancement patches still boots the deployment")
    void unlistableResources() {
        try (var container = VaubanContainer.builder()
                .classLoader(new ResourceLoader(null))
                .addBeanClass(Plain.class)
                .build()) {

            assertNotNull(container.select(Plain.class));
        }
    }

    @Test
    @DisplayName("comments, blank, malformed and unloadable lines of the replay list are skipped")
    void malformedReplayList(@TempDir Path dir) throws IOException {
        var list = dir.resolve("vauban-bce-runtime.list");
        Files.writeString(list, String.join("\n",
                "# a comment",
                "",
                ";" + Plain.class.getName(),
                FirstFailingExtension.class.getName() + ";",
                "no separator at all",
                "com.example.MissingExtension;com.example.MissingTarget"));

        try (var container = VaubanContainer.builder()
                .classLoader(new ResourceLoader(list.toUri().toURL()))
                .addBeanClass(Plain.class)
                .build()) {

            assertTrue(container.isRunning());
            assertEquals("plain", container.select(Plain.class).id());
        }
    }
}
