package fr.vidocq.example.test;

import fr.vidocq.example.securized.api.CryptoService;
import fr.vidocq.example.securized.api.LicenseValidator;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CDI container with encrypted library beans.
 * Uses only exported interfaces — internal impls are encrypted and loaded
 * by Vauban at runtime via the encrypted JAR plugin.
 */
class SecurizedLibIntegrationTest {

    private VaubanContainer container;

    @BeforeEach
    void setUp() throws Exception {
        // Find the encrypted JAR on the classpath
        var jarPath = findSecurizedJar();
        var hexKey = System.getProperty("vauban.sjar.key",
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2");
        var key = new SecretKeySpec(HexFormat.of().parseHex(hexKey), "AES");

        container = VaubanContainer.builder()
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(jarPath)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void cryptoServiceEncodeDecode() {
        var crypto = container.select(CryptoService.class);
        assertNotNull(crypto);

        var encoded = crypto.encode("Hello");
        assertNotEquals("Hello", encoded);
        assertEquals("Hello", crypto.decode(encoded));
    }

    @Test
    void licenseValidatorWorks() {
        var validator = container.select(LicenseValidator.class);
        assertNotNull(validator);

        assertFalse(validator.isValid("INVALID"));
        assertTrue(validator.isValid("VAUBAN-12345678"));

        var trial = validator.generateTrial();
        assertTrue(trial.startsWith("VAUBAN-TRIAL-"));
    }

    private static Path findSecurizedJar() throws Exception {
        // The encrypted JAR is in the sibling module's target
        var path = Path.of("../example-lib-securized/target/example-lib-securized-0.1.0-SNAPSHOT.jar");
        if (java.nio.file.Files.exists(path)) return path;

        // Fallback: try from system property
        var prop = System.getProperty("sjar.path");
        if (prop != null) return Path.of(prop);

        throw new IllegalStateException("Cannot find encrypted JAR. Build example-lib-securized first.");
    }
}
