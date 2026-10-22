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

/**
 * Declares {@code java:} names: the application, a library or the runtime assembling them binds each name to a bean
 * or to another name. Vauban declares none by itself.
 *
 * <p>The default {@link NamingService} asks every bean of this type once per container, at the first lookup or when
 * the container starts, and checks the declarations when it starts: a wrong one fails the start. A
 * {@code @Dependent} bean of this type is destroyed once it has bound its names.
 *
 * <pre>{@code
 * @ApplicationScoped
 * class OrdersNames implements JndiBindings {
 *     public void bind(Binder binder) {
 *         binder.bind("java:comp/env/jdbc/orders", JndiTarget.bean(DataSource.class, NamedLiteral.of("orders")))
 *               .bind("java:app/env/jdbc/orders", JndiTarget.link("java:comp/env/jdbc/orders"));
 *     }
 * }
 * }</pre>
 */
public interface JndiBindings {

    /**
     * Binds this bean's names.
     *
     * @param binder where the names go
     */
    void bind(Binder binder);
}
