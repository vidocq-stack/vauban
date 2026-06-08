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

import io.vidocq.vauban.api.VaubanComponentProvider;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test {@link VaubanComponentProvider} registered as a service. It owns only
 * {@link ProvidedBean}: it records the call and returns a fresh instance for it, and
 * {@code null} for everything else (so other tests fall back to reflection unaffected).
 */
public class CountingComponentProvider implements VaubanComponentProvider {

    /** Class names this provider was asked to (and did) instantiate. */
    public static final Set<String> CREATED = ConcurrentHashMap.newKeySet();

    @Override
    public Object create(String className) {
        if (ProvidedBean.class.getName().equals(className)) {
            CREATED.add(className);
            return new ProvidedBean();
        }
        return null;
    }

    @Override
    public Object create(String className, Object[] args) {
        if (ProvidedConstructorBean.class.getName().equals(className)) {
            CREATED.add(className);
            return new ProvidedConstructorBean((ProvidedDependency) args[0]);
        }
        return create(className);
    }
}
