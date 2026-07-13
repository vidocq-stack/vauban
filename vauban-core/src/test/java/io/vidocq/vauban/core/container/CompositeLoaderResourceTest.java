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
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When bean classes come from several class loaders (an application archive
 * loader plus the runtime loader), the composite discovery loader Vauban
 * installs as TCCL for the whole build must expose the RESOURCES of every
 * source loader — including via {@code getResources(String)} (plural), which
 * only consults {@code findResources}/parent, never
 * {@code getResourceAsStream}. MP Config's properties source reads
 * {@code META-INF/microprofile-config.properties} through exactly that call
 * during BCE deployment validation; before the fix it silently saw only the
 * parent loader's files.
 */
@DisplayName("Composite discovery loader exposes resources of every bean-class loader")
class CompositeLoaderResourceTest {

    static final String PROBE_RESOURCE = "vauban-composite-probe.properties";

    /** The bean class re-defined in a foreign loader. */
    @Dependent
    public static class ForeignBean {
    }

    /** BCE from the application loader capturing what the build-time TCCL exposes. */
    public static class TcclProbeBce implements BuildCompatibleExtension {
        static volatile List<URL> seenPlural;
        static volatile URL seenSingle;

        @Validation
        public void capture(jakarta.enterprise.inject.build.compatible.spi.Messages messages) throws IOException {
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            seenPlural = java.util.Collections.list(tccl.getResources(PROBE_RESOURCE));
            seenSingle = tccl.getResource(PROBE_RESOURCE);
        }
    }

    /**
     * Child-first loader for exactly one class, additionally serving
     * {@link #PROBE_RESOURCE} from a materialized temp directory — the same
     * shape as an exploded test deployment.
     */
    static final class ForeignLoader extends URLClassLoader {
        private final String childFirstClass;

        ForeignLoader(String childFirstClass, URL resourceRoot, ClassLoader parent) {
            super("foreign-deployment", new URL[] {resourceRoot}, parent);
            this.childFirstClass = childFirstClass;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(childFirstClass)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = in.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    /**
     * The embedded-deployment case: every bean class resolves through the
     * application loader (archive classes are also on the test classpath, so
     * the parent-first deployment loader never defines them), but the caller
     * installed the deployment loader as TCCL before building. The build-time
     * discovery loader must keep exposing that TCCL's resources — otherwise
     * the deployment's microprofile-config.properties silently disappears
     * during BCE validation.
     */
    @Test
    @DisplayName("resources of the entry TCCL stay visible even when no bean class comes from it")
    void entryTcclResourcesStayVisible() throws Exception {
        Path root = Files.createTempDirectory("vauban-composite-tccl-");
        Files.writeString(root.resolve(PROBE_RESOURCE), "probe=2\n");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var deploymentLoader = new URLClassLoader(
                "parent-first-deployment", new URL[] {root.toUri().toURL()}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(deploymentLoader);

            TcclProbeBce.seenPlural = null;
            try (VaubanContainer container = VaubanContainer.builder()
                    .addBeanClass(TcclProbeBce.class)
                    .addBeanClass(ForeignBean.class)
                    .build()) {

                assertNotNull(TcclProbeBce.seenPlural, "the @Validation probe must have run");
                assertTrue(TcclProbeBce.seenPlural.stream()
                                .anyMatch(url -> url.toString().contains("vauban-composite-tccl-")),
                        "the entry TCCL's resources must stay visible during the build, got: "
                                + TcclProbeBce.seenPlural);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("getResources on the build-time TCCL sees the foreign loader's files")
    void buildTimeTcclExposesForeignResources() throws Exception {
        Path root = Files.createTempDirectory("vauban-composite-probe-");
        Files.writeString(root.resolve(PROBE_RESOURCE), "probe=1\n");

        try (var foreign = new ForeignLoader(
                ForeignBean.class.getName(), root.toUri().toURL(), getClass().getClassLoader())) {
            Class<?> foreignBean = foreign.loadClass(ForeignBean.class.getName());

            TcclProbeBce.seenPlural = null;
            TcclProbeBce.seenSingle = null;
            try (VaubanContainer container = VaubanContainer.builder()
                    .addBeanClass(TcclProbeBce.class)
                    .addBeanClass(foreignBean)
                    .build()) {

                assertNotNull(TcclProbeBce.seenPlural, "the @Validation probe must have run");
                assertTrue(TcclProbeBce.seenPlural.stream()
                                .anyMatch(url -> url.toString().contains("vauban-composite-probe-")),
                        "getResources(plural) must surface the foreign loader's resource, got: "
                                + TcclProbeBce.seenPlural);
                assertNotNull(TcclProbeBce.seenSingle,
                        "getResource(singular) must surface the foreign loader's resource");
            }
        }
    }
}
