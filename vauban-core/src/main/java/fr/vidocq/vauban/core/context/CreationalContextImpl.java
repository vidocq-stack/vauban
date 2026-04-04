package fr.vidocq.vauban.core.context;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CreationalContextImpl<T> implements CreationalContext<T> {
    private final List<Object> incompleteInstances = new ArrayList<>();
    private final List<DependentInstance> dependentInstances = new ArrayList<>();
    private final Map<String, Object> interceptorInstances = new HashMap<>();

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
            try {
                var fw = new java.io.FileWriter("trace_release.txt", true);
                fw.write("release() STARTED with " + dependentInstances.size() + " items\n");
                fw.close();
            } catch(Exception e) {}
            // CDI spec: release() must destroy all dependent objects
            while (!dependentInstances.isEmpty()) {
                var dep = dependentInstances.remove(dependentInstances.size() - 1);
                try {
                    var fw = new java.io.FileWriter("trace_release.txt", true);
                    fw.write("DESTROYING " + dep.instance.getClass().getName() + "\n");
                    fw.close();
                } catch(Exception e) {}
                dep.destroy();
                try {
                    var fw = new java.io.FileWriter("trace_release.txt", true);
                    fw.write("DESTROYED " + dep.instance.getClass().getName() + ", remaining: " + dependentInstances.size() + "\n");
                    fw.close();
                } catch(Exception e) {}
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

    public static record DependentInstance(Contextual contextual, Object instance, CreationalContext ctx) {
        @SuppressWarnings("unchecked")
        void destroy() {
            try {
                contextual.destroy(instance, ctx);
            } catch (Exception e) {
                System.out.println("Exception destroying dependent instance:");
                e.printStackTrace(System.out);
            }
        }
    }
}
