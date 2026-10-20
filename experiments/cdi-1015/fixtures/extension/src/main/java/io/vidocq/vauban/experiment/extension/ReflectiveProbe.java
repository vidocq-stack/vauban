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
package io.vidocq.vauban.experiment.extension;

/**
 * Models an extension's existing reflection code in a separate named module.
 * This access probe does not implement or exercise a CDI extension lifecycle.
 */
public final class ReflectiveProbe {

    private ReflectiveProbe() {
    }

    /** Checks deep reflection from this module with a fresh Method on each call. */
    public static boolean canAccess(Class<?> beanClass) throws NoSuchMethodException {
        return beanClass.getDeclaredMethod("privateRecovery", String.class).trySetAccessible();
    }

    /** Calls the bean's private fallback through the legacy reflection API. */
    public static String invoke(Class<?> beanClass, Object bean, String name)
            throws ReflectiveOperationException {
        var method = beanClass.getDeclaredMethod("privateRecovery", String.class);
        if (!method.trySetAccessible()) {
            throw new IllegalAccessException(beanClass.getModule().getName() + "/"
                    + beanClass.getPackageName() + " is not open to "
                    + ReflectiveProbe.class.getModule().getName());
        }
        return (String) method.invoke(bean, name);
    }
}
