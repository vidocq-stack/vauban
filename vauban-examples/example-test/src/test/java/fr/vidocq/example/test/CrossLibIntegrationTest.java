package fr.vidocq.example.test;

import fr.vidocq.example.lib.GreetingService;
import fr.vidocq.example.lib.TimeService;
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
 * Demonstrates that Vauban can compose beans from regular JARs and SJARs in the same container.
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
    void mixPlainAndEncryptedBeans() throws Exception {
        container = VaubanContainer.builder()
                // Plain beans
                .addBeanClass(GreetingService.class)
                .addBeanClass(TimeService.class)
                // Encrypted beans
                .addByteSourcePlugin(new SjarPlugin())
                .pluginContext(SjarKeyProvider.withKey(key))
                .scanSjar(sjarPath)
                .build();

        // Plain beans work
        var greeting = container.select(GreetingService.class);
        assertNotNull(greeting);
        assertEquals("Bonjour, Vauban !", greeting.greet("Vauban"));

        var time = container.select(TimeService.class);
        assertNotNull(time);
        assertNotNull(time.now());

        // Encrypted beans work
        var cryptoClass = Class.forName("fr.vidocq.example.securized.CryptoService", true,
                Thread.currentThread().getContextClassLoader());
        var crypto = container.select(cryptoClass);
        assertNotNull(crypto);

        var encodeMethod = cryptoClass.getMethod("encode", String.class);
        var decodeMethod = cryptoClass.getMethod("decode", String.class);
        var encoded = (String) encodeMethod.invoke(crypto, "mixed");
        assertEquals("mixed", decodeMethod.invoke(crypto, encoded));
    }
}
