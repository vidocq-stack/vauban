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

import io.vidocq.vauban.example.lib.GreetingService;
import io.vidocq.vauban.example.lib.TimeService;
import io.vidocq.vauban.example.securized.api.CryptoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Application bean that injects beans from both libraries:
 * <ul>
 *   <li>{@link GreetingService} and {@link TimeService} from example-lib (plain JAR)</li>
 *   <li>{@link CryptoService} from example-lib-securized (encrypted internals)</li>
 * </ul>
 * Compiles against the exported API interface; at runtime Vauban decrypts
 * and injects the internal implementation class.
 */
@ApplicationScoped
public class WelcomeService {

    private final GreetingService greetingService;
    private final TimeService timeService;
    private final CryptoService cryptoService;

    // CDI proxy requires a no-arg constructor
    protected WelcomeService() {
        this.greetingService = null;
        this.timeService = null;
        this.cryptoService = null;
    }

    @Inject
    public WelcomeService(GreetingService greetingService, TimeService timeService, CryptoService cryptoService) {
        this.greetingService = greetingService;
        this.timeService = timeService;
        this.cryptoService = cryptoService;
    }

    public String welcome(String name) {
        return greetingService.greet(name) + " Il est " + timeService.now() + ".";
    }

    public String welcomeEncoded(String name) {
        return cryptoService.encode(welcome(name));
    }
}
