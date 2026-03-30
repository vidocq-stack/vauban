package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.annotation.Annotation;
import java.util.concurrent.ConcurrentHashMap;

public final class ApplicationContext implements AlterableContext {

    private record ContextualInstance(Object instance, CreationalContext<?> ctx) {}

    private final ConcurrentHashMap<Contextual<?>, ContextualInstance> instances = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    @Override
    public Class<? extends Annotation> getScope() {
        return ApplicationScoped.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        var existing = instances.get(contextual);
        if (existing != null) {
            return (T) existing.instance();
        }
        if (creationalContext == null) {
            return null;
        }
        var instance = contextual.create(creationalContext);
        var ci = new ContextualInstance(instance, creationalContext);
        var previous = instances.putIfAbsent(contextual, ci);
        return (T) (previous != null ? previous.instance() : instance);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual) {
        var ci = instances.get(contextual);
        return ci != null ? (T) ci.instance() : null;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy(Contextual<?> contextual) {
        var ci = instances.remove(contextual);
        if (ci != null) {
            ((Contextual<Object>) contextual).destroy(ci.instance(), (CreationalContext<Object>) ci.ctx());
            ci.ctx().release();
        }
    }

    public void deactivate() {
        active = false;
        for (var entry : instances.entrySet()) {
            destroy(entry.getKey());
        }
        instances.clear();
    }
}
