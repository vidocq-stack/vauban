package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.annotation.Annotation;
import java.util.concurrent.ConcurrentHashMap;

public final class ApplicationContext implements AlterableContext {
    private final ConcurrentHashMap<Contextual<?>, Object> instances = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    @Override
    public Class<? extends Annotation> getScope() {
        return ApplicationScoped.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        // Avoid computeIfAbsent to prevent ConcurrentHashMap "Recursive update"
        // when bean creation triggers lookup of another bean (e.g., producer methods).
        var existing = instances.get(contextual);
        if (existing != null) {
            return (T) existing;
        }
        if (creationalContext == null) {
            return null;
        }
        var instance = contextual.create(creationalContext);
        var previous = instances.putIfAbsent(contextual, instance);
        return (T) (previous != null ? previous : instance);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual) {
        return (T) instances.get(contextual);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy(Contextual<?> contextual) {
        var instance = instances.remove(contextual);
        if (instance != null) {
            ((Contextual<Object>) contextual).destroy(instance, new CreationalContextImpl<>());
        }
    }

    public void deactivate() {
        active = false;
        // Destroy all instances
        for (var entry : instances.entrySet()) {
            destroy(entry.getKey());
        }
        instances.clear();
    }
}
