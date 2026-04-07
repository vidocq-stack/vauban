package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

public final class RequestContext implements AlterableContext {

    private record ContextualInstance(Object instance, CreationalContext<?> ctx) {}

    static final class RequestContextState {
        final Map<Contextual<?>, ContextualInstance> instances = new HashMap<>();
        boolean active = false;
    }

    private static final ScopedValue<RequestContextState> STATE = ScopedValue.newInstance();

    // Fallback for imperative activate/deactivate (e.g., TCK, container shutdown)
    private RequestContextState fallbackState;

    private RequestContextState getState() {
        if (STATE.isBound()) return STATE.get();
        if (fallbackState == null) fallbackState = new RequestContextState();
        return fallbackState;
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return RequestScoped.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        checkActive();
        var state = getState();
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
        checkActive();
        var ci = getState().instances.get(contextual);
        return ci != null ? (T) ci.instance() : null;
    }

    @Override
    public boolean isActive() {
        return getState().active;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy(Contextual<?> contextual) {
        var ci = getState().instances.remove(contextual);
        if (ci != null) {
            ((Contextual<Object>) contextual).destroy(ci.instance(), (CreationalContext<Object>) ci.ctx());
            ci.ctx().release();
        }
    }

    public void activate() {
        var state = getState();
        state.active = true;
        state.instances.clear();
    }

    public void deactivate() {
        var state = getState();
        for (var entry : new HashMap<>(state.instances).entrySet()) {
            destroy(entry.getKey());
        }
        state.instances.clear();
        state.active = false;
    }

    /**
     * Runs an action within a request scope using ScopedValue.
     * Preferred API for virtual thread compatibility.
     */
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

    private void checkActive() {
        if (!isActive()) {
            throw new jakarta.enterprise.context.ContextNotActiveException("RequestScope is not active");
        }
    }
}
