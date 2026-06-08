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
package io.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.invoke.InvokerBuilder;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds a {@link VaubanInvoker} from a resolved Method.
 */
public final class VaubanInvokerBuilder implements InvokerBuilder<InvokerInfo> {

    private final Method method;
    private final Class<?> beanClass;
    private boolean instanceLookup;
    private final Set<Integer> argumentLookups = new HashSet<>();

    public VaubanInvokerBuilder(Method method, Class<?> beanClass) {
        this.method = method;
        this.beanClass = beanClass;
    }

    public Method getMethod() {
        return method;
    }

    public Set<Integer> getArgumentLookups() {
        return Set.copyOf(argumentLookups);
    }

    @Override
    public InvokerBuilder<InvokerInfo> withInstanceLookup() {
        this.instanceLookup = true;
        return this;
    }

    @Override
    public InvokerBuilder<InvokerInfo> withArgumentLookup(int position) {
        if (position < 0 || position >= method.getParameterCount()) {
            throw new IllegalArgumentException(
                    "Argument position " + position + " is out of bounds for method "
                            + method.getName() + " with " + method.getParameterCount() + " parameters");
        }
        argumentLookups.add(position);
        return this;
    }

    @Override
    public InvokerInfo build() {
        return new VaubanInvoker(method, beanClass, instanceLookup, argumentLookups);
    }
}
