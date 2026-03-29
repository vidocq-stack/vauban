package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.util.ArrayList;
import java.util.List;

public final class CreationalContextImpl<T> implements CreationalContext<T> {
    private final List<T> incompleteInstances = new ArrayList<>();
    private final List<DependentInstance<?>> dependentInstances = new ArrayList<>();

    @Override
    public void push(T incompleteInstance) {
        incompleteInstances.add(incompleteInstance);
    }

    @Override
    public void release() {
        // CDI spec: release() must destroy all dependent objects
        for (var dep : dependentInstances) {
            dep.destroy();
        }
        dependentInstances.clear();
        incompleteInstances.clear();
    }

    /**
     * Register a dependent instance for cleanup on release().
     */
    @SuppressWarnings("unchecked")
    public <X> void addDependentInstance(Contextual<X> contextual, X instance, CreationalContext<X> ctx) {
        dependentInstances.add(new DependentInstance<>(contextual, instance, ctx));
    }

    private record DependentInstance<X>(Contextual<X> contextual, X instance, CreationalContext<X> ctx) {
        void destroy() {
            try {
                contextual.destroy(instance, ctx);
            } catch (Exception e) {
                // CDI spec: exceptions during dependent destruction are suppressed
            }
        }
    }
}
