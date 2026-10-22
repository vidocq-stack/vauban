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

/**
 * The four namespaces of {@code java:} the Jakarta EE Platform defines (EE.5.2.2). They are distinct: a name
 * exists only in the namespace it is declared in.
 */
public enum Namespace {

    /** {@code java:comp}: the component's own names. */
    COMP("java:comp"),
    /** {@code java:module}: the names the components of a module share. */
    MODULE("java:module"),
    /** {@code java:app}: the names the modules of an application share. */
    APP("java:app"),
    /** {@code java:global}: the names every application of the JVM shares. */
    GLOBAL("java:global");

    private final String prefix;

    Namespace(String prefix) {
        this.prefix = prefix;
    }

    /**
     * The namespace's own name.
     *
     * @return {@code java:comp}, {@code java:module}, {@code java:app} or {@code java:global}
     */
    public String prefix() {
        return prefix;
    }
}
