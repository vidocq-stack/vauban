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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.CDI;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What the naming service answers for a name it cannot parse, and for a name no provider answers. */
class NamingServiceUnknownNameTest {

    private final VaubanContainer container = VaubanContainer.builder()
            .addBeanClass(DefaultNamingService.class)
            .addBeanClass(OrdersNames.class)
            .addBeanClass(OrdersDataSource.class)
            .addBeanClass(QueueNames.class)
            .build();

    @AfterEach
    void stop() {
        container.close();
    }

    private static NamingService service() {
        return CDI.current().select(NamingService.class).get();
    }

    @Test
    void aNameOutsideTheNamespacesIsNoContext() {
        assertFalse(service().isContext("jdbc/orders"));
    }

    @Test
    void lookingUpANameOutsideTheNamespacesFindsNothingAndKeepsTheReason() {
        var service = service();

        var failure = assertThrows(NameNotFoundException.class, () -> service.lookup("jdbc/orders"));

        assertEquals("jdbc/orders is outside java:comp, java:module, java:app and java:global", failure.getMessage());
        assertInstanceOf(InvalidNameException.class, failure.getRootCause());
    }

    @Test
    void aNameNeitherDeclaredNorProvidedIsBoundToNothing() {
        var service = service();

        var failure = assertThrows(NameNotFoundException.class, () -> service.lookup("java:comp/env/jms/invoices"));

        assertEquals("java:comp/env/jms/invoices is bound to nothing in the running container", failure.getMessage());
    }
}
