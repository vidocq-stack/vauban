package fr.vidocq.vauban.classloader.spi;

import javax.crypto.SecretKey;
import java.util.Optional;

/**
 * Provides key material and configuration to {@link ByteSourcePlugin} implementations.
 */
public interface PluginContext {

    /**
     * Resolve a secret key by alias from the configured key source.
     *
     * @param keyAlias the key alias (e.g., "my-app-key")
     * @return the secret key
     * @throws SecurityException if the key cannot be resolved
     */
    SecretKey resolveKey(String keyAlias);

    /**
     * Get a configuration property by name.
     *
     * @param name property name
     * @return the property value, or empty if not set
     */
    Optional<String> property(String name);

    /**
     * A no-op context that throws on key resolution. Useful for plugins
     * that don't require encryption keys.
     */
    static PluginContext empty() {
        return new PluginContext() {
            @Override
            public SecretKey resolveKey(String keyAlias) {
                throw new SecurityException("No key provider configured");
            }

            @Override
            public Optional<String> property(String name) {
                return Optional.empty();
            }
        };
    }
}
