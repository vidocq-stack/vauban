package fr.vidocq.vauban.core.event;

import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.indexer.model.TypeInfo;

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
    public <T> void fire(T event, java.lang.annotation.Annotation... qualifiers) {
        var eventType = event.getClass();
        var matching = findMatchingObservers(eventType, false);

        // Sort by priority
        matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));

        for (var observer : matching) {
            invokeObserver(observer, event);
        }
    }

    /**
     * Fire an asynchronous event.
     */
    public <T> CompletionStage<T> fireAsync(T event) {
        return CompletableFuture.supplyAsync(() -> {
            var matching = findMatchingObservers(event.getClass(), true);
            matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));
            for (var observer : matching) {
                invokeObserver(observer, event);
            }
            return event;
        });
    }

    /**
     * Find observers matching a given event type.
     */
    public List<ObserverDescriptor> findMatchingObservers(Class<?> eventType, boolean asyncOnly) {
        var result = new ArrayList<ObserverDescriptor>();
        for (var observer : observers) {
            if (asyncOnly && !observer.async()) continue;
            if (!asyncOnly && observer.async()) continue;

            // Match event type
            if (observer.eventType() instanceof TypeInfo.ClassType ct) {
                try {
                    var observedClass = Class.forName(ct.name().value());
                    if (observedClass.isAssignableFrom(eventType)) {
                        result.add(observer);
                    }
                } catch (ClassNotFoundException e) {
                    // skip unresolvable types
                }
            }
        }
        return result;
    }

    /**
     * Returns all registered observer descriptors.
     */
    public List<ObserverDescriptor> observers() {
        return observers;
    }

    private void invokeObserver(ObserverDescriptor observer, Object event) {
        try {
            var beanClass = Class.forName(observer.declaringClass().value());
            var beanInstance = container.select(beanClass);

            var method = findMethod(beanClass, observer.methodName(), event.getClass());
            if (method != null) {
                method.setAccessible(true);
                method.invoke(beanInstance, event);
            }
        } catch (jakarta.enterprise.event.ObserverException e) {
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
