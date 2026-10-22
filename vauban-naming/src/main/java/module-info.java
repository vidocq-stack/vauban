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
/**
 * A read-only JNDI {@code java:} namespace backed by CDI: the names {@code JndiBindings} beans declare, such as
 * {@code java:comp/env/jdbc/orders} bound to the {@code @Named("orders")} {@code DataSource} bean, answered from the
 * running container.
 */
module io.vidocq.vauban.naming {
    requires transitive java.naming;
    requires transitive jakarta.cdi;
    // The VaubanComponentProvider service type of the generated _VaubanComponents, and the
    // AnnotationInstanceFactory that builds the qualifiers of a bean written as text.
    requires io.vidocq.vauban.api;

    exports io.vidocq.vauban.naming;

    // NamingManager finds the factory named by java.naming.factory.initial through ServiceLoader: no reflection.
    provides javax.naming.spi.InitialContextFactory
            with io.vidocq.vauban.naming.CdiInitialContextFactory;

    // In-module instantiation and injection of this module's beans: no `opens` to the container.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.vauban.naming._VaubanComponents;
}
