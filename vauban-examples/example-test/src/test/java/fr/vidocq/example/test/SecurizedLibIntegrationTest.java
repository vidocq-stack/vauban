package fr.vidocq.example.test;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.sjar.SjarEncryptor;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CDI container with encrypted (SJAR) library beans.
 * The test encrypts the example-lib-securized JAR on the fly, then loads it via scanSjar().
 */
class SecurizedLibIntegrationTest {

    @TempDir
    Path tempDir;

    private VaubanContainer container;
    private SecretKey key;
    private Path sjarPath;

    @BeforeEach
    void setUp() throws Exception {
        key = SjarKeyProvider.generateKey();

        // Find the example-lib-securized JAR on the classpath
        var jarPath = findSecurizedJar();
        sjarPath = tempDir.resolve("securized.sjar");

        // Encrypt it
        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");
        assertTrue(Files.exists(sjarPath));
    }

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void loadEncryptedBeans() throws Exception {
        container = VaubanContainer.builder()
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(sjarPath)
                .build();

        // CryptoService is @ApplicationScoped in the encrypted lib
        var cryptoService = container.select(
                Class.forName("fr.vidocq.example.securized.CryptoService", true,
                        Thread.currentThread().getContextClassLoader()));
        assertNotNull(cryptoService);
    }

    @Test
    void encryptedBeanFunctionality() throws Exception {
        container = VaubanContainer.builder()
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(sjarPath)
                .build();

        var cryptoClass = Class.forName("fr.vidocq.example.securized.CryptoService", true,
                Thread.currentThread().getContextClassLoader());
        var service = container.select(cryptoClass);

        // Use reflection to call encode/decode since we load from SJAR classloader
        var encodeMethod = cryptoClass.getMethod("encode", String.class);
        var decodeMethod = cryptoClass.getMethod("decode", String.class);

        var encoded = (String) encodeMethod.invoke(service, "Hello SJAR");
        assertNotNull(encoded);
        assertNotEquals("Hello SJAR", encoded);

        var decoded = (String) decodeMethod.invoke(service, encoded);
        assertEquals("Hello SJAR", decoded);
    }

    @Test
    void licenseValidatorWorks() throws Exception {
        container = VaubanContainer.builder()
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(sjarPath)
                .build();

        var validatorClass = Class.forName("fr.vidocq.example.securized.LicenseValidator", true,
                Thread.currentThread().getContextClassLoader());
        var validator = container.select(validatorClass);

        var isValidMethod = validatorClass.getMethod("isValid", String.class);
        var generateTrialMethod = validatorClass.getMethod("generateTrial");

        assertFalse((boolean) isValidMethod.invoke(validator, "INVALID"));
        assertTrue((boolean) isValidMethod.invoke(validator, "VAUBAN-12345678"));

        var trialKey = (String) generateTrialMethod.invoke(validator);
        assertNotNull(trialKey);
        assertTrue(trialKey.startsWith("VAUBAN-TRIAL-"));
    }

    private Path findSecurizedJar() throws Exception {
        // The securized lib JAR is on the test classpath as a Maven dependency
        var resource = getClass().getClassLoader().getResource(
                "fr/vidocq/example/securized/CryptoService.class");
        if (resource == null) {
            throw new IllegalStateException(
                    "example-lib-securized not found on classpath. Run 'mvn install' first.");
        }

        var url = resource.toString();
        if (url.startsWith("jar:file:")) {
            // Extract JAR path from "jar:file:/path/to/jar!/class/path"
            var jarUrl = url.substring("jar:file:".length(), url.indexOf('!'));
            return Path.of(jarUrl);
        } else if (url.startsWith("file:")) {
            // Classes directory — create a JAR from it
            var classesDir = Path.of(URI.create(url.substring(0,
                    url.indexOf("fr/vidocq/example/securized/"))));
            return createJarFromClasses(classesDir);
        }
        throw new IllegalStateException("Cannot locate securized JAR from: " + url);
    }

    private Path createJarFromClasses(Path classesDir) throws Exception {
        var jarPath = tempDir.resolve("securized-classes.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            try (var walk = Files.walk(classesDir)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> {
                            try {
                                var entryName = classesDir.relativize(p).toString()
                                        .replace(java.io.File.separatorChar, '/');
                                jos.putNextEntry(new java.util.jar.JarEntry(entryName));
                                jos.write(Files.readAllBytes(p));
                                jos.closeEntry();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
        return jarPath;
    }
}
