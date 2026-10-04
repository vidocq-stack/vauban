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
package io.vidocq.vauban.core.interceptor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which declaration a business method call runs, and so which method-level interceptor bindings
 * apply to it. The invocation context ({@code getMethod()}, {@code getInterceptorBindings()}) and
 * the interceptor chain ({@link InterceptorManager}) both ask here, so they cannot disagree.
 *
 * <p>The declaration is the one a call on an instance of the bean class reaches: the most derived
 * declaration up the bean's superclass chain, whatever its access, else the interface default
 * method the bean inherits. Its own annotations are the method-level bindings, which is CDI 4.1
 * §4.2: a binding declared on an inherited method applies only while no class below overrides the
 * method. A non-overridden interface default method is a member of the bean class in the same way
 * (JLS 8.4.8), so its bindings apply too, as with Weld; the class-level bindings of an interface
 * never do (CDI 4.1 §4: type-level metadata is not inherited from interfaces).</p>
 */
final class BusinessMethods {

    private BusinessMethods() {}

    /**
     * The method each {@code $$super$<name>} bridge stands for, one map per generated subclass.
     * The walk in {@link #declarationOf} runs once per bridge instead of on every call — a method
     * inherited from far up would otherwise throw a {@link NoSuchMethodException} per level each
     * time. A {@link ClassValue} ties each map to its subclass, so the cache never keeps a
     * discarded application layer alive.
     */
    private static final ClassValue<Map<Method, Method>> ORIGINAL_METHODS = new ClassValue<>() {
        @Override
        protected Map<Method, Method> computeValue(Class<?> generatedSubclass) {
            return new ConcurrentHashMap<>();
        }
    };

    /** {@code true} for a {@code $$super$<name>} bridge of a generated {@code $$Intercepted}. */
    static boolean isBridge(Method method) {
        return method.getName().startsWith(InterceptedShape.SUPER_BRIDGE_PREFIX);
    }

    /** The business method name a method or a bridge stands for. */
    static String businessName(Method method) {
        return isBridge(method)
                ? method.getName().substring(InterceptedShape.SUPER_BRIDGE_PREFIX.length())
                : method.getName();
    }

    /**
     * The declaration a {@code $$super$} bridge stands for: the one {@code super.<name>(…)} reaches
     * from the generated subclass, whose superclass is the bean class (BUG-20261004-01). The bridge
     * itself when nothing declares it. Resolved here rather than captured by the generators: naming
     * the declaring class in the generated subclass fails when that class is not accessible from
     * the bean's package or module, and the subclasses already compiled into other modules only hand
     * over the bridge.
     */
    static Method originalOf(Method bridge) {
        return ORIGINAL_METHODS.get(bridge.getDeclaringClass()).computeIfAbsent(bridge, b -> {
            var declaration = declarationOf(b.getDeclaringClass().getSuperclass(), b);
            return declaration != null ? declaration : b;
        });
    }

    /**
     * The declaration a call of {@code method} — or of the business method a bridge stands for — runs
     * on an instance of {@code beanClass}: the most derived one up the superclass chain, whatever its
     * access, else the interface default method the bean inherits; {@code null} when there is none.
     */
    static Method declarationOf(Class<?> beanClass, Method method) {
        var name = businessName(method);
        var parameterTypes = method.getParameterTypes();
        for (Class<?> c = beanClass; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException declaredHigherUp) {
                // keep walking
            }
        }
        try {
            // No class declares it: the most specific interface method the bean inherits, which for
            // a concrete bean class is a default method.
            return beanClass.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException noSuchMethod) {
            return null;
        }
    }
}
