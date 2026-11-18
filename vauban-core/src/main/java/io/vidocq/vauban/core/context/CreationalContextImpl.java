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
package io.vidocq.vauban.core.context;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class CreationalContextImpl<T> implements CreationalContext<T> {
    private final List<Object> incompleteInstances = new ArrayList<>();
    private final List<DependentInstance> dependentInstances = new ArrayList<>();
    private final Map<String, Object> interceptorInstances = new HashMap<>();
    /**
     * The intercepted bean whose one instance this context creates, its interceptors' {@code @Intercepted Bean}
     * (BUG-20261117-47); {@code null} for a context of any other use.
     */
    private final Contextual<T> interceptedBean;
    /** The context of each intercepted instance created with this one, by instance (BUG-20261118-36). */
    private final Map<Object, CreationalContextImpl<?>> intercepted = new IdentityHashMap<>();

    public CreationalContextImpl() {
        this(null);
    }

    private CreationalContextImpl(Contextual<T> interceptedBean) {
        this.interceptedBean = interceptedBean;
    }

    /**
     * A context of its own for one instance of {@code bean}, an intercepted bean: its interceptor instances are
     * dependent objects of that instance alone (CDI 4.1, 6.4.1), and inject {@code bean} as their
     * {@code @Intercepted Bean}. Two intercepted instances created with one context shared its interceptor instances,
     * held by interceptor class (BUG-20261118-36). The caller hands the instance created to {@link #adopt}.
     */
    public static <X> CreationalContextImpl<X> forInterceptedInstanceOf(Contextual<X> bean) {
        return new CreationalContextImpl<>(bean);
    }

    /** Keeps {@code context}, the context of the intercepted {@code instance}, until it is destroyed or released. */
    public void adopt(Object instance, CreationalContextImpl<?> context) {
        intercepted.put(instance, context);
    }

    /** The context {@link #adopt} keeps for {@code instance}, no longer kept, or {@code null}. */
    @SuppressWarnings("unchecked")
    public <X> CreationalContextImpl<X> release(X instance) {
        return (CreationalContextImpl<X>) intercepted.remove(instance);
    }

    /** The intercepted bean this context creates an instance of, or {@code null}. */
    public Contextual<T> interceptedBean() {
        return interceptedBean;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void push(T incompleteInstance) {
        incompleteInstances.add(incompleteInstance);
    }
    
    public void pushInterceptor(Object instance) {
        if (!incompleteInstances.contains(instance)) {
            incompleteInstances.add(instance);
        }
    }

    public void addInterceptorInstance(String className, Object instance) {
        interceptorInstances.put(className, instance);
    }

    @SuppressWarnings("unchecked")
    public <X> X getInterceptorInstance(String className) {
        return (X) interceptorInstances.get(className);
    }

    public boolean isRegistered(Object instance) {
        for (var inst : incompleteInstances) {
            if (inst == instance) return true;
        }
        for (var dep : dependentInstances) {
            if (dep.instance == instance) return true;
        }
        return false;
    }

    private volatile boolean releasing = false;

    @Override
    public void release() {
        if (releasing) return; // Guard against recursive release
        releasing = true;
        try {
            // CDI spec: release() must destroy all dependent objects
            while (!dependentInstances.isEmpty()) {
                var dep = dependentInstances.remove(dependentInstances.size() - 1);
                dep.destroy();
            }
            // The contexts of intercepted instances no dependent destruction took (BUG-20261118-36)
            var left = List.copyOf(intercepted.values());
            intercepted.clear();
            for (CreationalContextImpl<?> context : left) {
                context.release();
            }
            incompleteInstances.clear();
        } finally {
            releasing = false;
        }
    }

    /**
     * Register a dependent instance for cleanup on release().
     */
    public void addDependentInstance(Contextual<?> contextual, Object instance, CreationalContext<?> ctx) {
        dependentInstances.add(new DependentInstance(contextual, instance, ctx));
    }

    public List<Object> getIncompleteInstances() {
        return incompleteInstances;
    }

    public List<DependentInstance> getDependentInstances() {
        return dependentInstances;
    }

    public static record DependentInstance(Contextual<?> contextual, Object instance, CreationalContext<?> ctx) {
        @SuppressWarnings("unchecked")
        void destroy() {
            try {
                ((Contextual<Object>) contextual).destroy(instance, (CreationalContext<Object>) ctx);
            } catch (Exception e) {
                System.Logger logger = System.getLogger(DependentInstance.class.getName());
                logger.log(System.Logger.Level.WARNING, "Exception destroying dependent instance", e);
            }
        }
    }
}
