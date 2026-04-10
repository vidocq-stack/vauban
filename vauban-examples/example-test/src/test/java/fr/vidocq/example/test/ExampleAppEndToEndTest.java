package fr.vidocq.example.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test: launches {@code example-app} as a separate Java process
 * with the real JARs on the classpath (including the encrypted JAR) and verifies stdout.
 *
 * <p>This simulates exactly what happens in production: the container starts,
 * discovers beans from {@code vauban-beans.list}, decrypts internal classes from
 * the encrypted JAR, injects everything, and runs the application.
 */
class ExampleAppEndToEndTest {

    private static final String DEMO_KEY = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2";
    private static String classpath;

    @BeforeAll
    static void buildClasspath() {
        var jars = new ArrayList<String>();

        // Application and libraries
        addJar(jars, "jar.example.app");
        addJar(jars, "jar.example.lib");
        addJar(jars, "jar.example.securized");

        // Vauban runtime
        addJar(jars, "jar.vauban.core");
        addJar(jars, "jar.vauban.api");
        addJar(jars, "jar.vauban.indexer");
        addJar(jars, "jar.vauban.sjar");
        addJar(jars, "jar.vauban.classloader.spi");

        // Jakarta APIs
        addJar(jars, "jar.jakarta.cdi");
        addJar(jars, "jar.jakarta.annotation");
        addJar(jars, "jar.jakarta.el");
        addJar(jars, "jar.jakarta.interceptor");
        addJar(jars, "jar.jakarta.inject");
        addJar(jars, "jar.jakarta.lang.model");

        classpath = String.join(System.getProperty("path.separator"), jars);
    }

    private static void addJar(List<String> jars, String sysProp) {
        var path = System.getProperty(sysProp);
        assertNotNull(path, "System property " + sysProp + " not set");
        assertTrue(Files.exists(Path.of(path)),
                "JAR not found: " + path + " (from " + sysProp + ")");
        jars.add(path);
    }

    @Test
    void applicationStartsAndProducesOutput() throws Exception {
        var result = runApp("Vauban");

        assertEquals(0, result.exitCode(),
                "App should exit successfully. stderr:\n" + result.stderr());
        assertFalse(result.stdout().isBlank(), "App should produce output");
    }

    @Test
    void greetingContainsName() throws Exception {
        var result = runApp("CDI");

        assertEquals(0, result.exitCode(), "stderr:\n" + result.stderr());
        assertTrue(result.stdout().contains("Bonjour, CDI !"),
                "Should contain greeting. Got:\n" + result.stdout());
    }

    @Test
    void outputContainsTime() throws Exception {
        var result = runApp("Test");

        assertEquals(0, result.exitCode(), "stderr:\n" + result.stderr());
        assertTrue(result.stdout().matches("(?s).*\\d{2}:\\d{2}:\\d{2}.*"),
                "Should contain time. Got:\n" + result.stdout());
    }

    @Test
    void encodedLineIsValidBase64() throws Exception {
        var result = runApp("Base64Test");

        assertEquals(0, result.exitCode(), "stderr:\n" + result.stderr());
        var lines = result.stdout().lines().toList();
        assertTrue(lines.size() >= 2, "Should have at least 2 lines. Got:\n" + result.stdout());

        // Second line should be "Encoded: <base64>"
        var encodedLine = lines.get(1);
        assertTrue(encodedLine.startsWith("Encoded: "),
                "Second line should start with 'Encoded: '. Got: " + encodedLine);

        var base64Part = encodedLine.substring("Encoded: ".length());
        var decoded = new String(Base64.getDecoder().decode(base64Part));
        assertTrue(decoded.contains("Bonjour, Base64Test"),
                "Decoded should contain greeting. Got: " + decoded);
    }

    @Test
    void encryptedJarIsActuallyEncrypted() throws Exception {
        // Verify the securized JAR has encrypted entries (not just running from target/classes)
        var securizedJar = System.getProperty("jar.example.securized");
        try (var jar = new java.util.jar.JarFile(securizedJar)) {
            assertNotNull(jar.getEntry("META-INF/vauban.encrypted"),
                    "JAR should have encryption marker");
            assertNotNull(jar.getEntry("fr/vidocq/example/securized/internal/CryptoServiceImpl.class.enc"),
                    "Internal class should be encrypted (.class.enc)");
            assertNull(jar.getEntry("fr/vidocq/example/securized/internal/CryptoServiceImpl.class"),
                    "Plain internal class should NOT exist");
            assertNotNull(jar.getEntry("fr/vidocq/example/securized/api/CryptoService.class"),
                    "Exported interface should be in clear");
        }
    }

    private ProcessResult runApp(String name) throws IOException, InterruptedException {
        var javaExe = System.getProperty("java.executable",
                ProcessHandle.current().info().command().orElse("java"));

        var pb = new ProcessBuilder(javaExe, "-cp", classpath,
                "fr.vidocq.example.app.Main", name);
        // Pass the encryption key so the container can decrypt encrypted JARs
        pb.environment().put("VAUBAN_SJAR_KEY", DEMO_KEY);
        var process = pb.start();

        var stdout = new String(process.getInputStream().readAllBytes());
        var stderr = new String(process.getErrorStream().readAllBytes());
        var exited = process.waitFor(30, TimeUnit.SECONDS);

        assertTrue(exited, "Process should complete within 30s");
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {}
}
