package fr.vidocq.vauban.core.event;

import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.container.QualifierUtils;
import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class EventDispatcher {

    private final List<ObserverDescriptor> observers;
    private final VaubanContainer container;

    public EventDispatcher(List<ObserverDescriptor> observers, VaubanContainer container) {
        this.observers = List.copyOf(observers);
        this.container = Objects.requireNonNull(container);
    }

    public <T> void fire(T event, Annotation... qualifiers) {
        fire(event, null, qualifiers);
    }

    public <T> void fire(T event, jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint,
            Annotation... qualifiers) {
        var eventType = event.getClass();
        var qualifierInstances = toQualifierInstances(qualifiers);
        var matching = findMatchingObservers(eventType, false, qualifierInstances, qualifiers);

        matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));

        for (var observer : matching) {
            invokeObserver(observer, event, eventInjectionPoint, qualifiers);
        }
    }

    public <T> void fire(T event, java.lang.reflect.Type selectedType,
            jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint,
            Annotation... qualifiers) {
        var qualifierInstances = toQualifierInstances(qualifiers);
        java.lang.reflect.Type eventTypeToMatch = event.getClass();
        if (selectedType != null) {
            eventTypeToMatch = resolveEventType(event.getClass(), selectedType);
        }
        var matching = findMatchingObservers(eventTypeToMatch, false, qualifierInstances, qualifiers);
        matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));
        for (var observer : matching) {
            invokeObserver(observer, event, selectedType, eventInjectionPoint, qualifiers);
        }
    }

    public <T> CompletionStage<T> fireAsync(T event, Annotation... qualifiers) {
        return fireAsync(event, null, qualifiers);
    }

    public <T> CompletionStage<T> fireAsync(T event, java.util.concurrent.Executor executor,
            Annotation... qualifiers) {
        java.util.function.Supplier<T> task = () -> {
            var qualifierInstances = toQualifierInstances(qualifiers);
            var matching = findMatchingObservers(event.getClass(), true, qualifierInstances);
            matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));
            var exceptions = new java.util.ArrayList<Throwable>();
            for (var observer : matching) {
                try {
                    invokeObserver(observer, event, qualifiers);
                } catch (Exception e) {
                    exceptions.add(e);
                }
            }
            if (!exceptions.isEmpty()) {
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

    public List<ObserverDescriptor> findMatchingObservers(java.lang.reflect.Type eventType, boolean asyncOnly,
            Set<DotName> eventQualifiers) {
        return findMatchingObservers(eventType, asyncOnly, eventQualifiers, null);
    }

    public List<ObserverDescriptor> findMatchingObservers(java.lang.reflect.Type eventType, boolean asyncOnly,
            Set<DotName> eventQualifiers, Annotation[] eventQualifierAnnotations) {
        var result = new ArrayList<ObserverDescriptor>();
        for (var observer : observers) {
            if (asyncOnly && !observer.async()) continue;
            if (!asyncOnly && observer.async()) continue;

            if (!eventTypeMatches(observer, eventType)) continue;

            boolean qualMatch;
            if (eventQualifierAnnotations != null && eventQualifierAnnotations.length > 0) {
                qualMatch = observerQualifiersMatchFull(observer.qualifiers(), eventQualifierAnnotations);
            } else {
                qualMatch = observerQualifiersMatch(observer.qualifiers(), eventQualifiers);
            }
            if (qualMatch) {
                result.add(observer);
            }
        }
        return result;
    }

    private boolean eventTypeMatches(ObserverDescriptor observer, java.lang.reflect.Type eventType) {
        Class<?> eventClass = resolveTypeToClass(eventType);
        if (eventClass == null) return false;

        if (observer.eventType() instanceof TypeInfo.TypeVariable tv) {
            // CDI spec: TypeVariable observer matches any event assignable to the bounds
            if (!tv.bounds().isEmpty()) {
                for (var bound : tv.bounds()) {
                    var boundClass = resolveObservedType(bound);
                    if (boundClass != null && !boundClass.isAssignableFrom(eventClass)) {
                        return false;
                    }
                }
            }
            return true;
        }

        Class<?> observedClass = resolveObservedType(observer.eventType());
        if (observedClass == null) return false;
        if (!observedClass.isAssignableFrom(eventClass)) return false;

        if (observer.eventType() instanceof TypeInfo.ParameterizedType pt) {
            if (!matchesParameterizedObserver(pt, eventType)) return false;
        }
        return true;
    }

    private boolean matchesParameterizedObserver(TypeInfo.ParameterizedType observerType, java.lang.reflect.Type eventType) {
        Class<?> eventClass = resolveTypeToClass(eventType);
        if (eventClass == null) return false;
        try {
            var cl = container.classLoader();
            var rawObserved = Class.forName(observerType.rawType().value(), true, cl);

            java.lang.reflect.ParameterizedType eventGenericType = null;
            for (java.lang.reflect.Type supertype : fr.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(eventType)) {
                if (supertype instanceof java.lang.reflect.ParameterizedType pt) {
                    if (pt.getRawType() == rawObserved) {
                        eventGenericType = pt;
                        break;
                    }
                }
            }

            if (eventGenericType == null) {
                return true;
            }

            var eventTypeArgs = eventGenericType.getActualTypeArguments();
            var observerTypeArgs = observerType.typeArguments();
            if (eventTypeArgs.length != observerTypeArgs.size()) return false;

            for (int i = 0; i < eventTypeArgs.length; i++) {
                if (!typeArgMatchesObserver(eventTypeArgs[i], observerTypeArgs.get(i), cl)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean typeArgMatchesObserver(java.lang.reflect.Type eventArg, TypeInfo observerArg, ClassLoader cl)
            throws ClassNotFoundException {
        if (observerArg instanceof TypeInfo.TypeVariable tv) {
            if (!tv.bounds().isEmpty()) {
                var eventClass = resolveTypeToClass(eventArg);
                if (eventClass != null) {
                    for (var bound : tv.bounds()) {
                        var boundClass = resolveTypeInfoToClass(bound, cl);
                        if (boundClass != null && !boundClass.isAssignableFrom(eventClass)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        if (observerArg instanceof TypeInfo.WildcardType wt) {
            if (wt.upperBound() != null && !(wt.upperBound() instanceof TypeInfo.ClassType ct
                    && ct.name().value().equals("java.lang.Object"))) {
                var upperClass = resolveTypeInfoToClass(wt.upperBound(), cl);
                var eventClass = resolveTypeToClass(eventArg);
                if (upperClass != null && eventClass != null && !upperClass.isAssignableFrom(eventClass)) {
                    return false;
                }
            }
            if (wt.lowerBound() != null) {
                var lowerClass = resolveTypeInfoToClass(wt.lowerBound(), cl);
                var eventClass = resolveTypeToClass(eventArg);
                if (lowerClass != null && eventClass != null && !eventClass.isAssignableFrom(lowerClass)) {
                    return false;
                }
            }
            return true;
        }

        if (observerArg instanceof TypeInfo.ClassType ct) {
            var expectedClass = Class.forName(ct.name().value(), true, cl);
            if (eventArg instanceof Class<?> ec) {
                return expectedClass.equals(ec);
            }
            if (eventArg instanceof java.lang.reflect.WildcardType ewt) {
                for (var bound : ewt.getUpperBounds()) {
                    if (bound instanceof Class<?> bc && expectedClass.equals(bc)) return true;
                }
                return false;
            }
            return false;
        }

        if (observerArg instanceof TypeInfo.ParameterizedType opt) {
            if (eventArg instanceof java.lang.reflect.ParameterizedType ept) {
                var obsRaw = Class.forName(opt.rawType().value(), true, cl);
                if (ept.getRawType() != obsRaw) return false;
                var eArgs = ept.getActualTypeArguments();
                var oArgs = opt.typeArguments();
                if (eArgs.length != oArgs.size()) return false;
                for (int i = 0; i < eArgs.length; i++) {
                    if (!typeArgMatchesObserver(eArgs[i], oArgs.get(i), cl)) return false;
                }
                return true;
            }
            return false;
        }

        return true;
    }

    private static Class<?> resolveTypeInfoToClass(TypeInfo typeInfo, ClassLoader cl) {
        if (typeInfo instanceof TypeInfo.ClassType ct) {
            try { return Class.forName(ct.name().value(), true, cl); } catch (Exception e) { return null; }
        }
        return null;
    }

    private static Class<?> resolveTypeToClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
        return null;
    }


    private static java.lang.reflect.Type resolveEventType(Class<?> runtimeClass, java.lang.reflect.Type selectedType) {
        if (runtimeClass.getTypeParameters().length == 0) {
            return runtimeClass;
        }
        java.lang.reflect.Type resultType = runtimeClass;
        if (selectedType instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> selectedClass = resolveTypeToClass(pt.getRawType());
            if (selectedClass != null && selectedClass.isAssignableFrom(runtimeClass)) {
                resultType = mergeTypeParameters(runtimeClass, pt);
            }
        }
        return resultType;
    }

    private static java.lang.reflect.Type mergeTypeParameters(Class<?> runtimeClass, java.lang.reflect.ParameterizedType selectedType) {
        java.util.Map<String, java.lang.reflect.Type> typeMappings = new java.util.HashMap<>();
        Class<?> selectedRaw = resolveTypeToClass(selectedType.getRawType());
        if (selectedRaw != null) {
            var typeParams = selectedRaw.getTypeParameters();
            var typeArgs = selectedType.getActualTypeArguments();
            for (int i = 0; i < typeParams.length && i < typeArgs.length; i++) {
                typeMappings.put(typeParams[i].getName(), typeArgs[i]);
            }
        }

        var runtimeParams = runtimeClass.getTypeParameters();
        java.lang.reflect.Type[] resolvedArgs = new java.lang.reflect.Type[runtimeParams.length];
        for (int i = 0; i < runtimeParams.length; i++) {
            java.lang.reflect.Type mapped = typeMappings.get(runtimeParams[i].getName());
            resolvedArgs[i] = mapped != null ? mapped : runtimeParams[i];
        }
        
        var result = new ParameterizedTypeImpl(runtimeClass, resolvedArgs, runtimeClass.getDeclaringClass());
        System.err.println("mergeTypeParameters returning " + java.util.Arrays.toString(resolvedArgs) + " for " + runtimeClass + " and " + selectedType);
        return result;
    }

    private static class ParameterizedTypeImpl implements java.lang.reflect.ParameterizedType {
        private final Class<?> rawType;
        private final java.lang.reflect.Type[] actualTypeArguments;
        private final java.lang.reflect.Type ownerType;

        public ParameterizedTypeImpl(Class<?> rawType, java.lang.reflect.Type[] actualTypeArguments, java.lang.reflect.Type ownerType) {
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments;
            this.ownerType = ownerType;
        }
        @Override public java.lang.reflect.Type[] getActualTypeArguments() { return actualTypeArguments; }
        @Override public java.lang.reflect.Type getRawType() { return rawType; }
        @Override public java.lang.reflect.Type getOwnerType() { return ownerType; }
        @Override public String toString() { return rawType.getName() + "<...>"; }
    }

    private boolean observerQualifiersMatch(List<QualifierInstance> observerQualifiers,
            Set<DotName> eventQualifiers) {
        if (observerQualifiers.isEmpty()) return true;
        for (var oq : observerQualifiers) {
            if (oq.isAny()) continue;
            if (!eventQualifiers.contains(oq.annotationName())) {
                return false;
            }
        }
        return true;
    }

    private boolean observerQualifiersMatchFull(List<QualifierInstance> observerQualifiers,
            Annotation[] eventQualifiers) {
        if (observerQualifiers.isEmpty()) return true;
        var cl = container.classLoader();
        for (var oq : observerQualifiers) {
            if (oq.isAny()) continue;
            var observerAnn = QualifierUtils.toAnnotation(oq, null, cl);
            if (observerAnn == null) continue;
            boolean found = false;
            for (var eq : eventQualifiers) {
                if (eq.annotationType().getName().equals(oq.annotationName().value())) {
                    if (qualifierMembersMatch(observerAnn, eq)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean qualifierMembersMatch(Annotation observer, Annotation event) {
        if (!observer.annotationType().getName().equals(event.annotationType().getName())) return false;
        try {
            for (var method : observer.annotationType().getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) continue;
                var obsVal = method.invoke(observer);
                var evtVal = method.invoke(event);
                if (!Objects.deepEquals(obsVal, evtVal)) return false;
            }
            return true;
        } catch (Exception e) {
            return observer.equals(event);
        }
    }

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

    public void invokeObserverDirect(ObserverDescriptor observer, Object event) {
        invokeObserver(observer, event, (Annotation[]) new Annotation[0]);
    }

    private void invokeObserver(ObserverDescriptor observer, Object event, Annotation... eventQualifiers) {
        invokeObserver(observer, event, null, null, eventQualifiers);
    }

    private void invokeObserver(ObserverDescriptor observer, Object event,
            jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint, Annotation... eventQualifiers) {
        invokeObserver(observer, event, null, eventInjectionPoint, eventQualifiers);
    }

    private void invokeObserver(ObserverDescriptor observer, Object event,
            java.lang.reflect.Type selectedEventType,
            jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint, Annotation... eventQualifiers) {
        try {
            var beanClass = Class.forName(observer.declaringClass().value(), true, container.classLoader());

            if ("IF_EXISTS".equals(observer.reception())) {
                var bm = container.getBeanManager();
                var beans = bm.getBeans(beanClass);
                if (!beans.isEmpty()) {
                    var bean = bm.resolve(beans);
                    if (bean != null) {
                        var scope = bean.getScope();
                        var ctx = bm.getContext(scope);
                        var existing = ctx.get((jakarta.enterprise.context.spi.Contextual<?>) bean);
                        if (existing == null) return;
                    }
                }
            }

            var method = findMethod(beanClass, observer.methodName(), event.getClass());
            if (method != null) {
                method.setAccessible(true);
                var bm = container.getBeanManager();
                var exactBean = container.findManagedBeanByExactClass(beanClass);
                boolean declaringIsDependent = exactBean != null
                        && exactBean.getScope() == jakarta.enterprise.context.Dependent.class;
                @SuppressWarnings("unchecked")
                var beanCtx = declaringIsDependent
                        ? new fr.vidocq.vauban.core.context.CreationalContextImpl<>()
                        : null;
                var beanInstance = java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        ? null : (beanCtx != null
                                ? bm.getReference(exactBean, beanClass, beanCtx)
                                : container.selectByBeanClass(beanClass));
                try {
                    if (method.getParameterCount() == 1) {
                        method.invoke(beanInstance, event);
                    } else {
                        var ctx = new fr.vidocq.vauban.core.context.CreationalContextImpl<>();
                        var paramTypes = method.getParameterTypes();
                        var args = new Object[paramTypes.length];
                        var params = method.getParameters();
                        for (int i = 0; i < params.length; i++) {
                            if (params[i].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                    || params[i].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                                args[i] = event;
                            } else if (paramTypes[i] == jakarta.enterprise.inject.spi.EventMetadata.class) {
                                final Object eventObj = event;
                                final var metaQualifiers = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
                                if (eventQualifiers != null) {
                                    for (var q : eventQualifiers) metaQualifiers.add(q);
                                }
                                boolean hasExplicitQualifier = false;
                                for (var q : metaQualifiers) {
                                    if (q.annotationType() != jakarta.enterprise.inject.Any.class
                                            && q.annotationType() != jakarta.enterprise.inject.Default.class) {
                                        hasExplicitQualifier = true;
                                        break;
                                    }
                                }
                                if (!hasExplicitQualifier) {
                                    metaQualifiers.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
                                }
                                metaQualifiers.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
                                final java.util.Set<java.lang.annotation.Annotation> immutableQualifiers =
                                        java.util.Set.copyOf(metaQualifiers);
                                args[i] = new jakarta.enterprise.inject.spi.EventMetadata() {
                                    @Override public java.util.Set<java.lang.annotation.Annotation> getQualifiers() {
                                        return immutableQualifiers;
                                    }
                                    @Override public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() {
                                        return eventInjectionPoint;
                                    }
                                    @Override public java.lang.reflect.Type getType() {
                                        return selectedEventType != null ? selectedEventType : eventObj.getClass();
                                    }
                                };
                            } else if (paramTypes[i] == jakarta.enterprise.inject.Instance.class
                                    || paramTypes[i] == jakarta.inject.Provider.class) {
                                Class<?> instanceType = Object.class;
                                var genericType = method.getGenericParameterTypes()[i];
                                if (genericType instanceof java.lang.reflect.ParameterizedType pt
                                        && pt.getActualTypeArguments().length > 0) {
                                    var typeArg = pt.getActualTypeArguments()[0];
                                    if (typeArg instanceof Class<?> c) instanceType = c;
                                }
                                var paramQualifiers = extractQualifierAnnotations(params[i]);
                                args[i] = new fr.vidocq.vauban.core.container.InstanceImpl<>(
                                        container, instanceType, paramQualifiers, null);
                            } else {
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
                            ctx.release();
                        }
                    }
                } finally {
                    if (beanCtx != null) beanCtx.release();
                }
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
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
                case TypeInfo.TypeVariable tv -> {
                    if (!tv.bounds().isEmpty()) {
                        yield resolveObservedType(tv.bounds().getFirst());
                    }
                    yield Object.class;
                }
                case TypeInfo.WildcardType wt -> {
                    if (wt.upperBound() != null) {
                        yield resolveObservedType(wt.upperBound());
                    }
                    yield Object.class;
                }
                default -> null;
            };
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Method findMethod(Class<?> clazz, String name, Class<?> eventType) {
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() >= 1) {
                    var params = method.getParameters();
                    for (int p = 0; p < params.length; p++) {
                        if (params[p].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || params[p].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                            if (params[p].getType().isAssignableFrom(eventType)) {
                                return method;
                            }
                            var genericType = method.getGenericParameterTypes()[p];
                            if (genericType instanceof java.lang.reflect.TypeVariable<?>) {
                                return method;
                            }
                        }
                    }
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
