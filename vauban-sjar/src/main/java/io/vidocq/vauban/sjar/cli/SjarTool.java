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
package io.vidocq.vauban.sjar.cli;

import io.vidocq.vauban.sjar.SjarEncryptor;
import io.vidocq.vauban.sjar.SjarKeyProvider;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * CLI tool for creating and verifying SJAR files.
 *
 * <pre>
 * Usage:
 *   sjar encrypt --input app.jar --output app.sjar --key-alias mykey
 *   sjar generate-key
 * </pre>
 */
public final class SjarTool {

    private SjarTool() {}

    @SuppressWarnings("java:S106") // CLI tool uses System.out/err intentionally
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        switch (args[0]) {
            case "encrypt" -> encrypt(args);
            case "generate-key" -> generateKey();
            default -> {
                System.err.println("Unknown command: " + args[0]);
                printUsage();
            }
        }
    }

    @SuppressWarnings("java:S106")
    private static void encrypt(String[] args) throws Exception {
        String input = null;
        String output = null;
        String keyAlias = "default";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input", "-i" -> input = args[++i];
                case "--output", "-o" -> output = args[++i];
                case "--key-alias", "-k" -> keyAlias = args[++i];
                default -> System.err.println("Unknown option: " + args[i]);
            }
        }

        if (input == null) {
            System.err.println("Error: --input is required");
            return;
        }
        if (output == null) {
            output = input.replace(".jar", ".sjar");
        }

        // Resolve key from environment
        var envKey = System.getenv(SjarKeyProvider.ENV_KEY);
        if (envKey == null || envKey.isBlank()) {
            System.err.println("Error: Set " + SjarKeyProvider.ENV_KEY + " environment variable (64 hex chars)");
            return;
        }

        SecretKey key = new SecretKeySpec(HexFormat.of().parseHex(envKey.strip()), "AES");
        SjarEncryptor.encrypt(Path.of(input), Path.of(output), key, keyAlias);
        System.out.println("Encrypted: " + input + " -> " + output);
    }

    @SuppressWarnings("java:S106")
    private static void generateKey() {
        var key = SjarKeyProvider.generateKey();
        System.out.println(HexFormat.of().formatHex(key.getEncoded()));
    }

    @SuppressWarnings("java:S106")
    private static void printUsage() {
        System.out.println("""
                Vauban SJAR Tool - Secure JAR encryption

                Commands:
                  encrypt        Encrypt a JAR file into an SJAR
                    --input, -i    Input JAR path (required)
                    --output, -o   Output SJAR path (default: input with .sjar extension)
                    --key-alias, -k Key alias in metadata (default: "default")

                  generate-key   Generate a random AES-256 key (hex)

                Environment:
                  VAUBAN_SJAR_KEY  Hex-encoded 256-bit AES key (64 chars)
                """);
    }
}
