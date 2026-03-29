package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.spi.CreationalContext;
import java.util.ArrayList;
import java.util.List;

public final class CreationalContextImpl<T> implements CreationalContext<T> {
    private final List<T> dependentInstances = new ArrayList<>();

    @Override
    public void push(T incompleteInstance) {
        dependentInstances.add(incompleteInstance);
    }

    @Override
    public void release() {
        dependentInstances.clear();
    }
}
