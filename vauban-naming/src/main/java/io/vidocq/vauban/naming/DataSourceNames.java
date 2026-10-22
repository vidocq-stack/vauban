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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;
import java.util.Optional;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * {@code jdbc/<name>} is the {@code @Named("<name>")} {@link DataSource} bean. A {@code @Dependent} one is
 * refused: the lookup would create an instance that nobody destroys.
 */
@ApplicationScoped
class DataSourceNames implements JndiNameProvider {

    private static final String PREFIX = "jdbc/";

    private final Instance<DataSource> dataSources;

    @Inject
    DataSourceNames(@Any Instance<DataSource> dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public Optional<Object> lookup(String name) throws NamingException {
        if (!name.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String beanName = name.substring(PREFIX.length());
        Instance<DataSource> named = dataSources.select(NamedLiteral.of(beanName));
        if (named.isUnsatisfied()) {
            return Optional.empty();
        }
        var handle = named.getHandle();
        if (handle.getBean().getScope() == Dependent.class) {
            throw new NamingException("java:comp/env/" + name + " names a @Dependent DataSource bean, @Named(\""
                    + beanName + "\"): a lookup would create an instance that nobody destroys. Give the bean a "
                    + "scope, such as @ApplicationScoped or @Singleton");
        }
        return Optional.of(handle.get());
    }
}
