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
package io.vidocq.vauban.core.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A context a build-compatible extension registered at build time (MetaAnnotations.addContext)
 * reaches the container through META-INF/vauban-contexts.list: an application compiled with the
 * processor skips the extensions at boot, and used to lose every such context.
 */
@DisplayName("Frozen contexts: META-INF/vauban-contexts.list")
class FrozenContextsTest {

    @TempDir
    Path archive;

    /** Active, so that BeanManager.getContext returns it. */
    public static class ActiveSessionContext implements AlterableContext {
        @Override public Class<? extends Annotation> getScope() { return SessionScoped.class; }
        @Override public <T> T get(Contextual<T> contextual, CreationalContext<T> cc) { return null; }
        @Override public <T> T get(Contextual<T> contextual) { return null; }
        @Override public boolean isActive() { return true; }
        @Override public void destroy(Contextual<?> contextual) { }
    }

    @Test
    @DisplayName("every list on the class path is read, comments and duplicates skipped")
    void readsTheLists() throws IOException {
        writeList("# frozen by the processor\n"
                + "jakarta.enterprise.context.SessionScoped;" + ActiveSessionContext.class.getName() + ";true\n"
                + "jakarta.enterprise.context.SessionScoped;" + ActiveSessionContext.class.getName() + ";true\n");

        try (var loader = loader()) {
            assertEquals(List.of(new FrozenContexts.Entry("jakarta.enterprise.context.SessionScoped",
                            ActiveSessionContext.class.getName(), true)),
                    FrozenContexts.read(loader));
        }
    }

    @Test
    @DisplayName("the container installs a frozen context with no extension to run")
    void theContainerInstallsAFrozenContext() throws IOException {
        writeList("jakarta.enterprise.context.SessionScoped;" + ActiveSessionContext.class.getName() + ";true\n");

        try (var loader = loader();
             var container = VaubanContainer.builder().classLoader(loader).build()) {
            assertInstanceOf(ActiveSessionContext.class, container.getBeanManager().getContext(SessionScoped.class));
        }
    }

    private void writeList(String content) throws IOException {
        var file = archive.resolve(FrozenContexts.PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private URLClassLoader loader() throws IOException {
        return new URLClassLoader(new URL[] {archive.toUri().toURL()}, FrozenContextsTest.class.getClassLoader());
    }
}
