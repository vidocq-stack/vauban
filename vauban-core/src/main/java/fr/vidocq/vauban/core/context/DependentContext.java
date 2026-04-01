package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.annotation.Annotation;

public final class DependentContext implements Context {

    @Override
    public Class<? extends Annotation> getScope() {
        return Dependent.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        if (creationalContext == null) return null;
        var childCtx = new CreationalContextImpl<T>();
        T instance = contextual.create(childCtx);
        if (instance != null && creationalContext instanceof CreationalContextImpl<?> cci) {
            ((CreationalContextImpl<T>) cci).addDependentInstance(contextual, instance, childCtx);
        }
        return instance;
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        return null; // Dependent instances are never pre-existing
    }

    @Override
    public boolean isActive() {
        return true; // Always active
    }
}
