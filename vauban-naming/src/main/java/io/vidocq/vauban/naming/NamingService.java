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
package io.vidocq.vauban.naming;

import javax.naming.NamingException;

/**
 * Resolves the names of the {@code java:} namespace: the CDI bean every JNDI operation of
 * {@link CdiInitialContextFactory}'s contexts delegates to, resolved in the running container at each call.
 *
 * <p>The default bean serves {@code java:comp/env} and its aliases {@code java:module/env} and
 * {@code java:app/env} (an application is a single component) from the {@link JndiNameProvider} beans. An
 * application replaces it with an {@code @Alternative @Priority} bean of this type.
 */
public interface NamingService {

    /**
     * Whether a name is a context rather than an object: {@code java:comp/env} is one.
     *
     * @param name an absolute name, such as {@code java:comp/env}
     * @return {@code true} when a lookup of the name returns a context
     */
    boolean isContext(String name);

    /**
     * The object a name is bound to.
     *
     * @param name an absolute name, such as {@code java:comp/env/jdbc/orders}
     * @return the object, never {@code null}
     * @throws javax.naming.NameNotFoundException when nothing answers the name
     * @throws NamingException                    when the name cannot be resolved
     */
    Object lookup(String name) throws NamingException;
}
