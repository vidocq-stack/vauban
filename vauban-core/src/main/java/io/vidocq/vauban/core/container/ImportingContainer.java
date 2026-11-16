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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.api.Passivation;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import java.io.InvalidObjectException;

/**
 * What the serial forms of the container read back through: the container that imports a session moved from another
 * one ({@link Passivation#importInto}, foy#114), and its beans. Each lookup that finds nothing throws
 * {@link InvalidObjectException} with a message that names the cause, never returns {@code null}: the web container
 * drops the session either way.
 */
final class ImportingContainer {

    private ImportingContainer() {
    }

    /** The container bound by the {@link Passivation#importInto} running on this thread. */
    static BeanManager beanManager() throws InvalidObjectException {
        return Passivation.importingBeanManager()
                .orElseThrow(() -> new InvalidObjectException("no importing container bound"));
    }

    /** The bean of {@code beanManager} with the passivation id {@code passivationId}. */
    // A bean found by its id, as BeanManager.getPassivationCapableBean gives it: Bean<?> is its only type.
    @SuppressWarnings("java:S1452")
    static Bean<?> bean(BeanManager beanManager, String passivationId) throws InvalidObjectException {
        Bean<?> bean = beanManager.getPassivationCapableBean(passivationId);
        if (bean == null) {
            throw new InvalidObjectException("no bean " + passivationId
                    + " in the importing container: it was removed, renamed or vetoed");
        }
        return bean;
    }
}
