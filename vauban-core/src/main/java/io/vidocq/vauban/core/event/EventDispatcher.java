/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.event;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.annotation.AnnotationKey;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.indexer.model.TypeInfo;


import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class EventDispatcher {

    private static final java.util.Map<String, Class<?>> PRIMITIVE_NAME_TO_CLASS = java.util.Map.of(
            "boolean", boolean.class, "byte", byte.class, "char", char.class,
            "short", short.class, "int", int.class, "long", long.class,
            "float", float.class, "double", double.class
    );

    private static final String PROP_USE_PLATFORM_THREADS = "VaubanUsePlatformThreadsForAsyncEvents";
    private static final String ENV_USE_PLATFORM_THREADS = "VAUBAN_USE_PLATFORM_THREADS_FOR_ASYNC_EVENTS";

    private final List<ObserverDescriptor> observers;
    private final VaubanContainer container;
    private final java.util.concurrent.Executor defaultAsyncExecutor;
    // Lazily computed immutable list, parallel to observers: computed once, read-only after.
    @SuppressWarnings("java:S3077")
    private volatile List<Set<AnnotationKey>> observerKeys;

    public EventDispatcher(List<ObserverDescriptor> observers, VaubanContainer container) {
        this.observers = List.copyOf(observers);
        this.container = Objects.requireNonNull(container);
        boolean usePlatformThreads = Boolean.parseBoolean(
                System.getProperty(PROP_USE_PLATFORM_THREADS,
                        System.getenv().getOrDefault(ENV_USE_PLATFORM_THREADS, "false")));
        this.defaultAsyncExecutor = usePlatformThreads
                ? null  // null → CompletableFuture uses ForkJoinPool.commonPool()
                : java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    public <T> void fire(T event, Annotation... qualifiers) {
        fire(event, null, qualifiers);
    }

    public <T> void fire(T event, jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint,
            Annotation... qualifiers) {
        fire(event, null, eventInjectionPoint, keysOf(qualifiers), qualifiers);
    }

    public <T> void fire(T event, java.lang.reflect.Type selectedType,
            jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint,
            Annotation... qualifiers) {
        fire(event, selectedType, eventInjectionPoint, keysOf(qualifiers), qualifiers);
    }

    /** With the event's qualifiers already reduced to their keys: an injected {@code Event} converts once. */
    <T> void fire(T event, java.lang.reflect.Type selectedType,
            jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint,
            Set<AnnotationKey> eventKeys, Annotation[] qualifiers) {
        java.lang.reflect.Type eventTypeToMatch = event.getClass();
        if (selectedType != null) {
            eventTypeToMatch = resolveEventType(event.getClass(), selectedType);
            if (containsTypeVariable(eventTypeToMatch)) {
                // Runtime class has type variables that could not be resolved from selectedType
                // This means the runtime class introduced new type parameters -> IAE
                throw new IllegalArgumentException("Event type contains unresolvable type variable: " + eventTypeToMatch);
            }
        }
        var matching = findMatchingObservers(eventTypeToMatch, false, eventKeys);
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
        return fireAsync(event, executor, keysOf(qualifiers), qualifiers);
    }

    /**
     * With the event's qualifiers already reduced to their keys. They are converted on the calling
     * thread, before the task: an asynchronous event resolves its observers by the same rule as a
     * synchronous one, member values included (BUG-20260914-06).
     */
    <T> CompletionStage<T> fireAsync(T event, java.util.concurrent.Executor executor,
            Set<AnnotationKey> eventKeys, Annotation[] qualifiers) {
        java.util.function.Supplier<T> task = () -> {
            var matching = findMatchingObservers(event.getClass(), true, eventKeys);
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
        var effectiveExecutor = executor != null ? executor : defaultAsyncExecutor;
        return effectiveExecutor != null
                ? CompletableFuture.supplyAsync(task, effectiveExecutor)
                : CompletableFuture.supplyAsync(task);
    }

    /**
     * The observers of {@code eventType} whose qualifiers the event carries. Both sides compare keys,
     * so a member value decides on the asynchronous path as it does on the synchronous one, and a
     * member an extension made non-binding decides on neither (BUG-20260914-06, BUG-20260914-07).
     */
    public List<ObserverDescriptor> findMatchingObservers(java.lang.reflect.Type eventType, boolean asyncOnly,
            Set<AnnotationKey> eventKeys) {
        var result = new ArrayList<ObserverDescriptor>();
        for (int i = 0; i < observers.size(); i++) {
            var observer = observers.get(i);
            if (asyncOnly && !observer.async()) continue;
            if (!asyncOnly && observer.async()) continue;

            if (!eventTypeMatches(observer, eventType)) {
                continue;
            }

            if (observerMatches(observerKeys(i), eventKeys)) {
                result.add(observer);
            }
        }
        return result;
    }

    /** The keys of the qualifiers of every observer, computed once for the container's lifetime. */
    private Set<AnnotationKey> observerKeys(int index) {
        var keys = observerKeys;
        if (keys == null) {
            var matcher = container.qualifierMatcher();
            keys = observers.stream().map(observer -> matcher.keys(observer.qualifiers())).toList();
            observerKeys = keys;
        }
        return keys.get(index);
    }

    /** The keys of the qualifiers an event was fired with. */
    Set<AnnotationKey> keysOf(Annotation... qualifiers) {
        return annotationTypes().keys(qualifiers);
    }

    /** The container's annotation metadata: what its modules generated, then what it can read. */
    public io.vidocq.vauban.core.annotation.AnnotationTypes annotationTypes() {
        return container.qualifierMatcher().types();
    }

    /** CDI 4.1 §10.2.1: every qualifier the observer declares, {@code @Any} aside, must be on the event. */
    private static boolean observerMatches(Set<AnnotationKey> observerKeys, Set<AnnotationKey> eventKeys) {
        for (var key : observerKeys) {
            if (key.isAny()) continue;
            if (!eventKeys.contains(key)) return false;
        }
        return true;
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
            for (java.lang.reflect.Type supertype : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(eventType)) {
                if (supertype instanceof java.lang.reflect.ParameterizedType pt) {
                    if (pt.getRawType() == rawObserved
                            || (pt.getRawType() instanceof Class<?> ptc && ptc.getName().equals(rawObserved.getName()))) {
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
            var expectedName = ct.name().value();
            if (eventArg instanceof Class<?> ec) {
                return expectedName.equals(ec.getName());
            }
            if (eventArg instanceof java.lang.reflect.WildcardType ewt) {
                for (var bound : ewt.getUpperBounds()) {
                    if (bound instanceof Class<?> bc && expectedName.equals(bc.getName())) return true;
                }
                return false;
            }
            return false;
        }

        if (observerArg instanceof TypeInfo.ParameterizedType opt) {
            if (eventArg instanceof java.lang.reflect.ParameterizedType ept) {
                var obsRawName = opt.rawType().value();
                var eptRaw = ept.getRawType();
                if (!(eptRaw instanceof Class<?> eptRawClass && obsRawName.equals(eptRawClass.getName()))) return false;
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


    private static boolean containsTypeVariable(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.TypeVariable<?>) return true;
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) return true;
            }
        }
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            return containsTypeVariable(gat.getGenericComponentType());
        }
        if (type instanceof java.lang.reflect.WildcardType wt) {
            for (var bound : wt.getUpperBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
            for (var bound : wt.getLowerBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
        }
        return false;
    }

    private static java.lang.reflect.Type resolveEventType(Class<?> runtimeClass, java.lang.reflect.Type selectedType) {
        if (selectedType instanceof java.lang.reflect.ParameterizedType pt) {
            Class<?> selectedClass = resolveTypeToClass(pt.getRawType());
            if (selectedClass != null && selectedClass.isAssignableFrom(runtimeClass)) {
                if (runtimeClass.getTypeParameters().length == 0) {
                    // Runtime class has no type params (e.g. Baz extends Bar<List<Integer>>)
                    // Use runtime class directly - its supertypes are fully resolved
                    return runtimeClass;
                }
                if (runtimeClass.equals(selectedClass)) {
                    // Same raw type - use selected type directly
                    return selectedType;
                }
                // Runtime is a subclass with its own type params - merge
                return mergeTypeHierarchy(runtimeClass, pt);
            }
        }
        if (runtimeClass.getTypeParameters().length == 0) {
            return runtimeClass;
        }
        return runtimeClass;
    }

    private static java.lang.reflect.Type mergeTypeHierarchy(Class<?> runtimeClass, java.lang.reflect.ParameterizedType selectedType) {
        // Walk from runtimeClass up to selectedType's raw class, collecting type variable mappings
        var resolvedMap = new java.util.HashMap<String, java.lang.reflect.Type>();
        collectMappingsFromSelected(runtimeClass, selectedType, resolvedMap);

        var runtimeParams = runtimeClass.getTypeParameters();
        java.lang.reflect.Type[] resolvedArgs = new java.lang.reflect.Type[runtimeParams.length];
        for (int i = 0; i < runtimeParams.length; i++) {
            var key = runtimeClass.getName() + "#" + runtimeParams[i].getName();
            java.lang.reflect.Type mapped = resolvedMap.get(key);
            resolvedArgs[i] = mapped != null ? mapped : runtimeParams[i];
        }
        return new ParameterizedTypeImpl(runtimeClass, resolvedArgs, runtimeClass.getDeclaringClass());
    }

    private static void collectMappingsFromSelected(Class<?> runtimeClass, java.lang.reflect.ParameterizedType selectedType,
            java.util.Map<String, java.lang.reflect.Type> resolvedMap) {
        Class<?> selectedRaw = resolveTypeToClass(selectedType.getRawType());
        if (selectedRaw == null) return;

        // First, assign selectedType's type args to selectedRaw's type params
        var selectedParams = selectedRaw.getTypeParameters();
        var selectedArgs = selectedType.getActualTypeArguments();
        var typeVarMap = new java.util.HashMap<String, java.lang.reflect.Type>();
        for (int i = 0; i < selectedParams.length && i < selectedArgs.length; i++) {
            typeVarMap.put(selectedRaw.getName() + "#" + selectedParams[i].getName(), selectedArgs[i]);
        }

        // Walk up from runtimeClass to selectedRaw, propagating type variable assignments
        propagateDown(runtimeClass, selectedRaw, typeVarMap, resolvedMap);
    }

    private static boolean propagateDown(Class<?> current, Class<?> target,
            java.util.Map<String, java.lang.reflect.Type> targetMap,
            java.util.Map<String, java.lang.reflect.Type> resolvedMap) {
        if (current == null || current == Object.class) return false;
        if (current.equals(target)) {
            // Copy target mappings to resolved
            resolvedMap.putAll(targetMap);
            return true;
        }

        // Try superclass
        var genSuper = current.getGenericSuperclass();
        if (genSuper != null) {
            Class<?> superRaw = resolveTypeToClass(genSuper);
            if (superRaw != null && target.isAssignableFrom(superRaw)) {
                if (propagateDown(superRaw, target, targetMap, resolvedMap)) {
                    mapCurrentFromSuper(current, genSuper, superRaw, resolvedMap);
                    return true;
                }
            }
        }

        // Try interfaces
        for (var genIface : current.getGenericInterfaces()) {
            Class<?> ifaceRaw = resolveTypeToClass(genIface);
            if (ifaceRaw != null && target.isAssignableFrom(ifaceRaw)) {
                if (propagateDown(ifaceRaw, target, targetMap, resolvedMap)) {
                    mapCurrentFromSuper(current, genIface, ifaceRaw, resolvedMap);
                    return true;
                }
            }
        }
        return false;
    }

    private static void mapCurrentFromSuper(Class<?> current, java.lang.reflect.Type genSuper, Class<?> superRaw,
            java.util.Map<String, java.lang.reflect.Type> resolvedMap) {
        // genSuper is e.g. Foo<B> where B is a TypeVariable of current, or Foo<List<Integer>> etc.
        if (genSuper instanceof java.lang.reflect.ParameterizedType pt) {
            var superParams = superRaw.getTypeParameters();
            var superArgs = pt.getActualTypeArguments();
            for (int i = 0; i < superParams.length && i < superArgs.length; i++) {
                var superKey = superRaw.getName() + "#" + superParams[i].getName();
                var resolvedValue = resolvedMap.get(superKey);
                if (resolvedValue != null && superArgs[i] instanceof java.lang.reflect.TypeVariable<?> tv) {
                    // tv belongs to current class - map it
                    var currentKey = current.getName() + "#" + tv.getName();
                    resolvedMap.put(currentKey, resolvedValue);
                }
            }
        }
    }

    /**
     * CDI spec: EventMetadata.getType() returns the runtime class of the event object
     * with type variables resolved. When the runtime class differs from the selected type's
     * raw type (e.g., ArrayList fired through Event&lt;List&lt;...&gt;&gt;), we resolve
     * the runtime class's type parameters from the selected type.
     */
    private static java.lang.reflect.Type resolveRuntimeEventType(Object event, java.lang.reflect.Type selectedType) {
        if (selectedType == null) return event.getClass();
        Class<?> runtimeClass = event.getClass();
        if (!(selectedType instanceof java.lang.reflect.ParameterizedType selectedPT)) return selectedType;
        Class<?> selectedRaw = (Class<?>) selectedPT.getRawType();
        if (runtimeClass == selectedRaw) return selectedType;

        var runtimeTypeParams = runtimeClass.getTypeParameters();
        if (runtimeTypeParams.length == 0) return runtimeClass;

        // Find the generic supertype of runtimeClass that matches selectedRaw
        java.lang.reflect.ParameterizedType matchingSupertype = findGenericSupertype(runtimeClass, selectedRaw);
        if (matchingSupertype == null) return selectedType;

        // Map type variables from the matching supertype to the selected type's actual args
        var varMap = new java.util.HashMap<String, java.lang.reflect.Type>();
        var supertypeArgs = matchingSupertype.getActualTypeArguments();
        var selectedArgs = selectedPT.getActualTypeArguments();
        for (int i = 0; i < supertypeArgs.length && i < selectedArgs.length; i++) {
            if (supertypeArgs[i] instanceof java.lang.reflect.TypeVariable<?> tv) {
                varMap.put(tv.getName(), selectedArgs[i]);
            }
        }

        var resolvedArgs = new java.lang.reflect.Type[runtimeTypeParams.length];
        for (int i = 0; i < runtimeTypeParams.length; i++) {
            resolvedArgs[i] = varMap.getOrDefault(runtimeTypeParams[i].getName(), Object.class);
        }
        return new ParameterizedTypeImpl(runtimeClass, resolvedArgs, null);
    }

    private static java.lang.reflect.ParameterizedType findGenericSupertype(Class<?> clazz, Class<?> targetRaw) {
        for (var iface : clazz.getGenericInterfaces()) {
            if (iface instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() == targetRaw) {
                return pt;
            }
        }
        if (clazz.getGenericSuperclass() instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() == targetRaw) {
            return pt;
        }
        // Recurse through supertypes
        for (var iface : clazz.getGenericInterfaces()) {
            Class<?> rawIface = (iface instanceof java.lang.reflect.ParameterizedType pt)
                    ? (Class<?>) pt.getRawType()
                    : (iface instanceof Class<?> c ? c : null);
            if (rawIface != null && targetRaw.isAssignableFrom(rawIface)) {
                var result = findGenericSupertype(rawIface, targetRaw);
                if (result != null) return result;
            }
        }
        var superclass = clazz.getSuperclass();
        if (superclass != null && targetRaw.isAssignableFrom(superclass)) {
            return findGenericSupertype(superclass, targetRaw);
        }
        return null;
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

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof java.lang.reflect.ParameterizedType other)) return false;
            return java.util.Objects.equals(rawType, other.getRawType())
                    && java.util.Arrays.equals(actualTypeArguments, other.getActualTypeArguments())
                    && java.util.Objects.equals(ownerType, other.getOwnerType());
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(actualTypeArguments)
                    ^ java.util.Objects.hashCode(rawType)
                    ^ java.util.Objects.hashCode(ownerType);
        }

        @Override
        public String toString() {
            if (actualTypeArguments.length == 0) return rawType.getTypeName();
            var sb = new StringBuilder(rawType.getTypeName()).append('<');
            for (int i = 0; i < actualTypeArguments.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(actualTypeArguments[i].getTypeName());
            }
            return sb.append('>').toString();
        }
    }

    public List<ObserverDescriptor> observers() {
        return observers;
    }

    public void invokeObserverDirect(ObserverDescriptor observer, Object event) {
        invokeObserver(observer, event, (Annotation[]) new Annotation[0]);
    }

    private void invokeObserver(ObserverDescriptor observer, Object event, Annotation... eventQualifiers) {
        invokeObserver(observer, event, null, null, eventQualifiers);
    }

    private void invokeObserver(ObserverDescriptor observer, Object event,
            java.lang.reflect.Type selectedEventType,
            jakarta.enterprise.inject.spi.InjectionPoint eventInjectionPoint, Annotation... eventQualifiers) {
        if (observer.isSynthetic()) {
            observer.syntheticInvoker().accept(event, eventQualifiers);
            return;
        }
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
                // Invocation goes through VaubanLookup.invokeMethod: it calls the observer in-module
                // via the module's generated provider when one owns it (no reflection, no opens), and
                // only the reflective fallback needs deep access. makeAccessible is therefore done
                // lazily inside that fallback, not eagerly here.
                var bm = container.getBeanManager();
                var exactBean = container.findManagedBeanByExactClass(beanClass);
                boolean declaringIsDependent = exactBean != null
                        && exactBean.getScope() == jakarta.enterprise.context.Dependent.class;
                @SuppressWarnings("unchecked")
                var beanCtx = declaringIsDependent
                        ? new io.vidocq.vauban.core.context.CreationalContextImpl<>()
                        : null;
                var beanInstance = java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        ? null : (beanCtx != null
                                ? bm.getReference(exactBean, beanClass, beanCtx)
                                : container.selectByBeanClass(beanClass));
                try {
                    if (method.getParameterCount() == 1) {
                        try {
                            container.getVaubanLookup().invokeMethod(beanInstance, method, event);
                        } catch (jakarta.enterprise.inject.CreationException ce) {
                            throw asObserverException(ce, observer);
                        }
                    } else {
                        var ctx = new io.vidocq.vauban.core.context.CreationalContextImpl<>();
                        var paramTypes = method.getParameterTypes();
                        var args = new Object[paramTypes.length];
                        var params = method.getParameters();
                        for (int i = 0; i < params.length; i++) {
                            if (params[i].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                    || params[i].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                                args[i] = event;
                            } else if (paramTypes[i] == jakarta.enterprise.inject.spi.EventMetadata.class) {
                                final Object eventObj = event;
                                // CDI spec: EventMetadata.getQualifiers() returns the qualifiers
                                // explicitly passed at fire-time plus @Any (always implicit).
                                // @Default is NOT added implicitly to EventMetadata.
                                final var metaQualifiers = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
                                if (eventQualifiers != null) {
                                    for (var q : eventQualifiers) metaQualifiers.add(q);
                                }
                                metaQualifiers.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
                                final java.util.Set<java.lang.annotation.Annotation> immutableQualifiers =
                                        java.util.Set.copyOf(metaQualifiers);
                                final java.lang.reflect.Type resolvedType = resolveRuntimeEventType(eventObj, selectedEventType);
                                args[i] = new jakarta.enterprise.inject.spi.EventMetadata() {
                                    @Override public java.util.Set<java.lang.annotation.Annotation> getQualifiers() {
                                        return immutableQualifiers;
                                    }
                                    @Override public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() {
                                        return eventInjectionPoint;
                                    }
                                    @Override public java.lang.reflect.Type getType() {
                                        return resolvedType;
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
                                args[i] = new io.vidocq.vauban.core.container.InstanceImpl<>(
                                        container, instanceType, paramQualifiers, null, ctx);
                            } else {
                                var paramQualifiers = extractQualifierAnnotations(params[i]);
                                // Resolve generic type variables for inherited observer methods
                                var resolvedParamType = resolveObserverParamType(beanClass, method, i);
                                var beans = paramQualifiers.length > 0
                                        ? bm.getBeans(resolvedParamType, paramQualifiers)
                                        : bm.getBeans(resolvedParamType);
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
                            container.getVaubanLookup().invokeMethod(beanInstance, method, args);
                        } catch (jakarta.enterprise.inject.CreationException ce) {
                            throw asObserverException(ce, observer);
                        } finally {
                            ctx.release();
                        }
                    }
                } finally {
                    if (beanCtx != null) beanCtx.release();
                }
            }
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

    /**
     * Re-maps the exception {@link io.vidocq.vauban.core.container.VaubanLookup#invokeMethod} raises
     * for an observer body. That method propagates a {@link RuntimeException} / {@link Error} from the
     * target unwrapped, but wraps a <em>checked</em> exception in a
     * {@link jakarta.enterprise.inject.CreationException}. CDI requires a checked exception from a
     * synchronous observer to surface as an {@link jakarta.enterprise.event.ObserverException}, so a
     * {@code CreationException} seen here (always wrapping the observer's own checked exception) is
     * unwrapped and rewrapped accordingly.
     */
    private static RuntimeException asObserverException(
            jakarta.enterprise.inject.CreationException ce, ObserverDescriptor observer) {
        var cause = ce.getCause();
        if (cause instanceof RuntimeException re) {
            return re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new jakarta.enterprise.event.ObserverException(
                "Observer threw a checked exception: " + observer.declaringClass().value()
                        + "." + observer.methodName(), cause != null ? cause : ce);
    }

    private Class<?> resolveObservedType(TypeInfo typeInfo) {
        try {
            var cl = container.classLoader();
            return switch (typeInfo) {
                case TypeInfo.ClassType ct -> {
                    var primitiveClass = PRIMITIVE_NAME_TO_CLASS.get(ct.name().value());
                    yield primitiveClass != null ? primitiveClass : Class.forName(ct.name().value(), true, cl);
                }
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

    private static java.lang.reflect.Type resolveObserverParamType(Class<?> beanClass, Method method, int paramIndex) {
        var typeMapping = io.vidocq.vauban.core.container.ManagedBean.buildTypeVariableMapping(beanClass);
        var genericType = method.getGenericParameterTypes()[paramIndex];
        return io.vidocq.vauban.core.container.ManagedBean.resolveType(genericType, typeMapping);
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
