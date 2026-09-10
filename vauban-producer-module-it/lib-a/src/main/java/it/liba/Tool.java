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
package it.liba;

/**
 * An interface produced type. A normal-scoped producer of an interface cannot be subclassed, so
 * historically the container fell back to a runtime {@code java.lang.reflect.Proxy}. Issue #42
 * (interface producer static proxy) generates a build-time class {@code implements Tool} instead,
 * forwarding every method — including {@code default} ones — to the contextual instance, so Vauban
 * keeps zero runtime reflection and zero dynamic proxies.
 */
public interface Tool {

    String use();

    default String describe() {
        return "tool:" + use();
    }
}
