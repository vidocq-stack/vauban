package fr.vidocq.example.test;

import fr.vidocq.example.lib.GreetingService;
import fr.vidocq.example.lib.TimeService;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.sjar.SjarEncryptor;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CDI container with BOTH plain and encrypted beans loaded simultaneously.
 * Demonstrates that Vauban can compose beans from regular JARs and SJARs in the same container.
 */
class CrossLibIntegrationTest {

    @TempDir
    Path tempDir;

    private VaubanContainer container;

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void mixPlainAndEncryptedBeans() throws Exception {
        var key = SjarKeyProvider.generateKey();
        var sjarPath = encryptSecurizedLib(key);

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

    private Path encryptSecurizedLib(SecretKey key) throws Exception {
        var resource = getClass().getClassLoader().getResource(
                "fr/vidocq/example/securized/CryptoService.class");
        if (resource == null) {
            throw new IllegalStateException("example-lib-securized not on classpath");
        }

        Path jarPath;
        var url = resource.toString();
        if (url.startsWith("jar:file:")) {
            jarPath = Path.of(url.substring("jar:file:".length(), url.indexOf('!')));
        } else {
            var classesDir = Path.of(URI.create(url.substring(0,
                    url.indexOf("fr/vidocq/example/securized/"))));
            jarPath = createJarFromClasses(classesDir);
        }

        var sjarPath = tempDir.resolve("securized.sjar");
        SjarEncryptor.encrypt(jarPath, sjarPath, key, "test-key");
        return sjarPath;
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
