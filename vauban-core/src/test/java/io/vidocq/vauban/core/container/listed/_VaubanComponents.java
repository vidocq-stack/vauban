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
package io.vidocq.vauban.core.container.listed;

import io.vidocq.vauban.api.VaubanComponentProvider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The provider a test compilation of a module without main beans emits: no {@code provides} clause names it, and no
 * services file lists it here (BUG-20261119-72). Counts what it builds.
 */
@SuppressWarnings("java:S101") // the name the processor gives a generated provider
public final class _VaubanComponents implements VaubanComponentProvider {

    /** How many {@link ListedBean}s this provider built. */
    public static final AtomicInteger CREATED = new AtomicInteger();

    @Override
    public Object create(String className) {
        if (!ListedBean.class.getName().equals(className)) {
            return null;
        }
        CREATED.incrementAndGet();
        return new ListedBean();
    }
}
