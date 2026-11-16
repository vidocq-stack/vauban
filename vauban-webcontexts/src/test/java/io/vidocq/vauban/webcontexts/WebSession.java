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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The web container's own session object, as an {@code HttpSession} is: it holds the attributes, it is not
 * {@code Serializable}, and a {@code @SessionScoped} producer hands it out as a bean ({@link SessionProducers}).
 */
public class WebSession {

    final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private final String id;

    /** For the client proxy, which calls a no-arg constructor of its superclass. */
    public WebSession() {
        this("proxy");
    }

    WebSession(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
