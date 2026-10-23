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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import java.util.stream.Stream;
import javax.naming.NamingException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A lookup checks its target again, as the start does: a service used without the start's validation (built by
 * hand, here) still refuses a loop of links, a target no bean or several beans match, and a {@code @Dependent} one.
 */
class NamingServiceLookupGuardTest {

    private static final String ORDERS = "java:comp/env/jdbc/orders";
    private static final String ANY = "java:comp/env/jdbc/any";
    private static final String SCRATCH = "java:comp/env/jdbc/scratch";

    static Stream<Arguments> unresolvableTargets() {
        return Stream.of(
                Arguments.of(new Class<?>[] {LoopNames.class}, "java:comp/env/a",
                        "The links of java:comp/env/a loop: [java:comp/env/a, java:comp/env/b]"),
                Arguments.of(new Class<?>[] {OrdersNames.class}, ORDERS,
                        ORDERS + " is bound to javax.sql.DataSource @Named(\"orders\"), which no bean matches"
                                + " in the running container"),
                Arguments.of(new Class<?>[] {AnyDataSourceNames.class, OrdersDataSource.class, DefaultDataSource.class},
                        ANY, ANY + " is bound to javax.sql.DataSource @Any, which several beans match in the running"
                                + " container"),
                Arguments.of(new Class<?>[] {ScratchNames.class, DependentDataSource.class}, SCRATCH,
                        SCRATCH + " is bound to javax.sql.DataSource @Named(\"scratch\"), a @Dependent bean: a lookup"
                                + " would create an instance that nobody destroys. Give the bean a scope, such as"
                                + " @ApplicationScoped or @Singleton"));
    }

    @ParameterizedTest
    @MethodSource("unresolvableTargets")
    void aLookupRefusesATargetItCannotResolve(Class<?>[] beans, String name, String reason) {
        var builder = VaubanContainer.builder();
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        try (var _ = builder.build()) {
            var service = new DefaultNamingService(CDI.current().select(JndiBindings.class, Any.Literal.INSTANCE),
                    CDI.current().select(JndiNameProvider.class, Any.Literal.INSTANCE));

            var failure = assertThrows(NamingException.class, () -> service.lookup(name));

            assertEquals(reason, failure.getMessage());
        }
    }
}
