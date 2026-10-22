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

import java.util.Optional;
import javax.naming.NamingException;

/**
 * Contributes names to {@code java:comp/env}: a library publishes its resources by declaring a bean of
 * this type, such as a messaging library answering {@code jms/orders}. This module's own provider answers
 * {@code jdbc/<name>} with the {@code @Named("<name>")} {@code DataSource} bean.
 *
 * <p>The default {@link NamingService} asks every provider bean at each lookup, and a name two providers
 * answer is ambiguous. Give the bean a scope, such as {@code @ApplicationScoped}: a {@code @Dependent}
 * provider is created and destroyed at each lookup.
 */
public interface JndiNameProvider {

    /**
     * The object a name of {@code java:comp/env} is bound to, if this provider knows it.
     *
     * @param name the name relative to {@code java:comp/env}, such as {@code jms/orders}
     * @return the object, or empty when this provider does not answer the name
     * @throws NamingException when the provider knows the name but cannot resolve it
     */
    Optional<Object> lookup(String name) throws NamingException;
}
