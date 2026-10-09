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

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The params a build-time extension gave its synthetic bean and observer reach them when the container
 * boots from the metadata the processor wrote (BUG-20261008-02): the creator and the observer get the
 * values, not {@code null}.
 */
@DisplayName("BCE at build time - synthetic params reach the creator and the observer at boot")
class BceRuntimeParamsTest {

    @TempDir
    Path archive;

    enum Mode { FAST, SAFE }

    /** What the creator read from its params. */
    public record Report(Class<?>[] classes, String[] names, int[] counts, Mode mode, Mode[] modes,
                         Class<?> single, Named named) {
    }

    public static final class ReportCreator implements SyntheticBeanCreator<Report> {
        @Override
        public Report create(Instance<Object> lookup, Parameters params) {
            return new Report(params.get("classes", Class[].class), params.get("names", String[].class),
                    params.get("counts", int[].class), params.get("mode", Mode.class),
                    params.get("modes", Mode[].class), params.get("single", Class.class),
                    params.get("named", Named.class));
        }
    }

    public record Probe() {
    }

    static final AtomicReference<Class<?>[]> OBSERVED = new AtomicReference<>();

    public static final class ProbeObserver implements SyntheticObserver<Probe> {
        @Override
        public void observe(EventContext<Probe> event, Parameters params) {
            OBSERVED.set(params.get("classes", Class[].class));
        }
    }

    /** Fires a {@link Probe} once the container is up. */
    @jakarta.enterprise.context.ApplicationScoped
    public static class Firer {
        @jakarta.inject.Inject
        jakarta.enterprise.event.Event<Probe> probes;

        void fire() {
            probes.fire(new Probe());
        }
    }

    @Test
    @DisplayName("arrays, enums, classes and annotations written at build time are read back at boot")
    void paramsReachTheCreatorAndTheObserver() throws Exception {
        var bean = new VaubanSyntheticBeanBuilder<>(Report.class);
        bean.type(Report.class).createWith(ReportCreator.class)
                .withParam("classes", new Class<?>[] {String.class, Integer.class})
                .withParam("names", new String[] {"a,b", "c:d"})
                .withParam("counts", new int[] {1, 2, 3})
                .withParam("mode", Mode.SAFE)
                .withParam("modes", new Mode[] {Mode.SAFE, Mode.FAST})
                .withParam("single", Probe.class)
                .withParam("named", NamedLiteral.of("tagged"));
        var observer = new VaubanSyntheticObserverBuilder<>(Probe.class);
        observer.observeWith(ProbeObserver.class).withParam("classes", new Class<?>[] {Long.class});

        var metaInf = Files.createDirectories(archive.resolve("META-INF"));
        try (var out = Files.newOutputStream(archive.resolve(SyntheticMetadataSerializer.METADATA_PATH))) {
            SyntheticMetadataSerializer.write(List.of(bean), List.of(observer), out);
        }
        Files.writeString(archive.resolve(SyntheticMetadataSerializer.BCE_PROCESSED_MARKER), "# test\n");
        Files.writeString(metaInf.resolve("vauban-beans.list"), "");
        OBSERVED.set(null);

        try (var loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, getClass().getClassLoader());
             var container = VaubanContainer.builder().classLoader(loader).scanClasspath()
                     .addBeanClass(Firer.class).build()) {
            var report = container.select(Report.class);
            assertArrayEquals(new Class<?>[] {String.class, Integer.class}, report.classes());
            assertArrayEquals(new String[] {"a,b", "c:d"}, report.names());
            assertArrayEquals(new int[] {1, 2, 3}, report.counts());
            assertSame(Mode.SAFE, report.mode());
            assertArrayEquals(new Mode[] {Mode.SAFE, Mode.FAST}, report.modes());
            assertEquals(Probe.class, report.single());
            assertEquals("tagged", report.named().value());

            container.select(Firer.class).fire();
            assertArrayEquals(new Class<?>[] {Long.class}, OBSERVED.get(), "the synthetic observer's params");
        }
    }
}
