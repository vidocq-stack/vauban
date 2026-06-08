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
package io.vidocq.vauban.core.proxy.sub;

/**
 * Parent class used for the proxy tests: it exposes a {@code protected} method
 * in a package distinct from the proxy's in order to reproduce the JVMS
 * §4.10.1.9 constraint (invokevirtual rejected).
 *
 * <p>Typical case: {@code jakarta.servlet.http.HttpServlet.doGet} is
 * {@code protected}; an {@code @ApplicationScoped} servlet that inherits from it
 * cannot be proxied via classic {@code invokevirtual} and requires a dispatch
 * through a {@link java.lang.invoke.MethodHandle}.</p>
 */
public class BaseWithProtected {

    protected String protectedEcho(String value) {
        return "base:" + value;
    }

    protected int protectedSum(int a, int b) {
        return a + b;
    }

    public String publicGreeting(String name) {
        return "hello " + name;
    }
}
