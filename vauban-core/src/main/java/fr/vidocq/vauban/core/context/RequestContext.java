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

    private final ThreadLocal<Map<Contextual<?>, ContextualInstance>> instances =
        ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

    @Override
    public Class<? extends Annotation> getScope() {
        return RequestScoped.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        checkActive();
        var ci = instances.get().get(contextual);
        if (ci != null) {
            return (T) ci.instance();
        }
        if (creationalContext == null) {
            return null;
        }
        var instance = contextual.create(creationalContext);
        instances.get().put(contextual, new ContextualInstance(instance, creationalContext));
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual) {
        checkActive();
        var ci = instances.get().get(contextual);
        return ci != null ? (T) ci.instance() : null;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy(Contextual<?> contextual) {
        var ci = instances.get().remove(contextual);
        if (ci != null) {
            ((Contextual<Object>) contextual).destroy(ci.instance(), (CreationalContext<Object>) ci.ctx());
            ci.ctx().release();
        }
    }

    public void activate() {
        active.set(true);
        instances.get().clear();
    }

    public void deactivate() {
        var map = instances.get();
        for (var entry : new HashMap<>(map).entrySet()) {
            destroy(entry.getKey());
        }
        instances.remove();
        active.remove();
    }

    private void checkActive() {
        if (!isActive()) {
            throw new jakarta.enterprise.context.ContextNotActiveException("RequestScope is not active");
        }
    }
}
