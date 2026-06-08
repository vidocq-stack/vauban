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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SyntheticMetadataSerializer - serialization of synthetic beans/observers")
class SyntheticMetadataSerializerTest {

    @Nested
    @DisplayName("encodeParam / decodeParam - round-trip")
    class ParamEncoding {

        @Test
        @DisplayName("String round-trip")
        void shouldEncodeDecodeString() {
            var encoded = SyntheticMetadataSerializer.encodeParam("hello");
            assertEquals("S:hello", encoded);
            assertEquals("hello", SyntheticMetadataSerializer.decodeParam(encoded));
        }

        @Test
        @DisplayName("boolean round-trip")
        void shouldEncodeDecodeBoolean() {
            assertEquals("B:true", SyntheticMetadataSerializer.encodeParam(true));
            assertEquals(true, SyntheticMetadataSerializer.decodeParam("B:true"));
            assertEquals(false, SyntheticMetadataSerializer.decodeParam("B:false"));
        }

        @Test
        @DisplayName("int round-trip")
        void shouldEncodeDecodeInt() {
            assertEquals("I:42", SyntheticMetadataSerializer.encodeParam(42));
            assertEquals(42, SyntheticMetadataSerializer.decodeParam("I:42"));
        }

        @Test
        @DisplayName("long round-trip")
        void shouldEncodeDecodeLong() {
            assertEquals("L:123456789", SyntheticMetadataSerializer.encodeParam(123456789L));
            assertEquals(123456789L, SyntheticMetadataSerializer.decodeParam("L:123456789"));
        }

        @Test
        @DisplayName("double round-trip")
        void shouldEncodeDecodeDouble() {
            assertEquals("D:3.14", SyntheticMetadataSerializer.encodeParam(3.14));
            assertEquals(3.14, SyntheticMetadataSerializer.decodeParam("D:3.14"));
        }

        @Test
        @DisplayName("Class encode returns the FQCN")
        void shouldEncodeClass() {
            assertEquals("C:java.lang.String", SyntheticMetadataSerializer.encodeParam(String.class));
        }

        @Test
        @DisplayName("null returns null")
        void shouldReturnNullForNull() {
            assertNull(SyntheticMetadataSerializer.encodeParam(null));
            assertNull(SyntheticMetadataSerializer.decodeParam(null));
        }
    }

    @Nested
    @DisplayName("readBeans - deserialization")
    class ReadBeans {

        @Test
        @DisplayName("reads a synthetic bean from Properties")
        void shouldReadSingleBean() {
            var props = new Properties();
            props.setProperty("bean.count", "1");
            props.setProperty("bean.0.beanClass", "com.example.MyBean");
            props.setProperty("bean.0.creator", "com.example.MyCreator");
            props.setProperty("bean.0.scope", "jakarta.enterprise.context.ApplicationScoped");
            props.setProperty("bean.0.types", "com.example.MyBean,java.lang.Object");
            props.setProperty("bean.0.alternative", "false");
            props.setProperty("bean.0.priority", "0");

            var beans = SyntheticMetadataSerializer.readBeans(props);

            assertEquals(1, beans.size());
            var bean = beans.getFirst();
            assertEquals("com.example.MyBean", bean.beanClassName());
            assertEquals("com.example.MyCreator", bean.creatorClassName());
            assertEquals("jakarta.enterprise.context.ApplicationScoped", bean.scopeAnnotation());
            assertEquals(List.of("com.example.MyBean", "java.lang.Object"), bean.types());
            assertFalse(bean.alternative());
        }

        @Test
        @DisplayName("reads the params of a synthetic bean")
        void shouldReadBeanParams() {
            var props = new Properties();
            props.setProperty("bean.count", "1");
            props.setProperty("bean.0.beanClass", "com.example.MyBean");
            props.setProperty("bean.0.alternative", "false");
            props.setProperty("bean.0.priority", "0");
            props.setProperty("bean.0.types", "");
            props.setProperty("bean.0.param.name", "S:test");
            props.setProperty("bean.0.param.count", "I:5");

            var beans = SyntheticMetadataSerializer.readBeans(props);
            var bean = beans.getFirst();

            assertEquals("S:test", bean.params().get("name"));
            assertEquals("I:5", bean.params().get("count"));
        }

        @Test
        @DisplayName("returns an empty list if count=0")
        void shouldReturnEmptyListWhenCountZero() {
            var props = new Properties();
            props.setProperty("bean.count", "0");
            assertTrue(SyntheticMetadataSerializer.readBeans(props).isEmpty());
        }

        @Test
        @DisplayName("returns an empty list if there is no count")
        void shouldReturnEmptyListWhenNoCount() {
            assertTrue(SyntheticMetadataSerializer.readBeans(new Properties()).isEmpty());
        }
    }

    @Nested
    @DisplayName("readObservers - deserialization")
    class ReadObservers {

        @Test
        @DisplayName("reads a synthetic observer from Properties")
        void shouldReadSingleObserver() {
            var props = new Properties();
            props.setProperty("observer.count", "1");
            props.setProperty("observer.0.eventType", "com.example.MyEvent");
            props.setProperty("observer.0.observer", "com.example.MyObserver");
            props.setProperty("observer.0.priority", "1000");
            props.setProperty("observer.0.async", "true");

            var observers = SyntheticMetadataSerializer.readObservers(props);

            assertEquals(1, observers.size());
            var obs = observers.getFirst();
            assertEquals("com.example.MyEvent", obs.eventTypeName());
            assertEquals("com.example.MyObserver", obs.observerClassName());
            assertEquals(1000, obs.priority());
            assertTrue(obs.async());
        }
    }

    @Nested
    @DisplayName("write + read round-trip via OutputStream/InputStream")
    class WriteReadRoundTrip {

        @Test
        @DisplayName("full round-trip with empty bean lists")
        void shouldRoundTripEmptyLists() throws IOException {
            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));
            assertTrue(beans.isEmpty());
        }
    }
}
