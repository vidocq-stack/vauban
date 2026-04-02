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
            invokeObserver(observer, event, qualifiers);
        }
    }

    /**
     * Fire an asynchronous event.
     */
    public <T> CompletionStage<T> fireAsync(T event, Annotation... qualifiers) {
        return fireAsync(event, null, qualifiers);
    }

    /**
     * Fire an asynchronous event with an optional custom executor.
     */
    public <T> CompletionStage<T> fireAsync(T event, java.util.concurrent.Executor executor,
            Annotation... qualifiers) {
        java.util.function.Supplier<T> task = () -> {
            var qualifierInstances = toQualifierInstances(qualifiers);
            var matching = findMatchingObservers(event.getClass(), true, qualifierInstances);
            matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));
            // CDI spec: invoke ALL observers, collect exceptions
            var exceptions = new java.util.ArrayList<Throwable>();
            for (var observer : matching) {
                try {
                    invokeObserver(observer, event, qualifiers);
                } catch (Exception e) {
                    exceptions.add(e);
                }
            }
            if (!exceptions.isEmpty()) {
                // CDI spec: ALL exceptions are added as suppressed to the CompletionException
                var ce = new java.util.concurrent.CompletionException(null);
                for (var ex : exceptions) {
                    ce.addSuppressed(ex);
                }
                throw ce;
            }
            return event;
        };
        return executor != null
                ? CompletableFuture.supplyAsync(task, executor)
                : CompletableFuture.supplyAsync(task);
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
            Class<?> observedClass = resolveObservedType(observer.eventType());
            if (observedClass != null && observedClass.isAssignableFrom(eventType)) {
                // CDI spec: if observer has parameterized event type, check type arguments
                if (observer.eventType() instanceof TypeInfo.ParameterizedType pt) {
                    if (!matchesParameterizedObserver(pt, eventType)) continue;
                }
                // Match qualifiers: observer qualifiers must be subset of event qualifiers
                if (observerQualifiersMatch(observer.qualifiers(), eventQualifiers)) {
                    result.add(observer);
                }
            }
        }
        return result;
    }

    /**
     * Check if the event's actual type matches a parameterized observer type.
     * CDI spec: type arguments of the event type must be assignable to the observer's type arguments.
     */
    private boolean matchesParameterizedObserver(TypeInfo.ParameterizedType observerType, Class<?> eventClass) {
        try {
            var cl = container.classLoader();
            var rawObserved = Class.forName(observerType.rawType().value(), true, cl);

            // Find the actual type arguments of eventClass for rawObserved
            var eventGenericType = findParameterizedSupertype(eventClass, rawObserved);
            if (eventGenericType == null) {
                // eventClass implements rawObserved but we can't find the parameterized version
                // Raw type assignability — accept (CDI spec: raw types are assignable)
                return true;
            }

            var eventTypeArgs = eventGenericType.getActualTypeArguments();
            var observerTypeArgs = observerType.typeArguments();
            if (eventTypeArgs.length != observerTypeArgs.size()) return false; // type args must match in count

            for (int i = 0; i < eventTypeArgs.length; i++) {
                var eventArg = eventTypeArgs[i];
                var observerArg = observerTypeArgs.get(i);

                if (observerArg instanceof TypeInfo.ClassType ct) {
                    // Observer expects a specific type — event must have exactly that type
                    var expectedClass = Class.forName(ct.name().value(), true, cl);
                    if (eventArg instanceof Class<?> ec) {
                        if (!expectedClass.equals(ec)) return false;
                    } else {
                        return false; // event has wildcard/type variable, observer wants concrete
                    }
                }
                // TypeVariable in observer = matches anything (CDI spec)
                // Wildcard in observer = check bounds (simplified: accept)
            }
            return true;
        } catch (Exception e) {
            return true; // fallback: accept on error
        }
    }

    /**
     * Find the ParameterizedType in eventClass's hierarchy that matches rawTarget.
     */
    private static java.lang.reflect.ParameterizedType findParameterizedSupertype(
            Class<?> clazz, Class<?> rawTarget) {
        if (clazz == null || clazz == Object.class) return null;

        // Check superclass
        var genericSuper = clazz.getGenericSuperclass();
        if (genericSuper instanceof java.lang.reflect.ParameterizedType pt) {
            if (pt.getRawType() == rawTarget) return pt;
        }
        // Check interfaces
        for (var iface : clazz.getGenericInterfaces()) {
            if (iface instanceof java.lang.reflect.ParameterizedType pt) {
                if (pt.getRawType() == rawTarget) return pt;
            }
        }
        // Recurse
        var fromSuper = findParameterizedSupertype(clazz.getSuperclass(), rawTarget);
        if (fromSuper != null) return fromSuper;
        for (var iface : clazz.getInterfaces()) {
            var fromIface = findParameterizedSupertype(iface, rawTarget);
            if (fromIface != null) return fromIface;
        }
        return null;
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

    private void invokeObserver(ObserverDescriptor observer, Object event, Annotation... eventQualifiers) {
        try {
            var beanClass = Class.forName(observer.declaringClass().value(), true, container.classLoader());

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

            var method = findMethod(beanClass, observer.methodName(), event.getClass());
            if (method != null) {
                method.setAccessible(true);
                // Static observer methods don't need a bean instance
                var beanInstance = java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        ? null : container.selectByBeanClass(beanClass);
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
                        } else if (paramTypes[i] == jakarta.enterprise.inject.spi.EventMetadata.class) {
                            // CDI spec: EventMetadata injection in observer methods
                            final Object eventObj = event;
                            // Build qualifiers: always include @Any (CDI spec: every event has @Any)
                            final var metaQualifiers = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
                            if (eventQualifiers != null) {
                                for (var q : eventQualifiers) metaQualifiers.add(q);
                            }
                            metaQualifiers.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
                            final java.util.Set<java.lang.annotation.Annotation> immutableQualifiers =
                                    java.util.Set.copyOf(metaQualifiers);
                            args[i] = new jakarta.enterprise.inject.spi.EventMetadata() {
                                @Override public java.util.Set<java.lang.annotation.Annotation> getQualifiers() {
                                    return immutableQualifiers;
                                }
                                @Override public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() {
                                    return null; // CDI spec: null if event was not fired from an injection point
                                }
                                @Override public java.lang.reflect.Type getType() {
                                    return eventObj.getClass();
                                }
                            };
                        } else {
                            // Resolve via BeanManager with qualifiers for proper dependent tracking
                            var paramQualifiers = extractQualifierAnnotations(params[i]);
                            var beans = paramQualifiers.length > 0
                                    ? bm.getBeans(paramTypes[i], paramQualifiers)
                                    : bm.getBeans(paramTypes[i]);
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

    private Class<?> resolveObservedType(TypeInfo typeInfo) {
        try {
            var cl = container.classLoader();
            return switch (typeInfo) {
                case TypeInfo.ClassType ct -> Class.forName(ct.name().value(), true, cl);
                case TypeInfo.ParameterizedType pt -> Class.forName(pt.rawType().value(), true, cl);
                case TypeInfo.ArrayType at -> {
                    var component = resolveObservedType(at.componentType());
                    yield component != null
                            ? java.lang.reflect.Array.newInstance(component, 0).getClass()
                            : null;
                }
                case TypeInfo.PrimitiveType pt -> switch (pt.kind()) {
                    case BOOLEAN -> boolean.class;
                    case BYTE -> byte.class;
                    case CHAR -> char.class;
                    case SHORT -> short.class;
                    case INT -> int.class;
                    case LONG -> long.class;
                    case FLOAT -> float.class;
                    case DOUBLE -> double.class;
                };
                default -> null;
            };
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Method findMethod(Class<?> clazz, String name, Class<?> eventType) {
        // Search the class hierarchy (inherited observer methods)
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() >= 1) {
                    // Find the @Observes/@ObservesAsync parameter
                    var params = method.getParameters();
                    for (var param : params) {
                        if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                            if (param.getType().isAssignableFrom(eventType)) {
                                return method;
                            }
                        }
                    }
                    // Fallback: check first parameter
                    if (method.getParameterTypes()[0].isAssignableFrom(eventType)) {
                        return method;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static java.lang.annotation.Annotation[] extractQualifierAnnotations(java.lang.reflect.Parameter param) {
        var quals = new java.util.ArrayList<java.lang.annotation.Annotation>();
        for (var ann : param.getAnnotations()) {
            if (ann.annotationType() == jakarta.enterprise.event.Observes.class) continue;
            if (ann.annotationType() == jakarta.enterprise.event.ObservesAsync.class) continue;
            if (ann.annotationType() == jakarta.enterprise.inject.TransientReference.class) continue;
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                quals.add(ann);
            }
        }
        return quals.toArray(new java.lang.annotation.Annotation[0]);
    }
}
