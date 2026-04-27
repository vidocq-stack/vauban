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
 * Tests du mecanisme de skip BCE au runtime et du chargement des metadonnees
 * synthetiques pre-calculees a la compilation.
 *
 * <h2>Contexte</h2>
 * Quand le VaubanProcessor (APT) execute les BCEs a la compilation, il ecrit :
 * <ul>
 *   <li>{@code META-INF/vauban-bce-processed} — marqueur pour le skip runtime</li>
 *   <li>{@code META-INF/vauban-synthetic-metadata.properties} — beans/observers synthetiques</li>
 * </ul>
 * Au boot, le VaubanContainerBuilder detecte le marqueur et charge les metadonnees
 * au lieu de re-executer les BCEs. Ces tests verifient que ce mecanisme fonctionne.
 */
@DisplayName("BCE runtime skip - chargement des metadonnees synthetiques")
class BceRuntimeSkipTest {

    @Nested
    @DisplayName("SyntheticMetadataSerializer - round-trip complet avec builders")
    class SerializerRoundTrip {

        @Test
        @DisplayName("serialise et desersialise un bean synthetique avec tous les champs")
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
        @DisplayName("serialise et deserialise un observer synthetique")
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
        @DisplayName("serialise plusieurs beans et observers ensemble")
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
    @DisplayName("SyntheticBeanDescriptor - reconstruction des champs")
    class DescriptorReconstruction {

        @Test
        @DisplayName("les types sont preserves apres serialisation")
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
        @DisplayName("les qualifiers sont preserves apres serialisation")
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
        @DisplayName("disposer est preserve s'il est present")
        void shouldPreserveDisposer() throws IOException {
            var builder = new VaubanSyntheticBeanBuilder<>(String.class);
            // Pas de disposer configuré → doit être null

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(builder), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));

            assertNull(beans.getFirst().disposerClassName());
        }

        @Test
        @DisplayName("bean sans scope a scopeAnnotation null")
        void shouldHandleNullScope() throws IOException {
            var builder = new VaubanSyntheticBeanBuilder<>(String.class);
            // Pas de scope

            var baos = new ByteArrayOutputStream();
            SyntheticMetadataSerializer.write(List.of(builder), List.of(), baos);

            var beans = SyntheticMetadataSerializer.readBeans(
                    new ByteArrayInputStream(baos.toByteArray()));

            assertNull(beans.getFirst().scopeAnnotation());
        }
    }

    @Nested
    @DisplayName("Marqueur BCE")
    class BceMarker {

        @Test
        @DisplayName("le chemin du marqueur est correct")
        void shouldHaveCorrectMarkerPath() {
            assertEquals("META-INF/vauban-bce-processed",
                    SyntheticMetadataSerializer.BCE_PROCESSED_MARKER);
        }

        @Test
        @DisplayName("le chemin des metadonnees est correct")
        void shouldHaveCorrectMetadataPath() {
            assertEquals("META-INF/vauban-synthetic-metadata.properties",
                    SyntheticMetadataSerializer.METADATA_PATH);
        }
    }
}
