package fr.vidocq.example.test;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CDI container with encrypted (SJAR) library beans.
 * The SJAR is built by the vauban-maven-plugin:encrypt goal during
 * example-lib-securized's build — this test consumes the pre-built artifact.
 */
class SecurizedLibIntegrationTest {

    private VaubanContainer container;
    private static SecretKey key;
    private static Path sjarPath;

    @BeforeAll
    static void findSjar() {
        // Key from VAUBAN_SJAR_KEY env or system property (same one used at build time)
        var hexKey = System.getenv(SjarKeyProvider.ENV_KEY);
        if (hexKey == null) hexKey = System.getProperty("vauban.sjar.key");
        assertNotNull(hexKey, "VAUBAN_SJAR_KEY env or -Dvauban.sjar.key must be set");
        key = new SecretKeySpec(HexFormat.of().parseHex(hexKey.strip()), "AES");

        // The SJAR is built by maven in example-lib-securized/target/
        var sjarProp = System.getProperty("sjar.path");
        if (sjarProp != null) {
            sjarPath = Path.of(sjarProp);
        } else {
            // Convention: sibling module target directory
            sjarPath = Path.of("../example-lib-securized/target/example-lib-securized-0.1.0-SNAPSHOT.sjar");
        }
        assertTrue(Files.exists(sjarPath),
                "Pre-built SJAR not found at " + sjarPath.toAbsolutePath()
                        + ". Run 'mvn package -Dvauban.sjar.key=<hex>' on example-lib-securized first.");
    }

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void loadEncryptedBeans() {
        container = VaubanContainer.builder()
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(sjarPath)
                .build();

        var cryptoService = container.select(
                loadClass("fr.vidocq.example.securized.CryptoService"));
        assertNotNull(cryptoService);
    }

    @Test
    void encryptedBeanFunctionality() throws Exception {
        container = VaubanContainer.builder()
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(sjarPath)
                .build();

        var cryptoClass = loadClass("fr.vidocq.example.securized.CryptoService");
        var service = container.select(cryptoClass);

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

        var validatorClass = loadClass("fr.vidocq.example.securized.LicenseValidator");
        var validator = container.select(validatorClass);

        var isValidMethod = validatorClass.getMethod("isValid", String.class);
        var generateTrialMethod = validatorClass.getMethod("generateTrial");

        assertFalse((boolean) isValidMethod.invoke(validator, "INVALID"));
        assertTrue((boolean) isValidMethod.invoke(validator, "VAUBAN-12345678"));

        var trialKey = (String) generateTrialMethod.invoke(validator);
        assertNotNull(trialKey);
        assertTrue(trialKey.startsWith("VAUBAN-TRIAL-"));
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            fail("Class not found (should be loaded from SJAR): " + name);
            return null; // unreachable
        }
    }
}
