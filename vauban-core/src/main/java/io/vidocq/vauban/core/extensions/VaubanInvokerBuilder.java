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
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds an invoker of a method: a {@link VaubanInvoker} when its class is loaded, an invoker described by names
 * ({@link InvokerTarget.Described}) when the build compiles it and it has no class file yet (BUG-20261117-48).
 */
public final class VaubanInvokerBuilder implements InvokerBuilder<InvokerInfo> {

    private final InvokerTarget target;
    private final MethodInfo methodInfo;
    private final Method method;
    private final Class<?> beanClass;
    private final InvokerShape shape;
    private boolean instanceLookup;
    private final Set<Integer> argumentLookups = new HashSet<>();

    /**
     * @param method    the method, {@code null} when its class has no class file yet
     * @param beanClass the bean class, {@code null} when it has no class file yet
     * @param shape     how a class the build generates calls the method (BUG-20261118-37)
     */
    VaubanInvokerBuilder(InvokerTarget target, MethodInfo methodInfo, Method method, Class<?> beanClass,
                         InvokerShape shape) {
        this.target = target;
        this.shape = shape;
        this.methodInfo = methodInfo;
        this.method = method;
        this.beanClass = beanClass;
    }

    /** The method, as the language model gives it: what the build and the start check the lookups against. */
    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    /** The method the invoker calls, by names. */
    public InvokerTarget getTarget() {
        return target;
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
        var parameterCount = methodInfo.parameters().size();
        if (position < 0 || position >= parameterCount) {
            throw new IllegalArgumentException(
                    "Argument position " + position + " is out of bounds for method "
                            + methodInfo.name() + " with " + parameterCount + " parameters");
        }
        argumentLookups.add(position);
        return this;
    }

    @Override
    public InvokerInfo build() {
        if (method == null) {
            return new InvokerTarget.Described(target, instanceLookup, argumentLookups, shape);
        }
        return new VaubanInvoker(method, beanClass, instanceLookup, argumentLookups, shape);
    }
}
