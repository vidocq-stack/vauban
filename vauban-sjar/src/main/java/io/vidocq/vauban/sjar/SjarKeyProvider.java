package io.vidocq.vauban.sjar;

import io.vidocq.vauban.classloader.spi.PluginContext;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Resolves encryption keys from multiple sources:
 * <ol>
 *   <li>Environment variable: {@code VAUBAN_SJAR_KEY} (hex-encoded 256-bit key)</li>
 *   <li>Java Keystore: {@code -Dvauban.sjar.keystore} + {@code -Dvauban.sjar.keypassword}</li>
 *   <li>Programmatic callback via custom {@link PluginContext}</li>
 * </ol>
 */
public final class SjarKeyProvider implements PluginContext {

    public static final String ENV_KEY = "VAUBAN_SJAR_KEY";
    public static final String PROP_KEYSTORE = "vauban.sjar.keystore";
    public static final String PROP_KEY_PASSWORD = "vauban.sjar.keypassword";

    @Override
    public SecretKey resolveKey(String keyAlias) {
        // 1. Try environment variable
        var envKey = System.getenv(ENV_KEY);
        if (envKey != null && !envKey.isBlank()) {
            return fromHex(envKey);
        }

        // 2. Try Java Keystore
        var keystorePath = System.getProperty(PROP_KEYSTORE);
        if (keystorePath != null) {
            return fromKeystore(keystorePath, keyAlias);
        }

        throw new SecurityException(
                "No SJAR key found. Set " + ENV_KEY + " env var or " + PROP_KEYSTORE + " system property.");
    }

    @Override
    public Optional<String> property(String name) {
        var sysProp = System.getProperty("vauban.sjar." + name);
        if (sysProp != null) return Optional.of(sysProp);
        return Optional.empty();
    }

    private static SecretKey fromHex(String hex) {
        var bytes = HexFormat.of().parseHex(hex.strip());
        if (bytes.length != 32) {
            throw new SecurityException(
                    ENV_KEY + " must be 64 hex characters (256-bit key), got " + hex.length() + " chars");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    @SuppressWarnings("java:S112") // Wrapping security exceptions
    private static SecretKey fromKeystore(String path, String alias) {
        try {
            var password = System.getProperty(PROP_KEY_PASSWORD, "").toCharArray();
            var ks = KeyStore.getInstance("PKCS12");
            try (var is = Files.newInputStream(Path.of(path))) {
                ks.load(is, password);
            }
            var entry = ks.getEntry(alias, new KeyStore.PasswordProtection(password));
            if (entry instanceof KeyStore.SecretKeyEntry ske) {
                return ske.getSecretKey();
            }
            throw new SecurityException("Keystore entry '" + alias + "' is not a secret key");
        } catch (GeneralSecurityException | IOException e) {
            throw new SecurityException("Failed to load key '" + alias + "' from " + path, e);
        }
    }

    /**
     * Create a simple PluginContext with a fixed key (for testing or programmatic use).
     */
    public static PluginContext withKey(SecretKey key) {
        return new PluginContext() {
            @Override
            public SecretKey resolveKey(String keyAlias) {
                return key;
            }

            @Override
            public Optional<String> property(String name) {
                return Optional.empty();
            }
        };
    }

    /**
     * Generate a new random AES-256 key for SJAR encryption.
     */
    public static SecretKey generateKey() {
        try {
            var keyGen = javax.crypto.KeyGenerator.getInstance("AES");
            keyGen.init(256);
            return keyGen.generateKey();
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Failed to generate AES-256 key", e);
        }
    }
}
