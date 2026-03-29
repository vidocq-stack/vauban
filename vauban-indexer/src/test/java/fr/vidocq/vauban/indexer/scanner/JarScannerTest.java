package fr.vidocq.vauban.indexer.scanner;

import fr.vidocq.vauban.indexer.model.DotName;
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
    @DisplayName("scanne un JAR et trouve des classes")
    void shouldScanJar() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        assertFalse(classes.isEmpty(), "devrait trouver des classes dans le JAR JUnit");
    }

    @Test
    @DisplayName("trouve une classe connue dans le JAR")
    void shouldFindKnownClass() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        boolean found = classes.stream()
                .anyMatch(c -> c.name().equals(DotName.of("org.junit.jupiter.api.Test")));
        assertTrue(found, "devrait trouver org.junit.jupiter.api.Test");
    }

    @Test
    @DisplayName("retourne plusieurs classes")
    void shouldReturnMultipleClasses() throws Exception {
        var classes = JarScanner.scan(junitJarPath());
        assertTrue(classes.size() > 10, "un JAR JUnit devrait contenir plus de 10 classes");
    }
}
