package fr.vidocq.example.test;

import fr.vidocq.example.lib.GreetingService;
import fr.vidocq.example.lib.TimeService;
import fr.vidocq.example.securized.api.CryptoService;
import fr.vidocq.example.securized.api.LicenseValidator;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CDI container with BOTH plain and encrypted beans simultaneously.
 * WelcomeService injects GreetingService (plain) + CryptoService (encrypted impl).
 */
class CrossLibIntegrationTest {

    private VaubanContainer container;

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void plainAndEncryptedBeansCoexist() throws Exception {
        container = buildMixedContainer();

        var greeting = container.select(GreetingService.class);
        assertEquals("Bonjour, Vauban !", greeting.greet("Vauban"));

        var crypto = container.select(CryptoService.class);
        assertEquals("test", crypto.decode(crypto.encode("test")));

        var validator = container.select(LicenseValidator.class);
        assertTrue(validator.isValid("VAUBAN-12345678"));
    }

    private VaubanContainer buildMixedContainer() throws Exception {
        var jarPath = Path.of("../example-lib-securized/target/example-lib-securized-0.1.0-SNAPSHOT.jar");
        var hexKey = System.getProperty("vauban.sjar.key",
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2");
        var key = new SecretKeySpec(HexFormat.of().parseHex(hexKey), "AES");

        return VaubanContainer.builder()
                .addBeanClass(GreetingService.class)
                .addBeanClass(TimeService.class)
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(jarPath)
                .build();
    }
}
