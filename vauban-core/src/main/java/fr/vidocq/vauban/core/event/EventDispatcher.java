package fr.vidocq.vauban.core.event;

import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Dispatches CDI events to matching observer methods.
 */
public final class EventDispatcher {

    private final List<ObserverDescriptor> observers;
    private final VaubanContainer container;

    public EventDispatcher(List<ObserverDescriptor> observers, VaubanContainer container) {
        this.observers = List.copyOf(observers);
        this.container = Objects.requireNonNull(container);
    }

    /**
     * Fire a synchronous event to all matching observers.
     */
    public <T> void fire(T event, Annotation... qualifiers) {
        var eventType = event.getClass();
        var qualifierInstances = toQualifierInstances(qualifiers);
        var matching = findMatchingObservers(eventType, false, qualifierInstances);

        // Sort by priority
        matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));

        for (var observer : matching) {
            invokeObserver(observer, event);
        }
    }

    /**
     * Fire an asynchronous event.
     */
    public <T> CompletionStage<T> fireAsync(T event, Annotation... qualifiers) {
        return CompletableFuture.supplyAsync(() -> {
            var qualifierInstances = toQualifierInstances(qualifiers);
            var matching = findMatchingObservers(event.getClass(), true, qualifierInstances);
            matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));
            for (var observer : matching) {
                invokeObserver(observer, event);
            }
            return event;
        });
    }

    /**
     * Find observers matching a given event type and qualifiers.
     * CDI rule: an observer matches if ALL its observed qualifiers are present
     * in the event qualifiers. An observer with no qualifiers matches all events.
     */
    public List<ObserverDescriptor> findMatchingObservers(Class<?> eventType, boolean asyncOnly,
            Set<DotName> eventQualifiers) {
        var result = new ArrayList<ObserverDescriptor>();
        for (var observer : observers) {
            if (asyncOnly && !observer.async()) continue;
            if (!asyncOnly && observer.async()) continue;

            // Match event type
            if (observer.eventType() instanceof TypeInfo.ClassType ct) {
                try {
                    var observedClass = Class.forName(ct.name().value());
                    if (observedClass.isAssignableFrom(eventType)) {
                        // Match qualifiers: observer qualifiers must be subset of event qualifiers
                        if (observerQualifiersMatch(observer.qualifiers(), eventQualifiers)) {
                            result.add(observer);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    // skip unresolvable types
                }
            }
        }
        return result;
    }

    /**
     * Check if all observer qualifiers are present in the event qualifiers.
     * An observer with no qualifiers matches any event.
     */
    private boolean observerQualifiersMatch(List<QualifierInstance> observerQualifiers,
            Set<DotName> eventQualifiers) {
        if (observerQualifiers.isEmpty()) return true;
        for (var oq : observerQualifiers) {
            // Skip @Any — it matches everything
            if (oq.isAny()) continue;
            if (!eventQualifiers.contains(oq.annotationName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns all registered observer descriptors.
     */
    public List<ObserverDescriptor> observers() {
        return observers;
    }

    private static Set<DotName> toQualifierInstances(Annotation... qualifiers) {
        if (qualifiers == null || qualifiers.length == 0) return Set.of();
        var result = new LinkedHashSet<DotName>();
        for (var q : qualifiers) {
            result.add(DotName.of(q.annotationType().getName()));
        }
        return result;
    }

    private void invokeObserver(ObserverDescriptor observer, Object event) {
        try {
            var beanClass = Class.forName(observer.declaringClass().value());

            // CDI spec: IF_EXISTS — only notify if a bean instance already exists in the context
            if ("IF_EXISTS".equals(observer.reception())) {
                var bm = container.getBeanManager();
                var beans = bm.getBeans(beanClass);
                if (!beans.isEmpty()) {
                    var bean = bm.resolve(beans);
                    if (bean != null) {
                        var scope = bean.getScope();
                        var ctx = bm.getContext(scope);
                        // Check if instance exists WITHOUT creating it
                        var existing = ctx.get((jakarta.enterprise.context.spi.Contextual<?>) bean);
                        if (existing == null) return; // no instance exists, skip
                    }
                }
            }

            var beanInstance = container.select(beanClass);

            var method = findMethod(beanClass, observer.methodName(), event.getClass());
            if (method != null) {
                method.setAccessible(true);
                if (method.getParameterCount() == 1) {
                    method.invoke(beanInstance, event);
                } else {
                    // Resolve additional parameters as injection points
                    // Use a CreationalContext to track @Dependent instances for cleanup
                    var bm = container.getBeanManager();
                    var ctx = new fr.vidocq.vauban.core.context.CreationalContextImpl<>();
                    var paramTypes = method.getParameterTypes();
                    var args = new Object[paramTypes.length];
                    var params = method.getParameters();
                    for (int i = 0; i < params.length; i++) {
                        if (params[i].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || params[i].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                            args[i] = event;
                        } else {
                            // Resolve via BeanManager for proper dependent tracking
                            var beans = bm.getBeans(paramTypes[i]);
                            if (!beans.isEmpty()) {
                                var bean = bm.resolve(beans);
                                var ref = bm.getReference(bean, paramTypes[i], ctx);
                                args[i] = ref;
                            } else {
                                args[i] = container.resolveParameter(paramTypes[i],
                                        method.getGenericParameterTypes()[i]);
                            }
                        }
                    }
                    try {
                        method.invoke(beanInstance, args);
                    } finally {
                        // CDI spec: @Dependent instances injected into observer methods
                        // are destroyed after the observer invocation completes
                        ctx.release();
                    }
                }
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            // CDI spec: observer RuntimeExceptions propagate directly
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new jakarta.enterprise.event.ObserverException(
                    "Failed to invoke observer: " + observer.declaringClass().value()
                            + "." + observer.methodName(), cause);
        } catch (jakarta.enterprise.event.ObserverException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.event.ObserverException(
                    "Failed to invoke observer: " + observer.declaringClass().value()
                            + "." + observer.methodName(), e);
        }
    }

    private Method findMethod(Class<?> clazz, String name, Class<?> eventType) {
        for (var method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() >= 1) {
                if (method.getParameterTypes()[0].isAssignableFrom(eventType)) {
                    return method;
                }
            }
        }
        return null;
    }
}
