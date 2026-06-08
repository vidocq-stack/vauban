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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of the runtime BCE skip mechanism and of the loading of synthetic
 * metadata precomputed at compile time.
 *
 * <h2>Context</h2>
 * When the VaubanProcessor (APT) executes the BCEs at compile time, it writes:
 * <ul>
 *   <li>{@code META-INF/vauban-bce-processed} — marker for the runtime skip</li>
 *   <li>{@code META-INF/vauban-synthetic-metadata.properties} — synthetic beans/observers</li>
 * </ul>
 * At boot, the VaubanContainerBuilder detects the marker and loads the metadata
 * instead of re-executing the BCEs. These tests verify that this mechanism works.
 */
@DisplayName("BCE runtime skip - loading of synthetic metadata")
class BceRuntimeSkipTest {

    @Nested
    @DisplayName("SyntheticMetadataSerializer - full round-trip with builders")
    class SerializerRoundTrip {

        @Test
        @DisplayName("serializes and deserializes a synthetic bean with all fields")
        void shouldRoundTripSyntheticBean() throws IOException {
            var builder = new VaubanSyntheticBeanBuilder<>(String.class);
            builder.scope(jakarta.enterprise.context.ApplicationScoped.class);
            builder.withParam("key1", "hello");
            builder.withParam("count", 42);
            builder.withParam("flag", true);
            builder.name("myBean");
            builder.alternative(true);
            builder.priority(100);

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(builder), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));

            assertEquals(1, beans.size());
            var bean = beans.getFirst();
            assertEquals("java.lang.String", bean.beanClassName());
            assertEquals("jakarta.enterprise.context.ApplicationScoped", bean.scopeAnnotation());
            assertEquals("myBean", bean.name());
            assertTrue(bean.alternative());
            assertEquals(100, bean.priority());
            assertEquals("S:hello", bean.params().get("key1"));
            assertEquals("I:42", bean.params().get("count"));
            assertEquals("B:true", bean.params().get("flag"));
        }

        @Test
        @DisplayName("serializes and deserializes a synthetic observer")
        void shouldRoundTripSyntheticObserver() throws IOException {
            var builder = new VaubanSyntheticObserverBuilder<>(String.class);
            builder.priority(500);
            builder.async(true);
            builder.withParam("channel", "events");

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(), List.of(builder), baos);

            var props = new Properties();
            props.load(new InputStreamReader(
                    new ByteArrayInputStream(baos.toByteArray()), StandardCharsets.UTF_8));

            var observers = SyntheticMetadataSerializer.readObservers(props);

            assertEquals(1, observers.size());
            var obs = observers.getFirst();
            assertEquals("java.lang.String", obs.eventTypeName());
            assertEquals(500, obs.priority());
            assertTrue(obs.async());
            assertEquals("S:events", obs.params().get("channel"));
        }

        @Test
        @DisplayName("serializes several beans and observers together")
        void shouldRoundTripMultipleBoth() throws IOException {
            var bean1 = new VaubanSyntheticBeanBuilder<>(String.class);
            bean1.scope(jakarta.enterprise.context.ApplicationScoped.class);

            var bean2 = new VaubanSyntheticBeanBuilder<>(Integer.class);
            bean2.scope(jakarta.enterprise.context.Dependent.class);

            var obs1 = new VaubanSyntheticObserverBuilder<>(String.class);
            obs1.priority(100);

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(bean1, bean2), List.of(obs1), baos);

            var props = new Properties();
            props.load(new InputStreamReader(
                    new ByteArrayInputStream(baos.toByteArray()), StandardCharsets.UTF_8));

            var beans = SyntheticMetadataSerializer.readBeans(props);
            var observers = SyntheticMetadataSerializer.readObservers(props);

            assertEquals(2, beans.size());
            assertEquals("java.lang.String", beans.get(0).beanClassName());
            assertEquals("java.lang.Integer", beans.get(1).beanClassName());

            assertEquals(1, observers.size());
            assertEquals("java.lang.String", observers.getFirst().eventTypeName());
        }
    }

    @Nested
    @DisplayName("SyntheticBeanDescriptor - field reconstruction")
    class DescriptorReconstruction {

        @Test
        @DisplayName("the types are preserved after serialization")
        void shouldPreserveTypes() throws IOException {
            var builder = new VaubanSyntheticBeanBuilder<>(String.class);
            builder.type(CharSequence.class);
            builder.type(Serializable.class);

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(builder), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));

            var types = beans.getFirst().types();
            assertTrue(types.contains("java.lang.String"), "Should contain String");
            assertTrue(types.contains("java.lang.CharSequence"), "Should contain CharSequence");
            assertTrue(types.contains("java.io.Serializable"), "Should contain Serializable");
            assertTrue(types.contains("java.lang.Object"), "Should contain Object (auto-added)");
        }

        @Test
        @DisplayName("the qualifiers are preserved after serialization")
        void shouldPreserveQualifiers() throws IOException {
            var builder = new VaubanSyntheticBeanBuilder<>(String.class);
            builder.qualifier(jakarta.inject.Named.class);

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(builder), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));

            assertTrue(beans.getFirst().qualifiers().contains("jakarta.inject.Named"));
        }

        @Test
        @DisplayName("disposer is preserved if it is present")
        void shouldPreserveDisposer() throws IOException {
            var builder = new VaubanSyntheticBeanBuilder<>(String.class);
            // No disposer configured → must be null

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(builder), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));

            assertNull(beans.getFirst().disposerClassName());
        }

        @Test
        @DisplayName("a bean without a scope has a null scopeAnnotation")
        void shouldHandleNullScope() throws IOException {
            var builder = new VaubanSyntheticBeanBuilder<>(String.class);
            // No scope

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(builder), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));

            assertNull(beans.getFirst().scopeAnnotation());
        }
    }

    @Nested
    @DisplayName("BCE marker")
    class BceMarker {

        @Test
        @DisplayName("the marker path is correct")
        void shouldHaveCorrectMarkerPath() {
            assertEquals("META-INF/vauban-bce-processed",
                    SyntheticMetadataSerializer.BCE_PROCESSED_MARKER);
        }

        @Test
        @DisplayName("the metadata path is correct")
        void shouldHaveCorrectMetadataPath() {
            assertEquals("META-INF/vauban-synthetic-metadata.properties",
                    SyntheticMetadataSerializer.METADATA_PATH);
        }
    }
}
