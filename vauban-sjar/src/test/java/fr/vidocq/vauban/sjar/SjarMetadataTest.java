package fr.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SjarMetadataTest {

    @Test
    void roundTripSerialization() throws Exception {
        var iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        var entries = Map.of(
                "com/example/Foo.class.enc", new SjarMetadata.EntryMetadata(iv, 1024),
                "com/example/Bar.class.enc", new SjarMetadata.EntryMetadata(iv, 512)
        );
        var metadata = new SjarMetadata("my-key", entries);

        // Serialize
        var out = new ByteArrayOutputStream();
        metadata.writeTo(out);
        var json = out.toString();

        assertTrue(json.contains("\"keyAlias\": \"my-key\""));
        assertTrue(json.contains("\"algorithm\": \"AES/GCM/NoPadding\""));
        assertTrue(json.contains("\"version\": 1"));

        // Deserialize
        var parsed = SjarMetadata.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("my-key", parsed.keyAlias());
        assertEquals(2, parsed.entries().size());

        var fooEntry = parsed.entries().get("com/example/Foo.class.enc");
        assertNotNull(fooEntry);
        assertEquals(1024, fooEntry.originalSize());
        assertArrayEquals(iv, fooEntry.iv());
    }

    @Test
    void emptyEntries() throws Exception {
        var metadata = new SjarMetadata("empty-key", Map.of());
        var out = new ByteArrayOutputStream();
        metadata.writeTo(out);

        var parsed = SjarMetadata.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("empty-key", parsed.keyAlias());
        assertTrue(parsed.entries().isEmpty());
    }
}
