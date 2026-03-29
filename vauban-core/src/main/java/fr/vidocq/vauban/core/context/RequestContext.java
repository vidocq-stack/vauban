package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

public final class RequestContext implements AlterableContext {
    private final ThreadLocal<Map<Contextual<?>, Object>> instances =
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
        return (T) instances.get().computeIfAbsent(contextual, k -> {
            if (creationalContext == null) return null;
            return contextual.create(creationalContext);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual) {
        checkActive();
        return (T) instances.get().get(contextual);
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy(Contextual<?> contextual) {
        var instance = instances.get().remove(contextual);
        if (instance != null) {
            ((Contextual<Object>) contextual).destroy(instance, new CreationalContextImpl<>());
        }
    }

    public void activate() {
        active.set(true);
        instances.get().clear();
    }

    public void deactivate() {
        // Destroy all instances
        var map = instances.get();
        for (var entry : new HashMap<>(map).entrySet()) {
            destroy(entry.getKey());
        }
        map.clear();
        active.set(false);
    }

    private void checkActive() {
        if (!isActive()) {
            throw new jakarta.enterprise.context.ContextNotActiveException("RequestScope is not active");
        }
    }
}
