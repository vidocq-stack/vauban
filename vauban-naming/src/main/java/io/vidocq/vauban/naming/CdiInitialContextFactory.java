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

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.spi.InitialContextFactory;

/**
 * The initial context factory of the {@code java:} namespace. Its contexts hold no state and no
 * reference to a container: each operation resolves the {@link NamingService} bean of the container
 * running at that moment, through {@code CDI.current()}, so a context outlives a container restart.
 *
 * <p>{@link JavaNaming#installAsDefault} makes it the JVM default. An application may also name it in
 * {@link Context#INITIAL_CONTEXT_FACTORY}; the JDK finds it through {@code ServiceLoader}.
 */
public final class CdiInitialContextFactory implements InitialContextFactory {

    /** Created by the JDK's naming manager. */
    public CdiInitialContextFactory() {
        // stateless
    }

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) {
        return new CdiContext(environment, "");
    }
}
