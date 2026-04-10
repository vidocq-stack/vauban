package fr.vidocq.example.test;

import fr.vidocq.example.app.WelcomeService;
import fr.vidocq.example.lib.GreetingService;
import fr.vidocq.example.lib.TimeService;
import fr.vidocq.example.securized.CryptoService;
import fr.vidocq.example.securized.LicenseValidator;
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
 * Tests CDI container with BOTH plain and encrypted beans loaded simultaneously.
 * WelcomeService from example-app injects beans from both example-lib (plain)
 * and example-lib-securized (encrypted SJAR).
 */
class CrossLibIntegrationTest {

    private VaubanContainer container;
    private static SecretKey key;
    private static Path sjarPath;

    @BeforeAll
    static void findSjar() {
        var hexKey = System.getenv(SjarKeyProvider.ENV_KEY);
        if (hexKey == null) hexKey = System.getProperty("vauban.sjar.key");
        assertNotNull(hexKey, "VAUBAN_SJAR_KEY env or -Dvauban.sjar.key must be set");
        key = new SecretKeySpec(HexFormat.of().parseHex(hexKey.strip()), "AES");

        var sjarProp = System.getProperty("sjar.path");
        sjarPath = sjarProp != null ? Path.of(sjarProp)
                : Path.of("../example-lib-securized/target/example-lib-securized-0.1.0-SNAPSHOT.sjar");
        assertTrue(Files.exists(sjarPath),
                "Pre-built SJAR not found at " + sjarPath.toAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void plainBeansWork() {
        container = buildMixedContainer();

        var greeting = container.select(GreetingService.class);
        assertEquals("Bonjour, Vauban !", greeting.greet("Vauban"));

        var time = container.select(TimeService.class);
        assertNotNull(time.now());
    }

    @Test
    void encryptedBeansWork() {
        container = buildMixedContainer();

        var crypto = container.select(CryptoService.class);
        var encoded = crypto.encode("test");
        assertEquals("test", crypto.decode(encoded));

        var validator = container.select(LicenseValidator.class);
        assertTrue(validator.isValid("VAUBAN-12345678"));
        assertFalse(validator.isValid("INVALID"));
    }

    @Test
    void welcomeServiceUsesPlainBeans() {
        container = buildMixedContainer();

        var welcome = container.select(WelcomeService.class);
        var result = welcome.welcome("CDI");
        assertTrue(result.startsWith("Bonjour, CDI !"));
        assertTrue(result.contains("Il est"));
    }

    @Test
    void welcomeServiceUsesEncryptedBeans() {
        container = buildMixedContainer();

        var welcome = container.select(WelcomeService.class);
        var crypto = container.select(CryptoService.class);

        var encoded = welcome.welcomeEncoded("CDI");
        assertNotNull(encoded);

        // Decode and verify content
        var decoded = crypto.decode(encoded);
        assertTrue(decoded.startsWith("Bonjour, CDI !"));
    }

    private VaubanContainer buildMixedContainer() {
        return VaubanContainer.builder()
                // Plain beans
                .addBeanClass(GreetingService.class)
                .addBeanClass(TimeService.class)
                // App bean (uses both libs)
                .addBeanClass(WelcomeService.class)
                // Encrypted beans from SJAR
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(sjarPath)
                .build();
    }
}
