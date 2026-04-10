package fr.vidocq.example.test;

import fr.vidocq.example.securized.api.CryptoService;
import fr.vidocq.example.securized.api.LicenseValidator;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.indexer.scanner.JarScanner;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates a real application that loads beans from BOTH:
 * - example-lib (plain JAR, scanned with JarScanner)
 * - example-lib-securized (encrypted JAR, loaded via SjarPlugin)
 *
 * This is the closest simulation of what happens in production.
 */
class CrossLibIntegrationTest {

    private VaubanContainer container;

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void fullApplicationSimulation() throws Exception {
        var plainJar = TestJarHelper.plainLibJar();
        var securizedJar = TestJarHelper.securizedLibJar();
        var key = TestJarHelper.encryptionKey();

        // Build a classloader with the plain JAR (as a real app would)
        var cl = TestJarHelper.buildClassLoader(plainJar);

        // Discover plain beans from JAR
        var builder = VaubanContainer.builder().classLoader(cl);
        for (var ci : JarScanner.scan(plainJar)) {
            var cls = Class.forName(ci.name().value(), false, cl);
            if (!cls.isInterface() && !cls.isAnnotation()) {
                builder.addBeanClass(cls);
            }
        }

        // Add encrypted beans
        builder.addByteSourcePlugin(new SjarPlugin())
               .pluginContext(SjarKeyProvider.withKey(key))
               .scanSjar(securizedJar);

        container = builder.build();

        // Plain beans work — loaded from JAR
        var greetingClass = cl.loadClass("fr.vidocq.example.lib.GreetingService");
        var greeting = container.select(greetingClass);
        var greetResult = greetingClass.getMethod("greet", String.class).invoke(greeting, "CDI");
        assertEquals("Bonjour, CDI !", greetResult);

        // Encrypted beans work — loaded from encrypted JAR
        var crypto = container.select(CryptoService.class);
        assertNotNull(crypto);
        assertEquals("test", crypto.decode(crypto.encode("test")));

        var validator = container.select(LicenseValidator.class);
        assertTrue(validator.isValid("VAUBAN-12345678"));
    }

    @Test
    void encryptedAndPlainBeansCommunicate() throws Exception {
        var plainJar = TestJarHelper.plainLibJar();
        var securizedJar = TestJarHelper.securizedLibJar();
        var key = TestJarHelper.encryptionKey();

        var cl = TestJarHelper.buildClassLoader(plainJar);
        var builder = VaubanContainer.builder().classLoader(cl);
        for (var ci : JarScanner.scan(plainJar)) {
            var cls = Class.forName(ci.name().value(), false, cl);
            if (!cls.isInterface() && !cls.isAnnotation()) {
                builder.addBeanClass(cls);
            }
        }
        builder.addByteSourcePlugin(new SjarPlugin())
               .pluginContext(SjarKeyProvider.withKey(key))
               .scanSjar(securizedJar);

        container = builder.build();

        // Use CryptoService (encrypted) to encode a message from GreetingService (plain)
        var greetingClass = cl.loadClass("fr.vidocq.example.lib.GreetingService");
        var greeting = container.select(greetingClass);
        var message = (String) greetingClass.getMethod("greet", String.class).invoke(greeting, "Mix");

        var crypto = container.select(CryptoService.class);
        var encoded = crypto.encode(message);
        assertEquals(message, crypto.decode(encoded));
    }
}
