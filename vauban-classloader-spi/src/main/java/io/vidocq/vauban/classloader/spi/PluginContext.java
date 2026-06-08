/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.classloader.spi;

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
