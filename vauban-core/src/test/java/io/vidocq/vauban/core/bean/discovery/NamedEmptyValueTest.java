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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @Named("")} is {@code @Named} with its default value: on a bean class it names the bean after the class, on
 * an injection point field after the field (BUG-20261118-27). An extension that rebuilds a {@code @Named} from the
 * members the language model shows writes {@code value = ""} since BUG-20261117-55.
 */
@DisplayName("BUG-20261118-27 - @Named(\"\") takes the default name")
class NamedEmptyValueTest {

    /** A bean named after its class: {@code service}. */
    @Named
    @Dependent
    public static class Service {
    }

    /** A bean that writes the default value out: named {@code gadget}. */
    @Named("")
    @Dependent
    public static class Gadget {
    }

    /** An injection point that writes the default value out: it asks for the bean named {@code service}. */
    @Dependent
    public static class Client {
        @Inject
        @Named("")
        Service service;
    }

    @Test
    @DisplayName("a bean class annotated @Named(\"\") is named after its class")
    void aBeanClassAnnotatedNamedEmptyIsNamedAfterItsClass() {
        try (var container = VaubanContainer.builder().addBeanClass(Gadget.class).build()) {
            assertEquals(1, container.getBeanManager().getBeans(Gadget.class, NamedLiteral.of("gadget")).size());
        }
    }

    @Test
    @DisplayName("an injection point field annotated @Named(\"\") asks for the bean named after the field")
    void anInjectionPointFieldAnnotatedNamedEmptyAsksForTheFieldName() {
        try (var container = VaubanContainer.builder().addBeanClass(Service.class).addBeanClass(Client.class)
                .build()) {
            assertNotNull(container.select(Client.class).service);
        }
    }
}
