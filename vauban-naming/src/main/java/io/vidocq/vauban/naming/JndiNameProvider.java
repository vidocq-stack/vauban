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
 * Answers names not known in advance, at each lookup: a messaging library that discovers its queues while running.
 * Names known when the container starts are better declared with {@link JndiBindings}, which are checked then.
 *
 * <p>The default {@link NamingService} asks the providers only for a name no {@link JndiBindings} bean declares, and
 * a name two providers answer is ambiguous. Give the bean a scope, such as {@code @ApplicationScoped}: a
 * {@code @Dependent} provider is created and destroyed at each lookup.
 */
public interface JndiNameProvider {

    /**
     * The object a name is bound to, if this provider knows it.
     *
     * @param name an absolute name, in any of the four namespaces
     * @return the object, or empty when this provider does not answer the name
     * @throws NamingException when the provider knows the name but cannot resolve it
     */
    Optional<Object> lookup(JndiName name) throws NamingException;

    /**
     * Whether a name is a context of this provider's names, such as {@code java:comp/env/jms}, so that relative
     * names resolve in it.
     *
     * @param name an absolute name
     * @return {@code true} when a lookup of the name returns a context; {@code false} by default
     */
    default boolean isContext(JndiName name) {
        return false;
    }
}
