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

/**
 * Loads a class of the deployment by the name the bean index gives it, the way discovery has always
 * done: through the thread context class loader when there is one, without initializing the class,
 * and through {@link Class#forName(String)} otherwise.
 *
 * <p>Every name passed here comes from the deployment itself (a class, an annotation type or a type
 * argument the index recorded), never from input the application receives at run time. Discovery
 * reads the class only when the index does not describe what it needs, the reflective fallback the
 * AOT section of the internals page documents.
 */
public final class DeploymentClasses {

    private DeploymentClasses() {
    }

    /**
     * The class the index names.
     *
     * @throws ClassNotFoundException when no loader visible from here defines it
     */
    public static Class<?> load(String name) throws ClassNotFoundException {
        return load(name, null);
    }

    /**
     * The class the index names, through {@code loader} — the container's bean class loader, once the build has
     * restored the caller's context loader — or, when it is {@code null}, the way {@link #load(String)} does.
     *
     * @throws ClassNotFoundException when no loader visible from here defines it
     */
    public static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException {
        var effective = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        return effective != null ? Class.forName(name, false, effective) : Class.forName(name);
    }
}
