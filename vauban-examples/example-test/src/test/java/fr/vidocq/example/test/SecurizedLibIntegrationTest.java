package fr.vidocq.example.test;

import fr.vidocq.example.securized.api.CryptoService;
import fr.vidocq.example.securized.api.LicenseValidator;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates loading example-lib-securized from its actual encrypted JAR.
 * The JAR has been encrypted by vauban:encrypt at build time — internal
 * classes are .class.enc, exported interfaces are in clear.
 *
 * This test verifies the full pipeline: JAR discovery → decryption → CDI injection.
 */
class SecurizedLibIntegrationTest {

    private VaubanContainer container;

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void cryptoServiceFromEncryptedJar() {
        container = buildSecurizedContainer();

        var crypto = container.select(CryptoService.class);
        assertNotNull(crypto, "CryptoService should be resolved from encrypted impl");

        var encoded = crypto.encode("Hello SJAR v2");
        assertNotEquals("Hello SJAR v2", encoded);
        assertEquals("Hello SJAR v2", crypto.decode(encoded));
    }

    @Test
    void licenseValidatorFromEncryptedJar() {
        container = buildSecurizedContainer();

        var validator = container.select(LicenseValidator.class);
        assertNotNull(validator, "LicenseValidator should be resolved from encrypted impl");

        assertFalse(validator.isValid("NOPE"));
        assertTrue(validator.isValid("VAUBAN-12345678"));

        var trial = validator.generateTrial();
        assertTrue(trial.startsWith("VAUBAN-TRIAL-"));
    }

    @Test
    void encryptedJarContainsMarker() throws Exception {
        var jarPath = TestJarHelper.securizedLibJar();

        // Verify the JAR actually has encrypted entries (not just classes on classpath)
        try (var jar = new java.util.jar.JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("META-INF/vauban.encrypted"),
                    "JAR should contain the encryption marker");
            assertNotNull(jar.getEntry("fr/vidocq/example/securized/api/CryptoService.class"),
                    "Exported interface should be in clear");
            assertNotNull(jar.getEntry("fr/vidocq/example/securized/internal/CryptoServiceImpl.class.enc"),
                    "Internal impl should be encrypted");
            assertNull(jar.getEntry("fr/vidocq/example/securized/internal/CryptoServiceImpl.class"),
                    "Plain internal class should NOT exist");
        }
    }

    private VaubanContainer buildSecurizedContainer() {
        var jarPath = TestJarHelper.securizedLibJar();
        var key = TestJarHelper.encryptionKey();

        return VaubanContainer.builder()
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(jarPath)
                .build();
    }
}
