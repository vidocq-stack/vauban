package fr.vidocq.vauban.sjar;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SjarPluginTest {

    @Test
    void handlesSjarFiles() {
        var plugin = new SjarPlugin();
        assertTrue(plugin.handles(Path.of("mylib.sjar")));
        assertTrue(plugin.handles(Path.of("/path/to/beans.sjar")));
        assertFalse(plugin.handles(Path.of("mylib.jar")));
        assertFalse(plugin.handles(Path.of("mylib.zip")));
    }

    @Test
    void protocolIsSjar() {
        assertEquals("sjar", new SjarPlugin().protocol());
    }

    @Test
    void priorityIs100() {
        assertEquals(100, new SjarPlugin().priority());
    }
}
