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

import io.vidocq.vauban.core.container.listed.ListedBean;
import io.vidocq.vauban.core.container.listed._VaubanComponents;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261119-72: the provider of a package that a {@code vauban-beans.list} names is used, also when no
 * {@code provides} clause or services file registers it (the test compilation of a module whose main sources declare
 * no bean). BUG-20261119-73: {@code addBeanClass} of a class without a bean defining annotation that declares an
 * observer says that the observer is ignored.
 */
@DisplayName("Providers found next to a beans list, and classes added without a bean defining annotation")
class ListedComponentProviderTest {

    @TempDir
    Path archive;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private java.util.logging.StreamHandler handler;
    private java.util.logging.Logger logger;

    /** No bean defining annotation, an observer: added with addBeanClass, it is no bean. */
    public static class PlainObserver {
        void onStartup(@Observes Startup event) {
            event.toString();
        }
    }

    @BeforeEach
    void capture() {
        // The tests run inside io.vidocq.vauban.core, which does not read java.logging (LifecycleObserverFailureTest).
        getClass().getModule().addReads(ModuleLayer.boot().findModule("java.logging").orElseThrow());
        handler = new java.util.logging.StreamHandler(out, new java.util.logging.SimpleFormatter());
        handler.setLevel(java.util.logging.Level.WARNING);
        logger = java.util.logging.Logger.getLogger(VaubanContainerBuilder.class.getName());
        logger.addHandler(handler);
    }

    @AfterEach
    void release() {
        logger.removeHandler(handler);
    }

    private String logged() {
        handler.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a bean a beans list names is built by its package's provider, which nothing else registers")
    void theProviderOfAListedPackageBuildsItsBean() throws IOException {
        Files.createDirectories(archive.resolve("META-INF"));
        Files.writeString(archive.resolve("META-INF/vauban-beans.list"), ListedBean.class.getName() + "\n");
        _VaubanComponents.CREATED.set(0);

        try (var loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, getClass().getClassLoader());
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            assertNotNull(container.select(ListedBean.class));
        }

        assertEquals(1, _VaubanComponents.CREATED.get(), "the listed package's provider built the bean");
    }

    @Test
    @DisplayName("addBeanClass of a class without a bean defining annotation that observes says it is ignored")
    void anObserverWithoutABeanDefiningAnnotationIsReported() {
        try (var container = VaubanContainer.builder().addBeanClass(PlainObserver.class).build()) {
            assertNotNull(container);
        }

        String logged = logged();
        assertTrue(logged.contains(PlainObserver.class.getName()) && logged.contains("no bean defining annotation"),
                logged);
    }
}
