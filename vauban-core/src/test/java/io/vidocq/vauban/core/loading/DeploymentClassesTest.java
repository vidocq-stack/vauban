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
package io.vidocq.vauban.core.loading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("DeploymentClasses: loading a class the index names")
class DeploymentClassesTest {

    @Test
    @DisplayName("a class only the thread context class loader defines is found through it")
    void throughTheContextClassLoader() throws ClassNotFoundException {
        var loader = IsolatedLoader.ofClass("io.vidocq.vauban.core.loading.isolated.OnlyHere");
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            assertSame(loader.loadClass(loader.name()), DeploymentClasses.load(loader.name()));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("without a thread context class loader, deployment classes load through the caller's loader")
    void noContextClassLoader() throws ClassNotFoundException {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertEquals(String.class, DeploymentClasses.load("java.lang.String"));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
