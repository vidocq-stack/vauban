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

import io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic beans and observers: those a build step recorded in the synthetic metadata resource (with a disposer, a
 * scope and qualifiers, some of which the deployment cannot load), and an observer an extension adds for a
 * parameterized event type.
 */
@DisplayName("SyntheticComponentRegistrar: recorded synthetic components and parameterized synthetic observers")
class SyntheticComponentRegistrationTest {

    static final List<String> OBSERVED = new CopyOnWriteArrayList<>();
    static final Map<String, Set<Annotation>> QUALIFIERS = new ConcurrentHashMap<>();

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    public @interface Metric {
    }

    private static final class MetricLiteral extends AnnotationLiteral<Metric> implements Metric {
        private static final Metric INSTANCE = new MetricLiteral();
    }

    public static class Gauge {
        public String id() {
            return "gauge";
        }
    }

    public static class GaugeCreator implements SyntheticBeanCreator<Gauge> {
        @Override
        public Gauge create(Instance<Object> lookup, Parameters params) {
            return new Gauge();
        }
    }

    /** Cannot be instantiated by the container: disposing of a gauge fails, and the failure is suppressed. */
    public static class GaugeDisposer implements SyntheticBeanDisposer<Gauge> {
        private GaugeDisposer() {
        }

        @Override
        public void dispose(Gauge instance, Instance<Object> lookup, Parameters params) {
            OBSERVED.add("disposed");
        }
    }

    public static class TickObserver implements SyntheticObserver<String> {
        @Override
        public void observe(EventContext<String> event, Parameters params) {
            OBSERVED.add("tick:" + event.getEvent());
            QUALIFIERS.put("tick", event.getMetadata().getQualifiers());
        }
    }

    public static class ListObserver implements SyntheticObserver<Object> {
        @Override
        public void observe(EventContext<Object> event, Parameters params) {
            OBSERVED.add("list:" + event.getEvent());
        }
    }

    public static class GenericObservers implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components, Types types) {
            var listOfStrings = types.parameterized(List.class, String.class);
            components.addObserver(listOfStrings).observeWith(ListObserver.class);
        }
    }

    /** Serves the processed marker and the synthetic metadata resource from files. */
    private static final class MetadataLoader extends ClassLoader {
        private final URL marker;
        private final URL metadata;

        MetadataLoader(URL marker, URL metadata) {
            super(SyntheticComponentRegistrationTest.class.getClassLoader());
            this.marker = marker;
            this.metadata = metadata;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.equals(SyntheticMetadataSerializer.BCE_PROCESSED_MARKER)) {
                return Collections.enumeration(List.of(marker));
            }
            if (name.equals(SyntheticMetadataSerializer.METADATA_PATH)) {
                return Collections.enumeration(List.of(metadata));
            }
            return super.getResources(name);
        }
    }

    @BeforeEach
    void reset() {
        OBSERVED.clear();
        QUALIFIERS.clear();
    }

    @Test
    @DisplayName("recorded synthetic beans and observers are registered with their disposer, scope and qualifiers")
    void recordedSyntheticComponents(@TempDir Path dir) throws IOException {
        var marker = Files.write(dir.resolve("vauban-bce-processed"), new byte[0]);
        var metadata = Files.writeString(dir.resolve("vauban-synthetic-metadata.properties"), String.join("\n",
                "bean.count=1",
                "bean.0.beanClass=" + Gauge.class.getName(),
                "bean.0.creator=" + GaugeCreator.class.getName(),
                "bean.0.disposer=" + GaugeDisposer.class.getName(),
                "bean.0.scope=jakarta.inject.Singleton",
                "bean.0.types=" + Gauge.class.getName(),
                "bean.0.qualifiers=" + Metric.class.getName() + ",com.example.MissingQualifier",
                "observer.count=1",
                "observer.0.eventType=java.lang.String",
                "observer.0.observer=" + TickObserver.class.getName(),
                "observer.0.qualifiers=" + Metric.class.getName() + ",com.example.MissingQualifier"));
        var loader = new MetadataLoader(marker.toUri().toURL(), metadata.toUri().toURL());

        try (var container = VaubanContainer.builder().classLoader(loader).scanClasspath().build()) {
            var beanManager = container.getBeanManager();
            var gauge = beanManager.createInstance().select(Gauge.class, MetricLiteral.INSTANCE).get();
            beanManager.getEvent().select(String.class, MetricLiteral.INSTANCE).fire("now");

            assertEquals("gauge", gauge.id());
            assertEquals(List.of("tick:now"), OBSERVED);
            assertTrue(QUALIFIERS.get("tick").contains(MetricLiteral.INSTANCE), QUALIFIERS::toString);
        }
        assertEquals(List.of("tick:now"), OBSERVED, "the disposer cannot be created, and that failure is suppressed");
    }

    @Test
    @DisplayName("an observer an extension adds for a parameterized event type receives the events of that type")
    void parameterizedSyntheticObserver() {
        try (var container = VaubanContainer.builder().addBeanClass(GenericObservers.class).build()) {
            container.getBeanManager().getEvent().select(new TypeLiteral<List<String>>() { }).fire(List.of("a"));

            assertTrue(OBSERVED.contains("list:[a]"), OBSERVED::toString);
        }
    }
}
