package fr.vidocq.example.test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helper for integration tests — locates the actual JARs built by sibling
 * modules and creates a URLClassLoader simulating a real application classpath.
 */
final class TestJarHelper {

    private TestJarHelper() {}

    static Path plainLibJar() {
        var path = Path.of(System.getProperty("jar.example.lib",
                "../example-lib/target/example-lib-0.1.0-SNAPSHOT.jar"));
        assertTrue(Files.exists(path),
                "example-lib JAR not found at " + path.toAbsolutePath()
                        + ". Run 'mvn package' on example-lib first.");
        return path;
    }

    static Path securizedLibJar() {
        var path = Path.of(System.getProperty("jar.example.securized",
                "../example-lib-securized/target/example-lib-securized-0.1.0-SNAPSHOT.jar"));
        assertTrue(Files.exists(path),
                "example-lib-securized JAR not found at " + path.toAbsolutePath()
                        + ". Run 'mvn package' on example-lib-securized first.");
        return path;
    }

    static SecretKey encryptionKey() {
        var hex = System.getProperty("vauban.sjar.key",
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2");
        return new SecretKeySpec(HexFormat.of().parseHex(hex), "AES");
    }

    /**
     * Build a URLClassLoader that includes the given JARs, simulating
     * what a real application classpath looks like at runtime.
     */
    static URLClassLoader buildClassLoader(Path... jars) throws Exception {
        var urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        return new URLClassLoader(urls, TestJarHelper.class.getClassLoader());
    }
}
