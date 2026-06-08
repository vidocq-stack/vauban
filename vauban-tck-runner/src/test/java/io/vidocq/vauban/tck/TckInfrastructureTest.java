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
package io.vidocq.vauban.tck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TCK Infrastructure - infrastructure verification")
class TckInfrastructureTest {

    @Test
    @DisplayName("VaubanBeans SPI is functional")
    void beansSpiShouldWork() {
        var beans = new VaubanBeans();
        assertFalse(beans.isProxy(new Object()));
    }

    @Test
    @DisplayName("VaubanContexts SPI is functional")
    void contextsSpiShouldWork() {
        var contexts = new VaubanContexts();
        assertNotNull(contexts.getDependentContext());
    }

    @Test
    @DisplayName("VaubanContextuals SPI is functional")
    void contextualsSpiShouldWork() {
        var contextuals = new VaubanContextuals();
        var inspectable = contextuals.create("test", new io.vidocq.vauban.core.context.DependentContext());
        assertNotNull(inspectable);
        assertNull(inspectable.getCreationalContextPassedToCreate());
    }

    @Test
    @DisplayName("VaubanCreationalContexts SPI is functional")
    void creationalContextsSpiShouldWork() {
        var ccs = new VaubanCreationalContexts();
        var inspectable = ccs.create(null);
        assertNotNull(inspectable);
        assertFalse(inspectable.isPushCalled());
        inspectable.push(new Object());
        assertTrue(inspectable.isPushCalled());
    }
}
