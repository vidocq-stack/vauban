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

import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The request context. Its state is bound to a scope ({@link #runInScope}, a {@link ScopedValue}),
 * or, when activated imperatively ({@link #activate} / {@link #deactivate}, as the built-in
 * {@code RequestContextController} does for a servlet listener), to the current thread: two
 * threads activating it each have their own request (Vidocq/vauban#147).
 *
 * <p>Activation fires {@code @Initialized(RequestScoped.class)}; deactivation fires
 * {@code @BeforeDestroyed}, destroys the instances, then fires {@code @Destroyed}
 * (CDI 4.1 §6.7.1).</p>
 */
public final class RequestContext implements AlterableContext {

    private record ContextualInstance(Object instance, CreationalContext<?> ctx) {}

    static final class RequestContextState {
        final Map<Contextual<?>, ContextualInstance> instances = new HashMap<>();
        boolean active = false;
    }

    private static final ScopedValue<RequestContextState> STATE = ScopedValue.newInstance();

    /** The state of an imperative activation, per thread. Removed on deactivation. */
    private final ThreadLocal<RequestContextState> threadState = new ThreadLocal<>();

    /** Fires a context event with the given qualifier; set by the container, a no-op until then. */
    private volatile Consumer<Annotation> lifecycleEvents = qualifier -> { };

    /** Gives the context the way to fire its {@code @Initialized} / {@code @BeforeDestroyed} / {@code @Destroyed} events. */
    public void lifecycleEvents(Consumer<Annotation> events) {
        this.lifecycleEvents = events;
    }

    /** The current state, or {@code null} when neither a scope nor this thread activated the context. */
    private RequestContextState currentState() {
        return STATE.isBound() ? STATE.get() : threadState.get();
    }

    private RequestContextState activeState() {
        var state = currentState();
        if (state == null || !state.active) {
            throw new ContextNotActiveException("RequestScope is not active");
        }
        return state;
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return RequestScoped.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        var state = activeState();
        var ci = state.instances.get(contextual);
        if (ci != null) {
            return (T) ci.instance();
        }
        if (creationalContext == null) {
            return null;
        }
        var instance = contextual.create(creationalContext);
        state.instances.put(contextual, new ContextualInstance(instance, creationalContext));
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual) {
        var ci = activeState().instances.get(contextual);
        return ci != null ? (T) ci.instance() : null;
    }

    @Override
    public boolean isActive() {
        var state = currentState();
        return state != null && state.active;
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        var state = currentState();
        if (state != null) {
            destroy(state, contextual);
        }
    }

    @SuppressWarnings("unchecked")
    private static void destroy(RequestContextState state, Contextual<?> contextual) {
        var ci = state.instances.remove(contextual);
        if (ci != null) {
            ((Contextual<Object>) contextual).destroy(ci.instance(), (CreationalContext<Object>) ci.ctx());
            ci.ctx().release();
        }
    }

    /** Activates the context for the current scope, or else for the current thread. */
    public void activate() {
        RequestContextState state;
        if (STATE.isBound()) {
            state = STATE.get();
        } else {
            state = threadState.get();
            if (state == null) {
                state = new RequestContextState();
                threadState.set(state);
            }
        }
        state.active = true;
        state.instances.clear();
        lifecycleEvents.accept(Initialized.Literal.REQUEST);
    }

    /** Destroys the instances of the current request and deactivates the context. */
    public void deactivate() {
        var state = currentState();
        if (state == null) {
            return;
        }
        try {
            if (state.active) {
                lifecycleEvents.accept(BeforeDestroyed.Literal.REQUEST);
            }
            for (var contextual : new HashMap<>(state.instances).keySet()) {
                destroy(state, contextual);
            }
        } finally {
            boolean wasActive = state.active;
            state.instances.clear();
            state.active = false;
            if (!STATE.isBound()) {
                threadState.remove();
            }
            if (wasActive) {
                lifecycleEvents.accept(Destroyed.Literal.REQUEST);
            }
        }
    }

    public void runInScope(Runnable action) {
        ScopedValue.where(STATE, new RequestContextState()).run(() -> {
            activate();
            try {
                action.run();
            } finally {
                deactivate();
            }
        });
    }
}
