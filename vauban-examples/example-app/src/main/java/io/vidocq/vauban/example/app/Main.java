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
package io.vidocq.vauban.example.app;

import io.vidocq.vauban.core.container.VaubanContainer;

/**
 * Entry point for the Vauban CDI example application.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Automatic bean discovery via {@code scanClasspath()} — reads
 *       {@code META-INF/vauban-beans.list} from all JARs and directories</li>
 *   <li>Plain beans from example-lib</li>
 *   <li>Encrypted beans from example-lib-securized (internal impls decrypted at runtime)</li>
 *   <li>Cross-library injection — WelcomeService uses both</li>
 * </ul>
 */
@SuppressWarnings("java:S106")
public class Main {

    public static void main(String[] args) {
        var name = args.length > 0 ? args[0] : "Vauban";

        try (var container = VaubanContainer.builder()
                .scanClasspath()
                .build()) {

            var welcome = container.select(WelcomeService.class);
            System.out.println(welcome.welcome(name));
            System.out.println("Encoded: " + welcome.welcomeEncoded(name));
        }
    }
}
