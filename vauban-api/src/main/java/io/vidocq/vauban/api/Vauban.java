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
package io.vidocq.vauban.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Entry point marker for the Vauban CDI container.
 */
public final class Vauban {

    /**
     * Implementation version, filtered by the Maven build into a same-module
     * resource. Not a compile-time constant on purpose: consumers always read
     * the version of the artifact actually on their module path.
     */
    public static final String VERSION = loadVersion();

    private static final String UNKNOWN_VERSION = "unknown";

    private Vauban() {}

    private static String loadVersion() {
        try (InputStream in = Vauban.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return UNKNOWN_VERSION;
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", UNKNOWN_VERSION);
        } catch (IOException _) {
            return UNKNOWN_VERSION;
        }
    }
}
