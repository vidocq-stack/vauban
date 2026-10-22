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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import java.util.Optional;

/** A {@code @Dependent} provider: the naming service destroys each instance it creates once it has answered. */
@Dependent
public class DependentQueueNames implements JndiNameProvider {

    static final String AUDIT_QUEUE = "the audit queue";

    static int created;

    static int destroyed;

    @PostConstruct
    void create() {
        created++;
    }

    @Override
    public Optional<Object> lookup(JndiName name) {
        return "java:comp/env/jms/audit".equals(name.toString()) ? Optional.of(AUDIT_QUEUE) : Optional.empty();
    }

    @PreDestroy
    void destroy() {
        destroyed++;
    }
}
