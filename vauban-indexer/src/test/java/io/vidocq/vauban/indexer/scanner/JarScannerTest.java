package io.vidocq.vauban.indexer.scanner;

import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JarScanner")
class JarScannerTest {

    private static Path junitJarPath() throws URISyntaxException {
        var url = Test.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(url.toURI());
    }

    @Test
    @DisplayName("scans a JAR and finds classes")
    void shouldScanJar() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        assertFalse(classes.isEmpty(), "should find classes in the JUnit JAR");
    }

    @Test
    @DisplayName("finds a known class in the JAR")
    void shouldFindKnownClass() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        boolean found = classes.stream()
                .anyMatch(c -> c.name().equals(DotName.of("org.junit.jupiter.api.Test")));
        assertTrue(found, "should find org.junit.jupiter.api.Test");
    }

    @Test
    @DisplayName("returns multiple classes")
    void shouldReturnMultipleClasses() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        assertTrue(classes.size() > 10, "a JUnit JAR should contain more than 10 classes");
    }
}
