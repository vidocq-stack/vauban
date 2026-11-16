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
import java.util.List;

/** Adapts a {@link WebSession}, as a web container adapts its {@code HttpSession}, and recognises it. */
final class WebSessionStore implements SessionStore {

    private final WebSession session;

    WebSessionStore(WebSession session) {
        this.session = session;
    }

    WebSession session() {
        return session;
    }

    @Override
    public Object getAttribute(String name) {
        return session.attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        session.attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        session.attributes.remove(name);
    }

    @Override
    public Collection<String> attributeNames() {
        return List.copyOf(session.attributes.keySet());
    }

    @Override
    public Object mutex() {
        return session;
    }

    @Override
    public boolean isSession(Object instance) {
        return instance == session;
    }
}
