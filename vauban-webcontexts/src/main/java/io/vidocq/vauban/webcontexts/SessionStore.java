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
package io.vidocq.vauban.webcontexts;

import java.util.Collection;

/**
 * Where a session keeps its {@code @SessionScoped} instances: the attributes of the web
 * container's session. The web container adapts its own session, typically
 * {@code HttpSession}, and may create that session on the first {@link #setAttribute}.
 */
public interface SessionStore {

    Object getAttribute(String name);

    void setAttribute(String name, Object value);

    void removeAttribute(String name);

    Collection<String> attributeNames();

    /**
     * The object to lock while an instance is created, so that two concurrent requests of one
     * session create it once. The same object for every store adapting the same session.
     */
    default Object mutex() {
        return this;
    }
}
