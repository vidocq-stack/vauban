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
import io.vidocq.vauban.indexer.model.DotName;
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

    /**
     * An observer of a container lifecycle event threw (vauban#97). Carries the observer, as
     * {@code declaring.class.method}, because the container that fires the event cannot know which one it was.
     */
    public static final class LifecycleObserverFailure extends RuntimeException {
        private final String observer;

        LifecycleObserverFailure(String observer, RuntimeException cause) {
            super(observer, cause, false, false);
            this.observer = observer;
        }

        /** The observer that threw, as {@code declaring.class.method}. */
        public String observer() {
            return observer;
        }

        @Override
        public synchronized RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }

    /**
     * Fires a container lifecycle event as {@link #fire(Object, Annotation...)} does, CDI 4.1 §9.5 included: the
     * first observer that throws stops the notification. Its exception comes out as a
     * {@link LifecycleObserverFailure} naming that observer, so the container can say which one failed (vauban#97).
     * An {@link Error} propagates unchanged.
     */
    public void fireLifecycle(Object event, Annotation... qualifiers) {
        var matching = findMatchingObservers(event.getClass(), false, keysOf(qualifiers));
        matching.sort(Comparator.comparingInt(ObserverDescriptor::priority));
        for (var observer : matching) {
            try {
                invokeObserver(observer, event, null, null, qualifiers);
            } catch (RuntimeException e) {
                throw new LifecycleObserverFailure(observer.declaringClass() + "." + observer.methodName(), e);
            }
        }
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
            if (observer.async() == asyncOnly
                    && eventTypeMatches(observer, eventType)
                    && observerMatches(observerKeys(i), eventKeys)) {
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

        if (observer.eventType() instanceof TypeInfo.TypeVariable(_, var bounds)) {
            // CDI spec: TypeVariable observer matches any event assignable to the bounds
            return withinObservedBounds(bounds, eventClass);
        }

        Class<?> observedClass = resolveObservedType(observer.eventType());
        if (observedClass == null || !observedClass.isAssignableFrom(eventClass)) return false;

        return !(observer.eventType() instanceof TypeInfo.ParameterizedType pt)
                || matchesParameterizedObserver(pt, eventType);
    }

    /** {@code eventClass} is assignable to every bound that resolves to a class. */
    private boolean withinObservedBounds(List<TypeInfo> bounds, Class<?> eventClass) {
        for (var bound : bounds) {
            var boundClass = resolveObservedType(bound);
            if (boundClass != null && !boundClass.isAssignableFrom(eventClass)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesParameterizedObserver(TypeInfo.ParameterizedType observerType, java.lang.reflect.Type eventType) {
        Class<?> eventClass = resolveTypeToClass(eventType);
        if (eventClass == null) return false;
        try {
            var cl = container.classLoader();
            var rawObserved = Class.forName(observerType.rawType().value(), true, cl);
            var eventGenericType = parameterizedSupertype(eventType, rawObserved);
            return eventGenericType == null
                    || typeArgsMatchObserver(eventGenericType.getActualTypeArguments(), observerType.typeArguments(), cl);
        } catch (Exception _) {
            return true;
        }
    }

    /** The supertype of {@code eventType} that parameterizes {@code rawObserved}, or {@code null}. */
    private static java.lang.reflect.ParameterizedType parameterizedSupertype(
            java.lang.reflect.Type eventType, Class<?> rawObserved) {
        for (java.lang.reflect.Type supertype : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(eventType)) {
            if (supertype instanceof java.lang.reflect.ParameterizedType pt && sameRawType(pt.getRawType(), rawObserved)) {
                return pt;
            }
        }
        return null;
    }

    /**
     * The same class, or a class of the same name: the event's class and the observer's may come from two
     * class loaders or module layers, where the same class has two {@code Class} objects.
     */
    private static boolean sameRawType(java.lang.reflect.Type raw, Class<?> rawObserved) {
        return raw == rawObserved
                || (raw instanceof Class<?> rawClass && rawClass.getName().equals(rawObserved.getName()));
    }

    private static boolean typeArgsMatchObserver(java.lang.reflect.Type[] eventArgs, List<TypeInfo> observerArgs,
            ClassLoader cl) {
        if (eventArgs.length != observerArgs.size()) return false;
        for (int i = 0; i < eventArgs.length; i++) {
            if (!typeArgMatchesObserver(eventArgs[i], observerArgs.get(i), cl)) {
                return false;
            }
        }
        return true;
    }

    private static boolean typeArgMatchesObserver(java.lang.reflect.Type eventArg, TypeInfo observerArg, ClassLoader cl) {
        return switch (observerArg) {
            case TypeInfo.TypeVariable(_, var bounds) -> withinBounds(eventArg, bounds, cl);
            case TypeInfo.WildcardType(var upperBound, var lowerBound) ->
                    withinWildcard(eventArg, upperBound, lowerBound, cl);
            case TypeInfo.ClassType(DotName(var expectedName)) -> isClassNamed(eventArg, expectedName);
            case TypeInfo.ParameterizedType(DotName(var rawName), var typeArguments) ->
                    isParameterizedAs(eventArg, rawName, typeArguments, cl);
            default -> true;
        };
    }

    /** A type variable of the observer: the event's argument is assignable to every bound that resolves. */
    private static boolean withinBounds(java.lang.reflect.Type eventArg, List<TypeInfo> bounds, ClassLoader cl) {
        var eventClass = resolveTypeToClass(eventArg);
        if (eventClass == null) return true;
        for (var bound : bounds) {
            if (!assignableOrUnknown(resolveTypeInfoToClass(bound, cl), eventClass)) {
                return false;
            }
        }
        return true;
    }

    /** A wildcard of the observer: the event's argument lies between its bounds, an {@code Object} upper bound aside. */
    private static boolean withinWildcard(java.lang.reflect.Type eventArg, TypeInfo upperBound, TypeInfo lowerBound,
            ClassLoader cl) {
        if (upperBound != null && !isObject(upperBound)
                && !assignableOrUnknown(resolveTypeInfoToClass(upperBound, cl), resolveTypeToClass(eventArg))) {
            return false;
        }
        return lowerBound == null
                || assignableOrUnknown(resolveTypeToClass(eventArg), resolveTypeInfoToClass(lowerBound, cl));
    }

    private static boolean isObject(TypeInfo type) {
        return type instanceof TypeInfo.ClassType(DotName(var name)) && name.equals("java.lang.Object");
    }

    /** {@code to} is assignable from {@code from}, or one of them did not resolve to a class. */
    private static boolean assignableOrUnknown(Class<?> to, Class<?> from) {
        return to == null || from == null || to.isAssignableFrom(from);
    }

    /** A class of the observer: the event's argument is that class, or a wildcard with it as an upper bound. */
    private static boolean isClassNamed(java.lang.reflect.Type eventArg, String expectedName) {
        if (eventArg instanceof Class<?> ec) {
            return expectedName.equals(ec.getName());
        }
        if (eventArg instanceof java.lang.reflect.WildcardType ewt) {
            for (var bound : ewt.getUpperBounds()) {
                if (bound instanceof Class<?> bc && expectedName.equals(bc.getName())) return true;
            }
        }
        return false;
    }

    /** A parameterized type of the observer: the event's argument has its raw type and matching arguments. */
    private static boolean isParameterizedAs(java.lang.reflect.Type eventArg, String rawName,
            List<TypeInfo> typeArguments, ClassLoader cl) {
        return eventArg instanceof java.lang.reflect.ParameterizedType ept
                && ept.getRawType() instanceof Class<?> eventRaw
                && rawName.equals(eventRaw.getName())
                && typeArgsMatchObserver(ept.getActualTypeArguments(), typeArguments, cl);
    }

    private static Class<?> resolveTypeInfoToClass(TypeInfo typeInfo, ClassLoader cl) {
        if (typeInfo instanceof TypeInfo.ClassType(DotName(var name))) {
            try {
                return Class.forName(name, true, cl);
            } catch (Exception _) {
                return null;
            }
        }
        return null;
    }

    private static Class<?> resolveTypeToClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
        return null;
    }


    private static boolean containsTypeVariable(java.lang.reflect.Type type) {
        return switch (type) {
            case java.lang.reflect.TypeVariable<?> _ -> true;
            case java.lang.reflect.ParameterizedType pt -> anyContainsTypeVariable(pt.getActualTypeArguments());
            case java.lang.reflect.GenericArrayType gat -> containsTypeVariable(gat.getGenericComponentType());
            case java.lang.reflect.WildcardType wt ->
                    anyContainsTypeVariable(wt.getUpperBounds()) || anyContainsTypeVariable(wt.getLowerBounds());
            case null, default -> false;
        };
    }

    private static boolean anyContainsTypeVariable(java.lang.reflect.Type[] types) {
        for (var type : types) {
            if (containsTypeVariable(type)) return true;
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
        if (genSuper != null && propagateThrough(current, genSuper, target, targetMap, resolvedMap)) {
            return true;
        }

        // Try interfaces
        for (var genIface : current.getGenericInterfaces()) {
            if (propagateThrough(current, genIface, target, targetMap, resolvedMap)) {
                return true;
            }
        }
        return false;
    }

    /** Propagates through the direct supertype {@code genSuper} of {@code current}, when it leads to {@code target}. */
    private static boolean propagateThrough(Class<?> current, java.lang.reflect.Type genSuper, Class<?> target,
            java.util.Map<String, java.lang.reflect.Type> targetMap,
            java.util.Map<String, java.lang.reflect.Type> resolvedMap) {
        Class<?> superRaw = resolveTypeToClass(genSuper);
        if (superRaw != null && target.isAssignableFrom(superRaw)
                && propagateDown(superRaw, target, targetMap, resolvedMap)) {
            mapCurrentFromSuper(current, genSuper, superRaw, resolvedMap);
            return true;
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
        var direct = directGenericSupertype(clazz, targetRaw);
        if (direct != null) {
            return direct;
        }
        // Recurse through supertypes
        for (var iface : clazz.getGenericInterfaces()) {
            Class<?> rawIface = resolveTypeToClass(iface);
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

    /** The direct supertype of {@code clazz} that parameterizes {@code targetRaw}, interfaces first, or {@code null}. */
    private static java.lang.reflect.ParameterizedType directGenericSupertype(Class<?> clazz, Class<?> targetRaw) {
        for (var iface : clazz.getGenericInterfaces()) {
            if (iface instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() == targetRaw) {
                return pt;
            }
        }
        if (clazz.getGenericSuperclass() instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() == targetRaw) {
            return pt;
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
        invokeObserver(observer, event);
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
            if (lacksContextualInstance(observer, beanClass)) {
                return;
            }
            var method = findMethod(beanClass, observer.methodName(), event.getClass());
            if (method != null) {
                invokeObserverMethod(observer, beanClass, method,
                        new ObservedEvent(event, selectedEventType, eventInjectionPoint, eventQualifiers));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.event.ObserverException(
                    "Failed to invoke observer: " + observer.declaringClass().value()
                            + "." + observer.methodName(), e);
        }
    }

    /** A conditional observer ({@code IF_EXISTS}) whose bean has no contextual instance is not notified. */
    private boolean lacksContextualInstance(ObserverDescriptor observer, Class<?> beanClass) {
        if (!"IF_EXISTS".equals(observer.reception())) {
            return false;
        }
        var bm = container.getBeanManager();
        var beans = bm.getBeans(beanClass);
        if (beans.isEmpty()) {
            return false;
        }
        var bean = bm.resolve(beans);
        return bean != null
                && bm.getContext(bean.getScope()).get((jakarta.enterprise.context.spi.Contextual<?>) bean) == null;
    }

    private void invokeObserverMethod(ObserverDescriptor observer, Class<?> beanClass, Method method,
            ObservedEvent observed) {
        // Invocation goes through VaubanLookup.invokeMethod: it calls the observer in-module
        // via the module's generated provider when one owns it (no reflection, no opens), and
        // only the reflective fallback needs deep access. makeAccessible is therefore done
        // lazily inside that fallback, not eagerly here.
        var exactBean = container.findManagedBeanByExactClass(beanClass);
        boolean declaringIsDependent = exactBean != null
                && exactBean.getScope() == jakarta.enterprise.context.Dependent.class;
        var beanCtx = declaringIsDependent
                ? new io.vidocq.vauban.core.context.CreationalContextImpl<>()
                : null;
        var beanInstance = receiverOf(method, beanClass, exactBean, beanCtx);
        try {
            if (method.getParameterCount() == 1) {
                invoke(observer, beanInstance, method, observed.event());
            } else {
                invokeWithInjectedParameters(observer, beanClass, method, beanInstance, observed);
            }
        } finally {
            if (beanCtx != null) beanCtx.release();
        }
    }

    /** The instance the observer is called on: none for a static method, a new one for a dependent bean. */
    private Object receiverOf(Method method, Class<?> beanClass,
            io.vidocq.vauban.core.container.ManagedBean<?> exactBean,
            io.vidocq.vauban.core.context.CreationalContextImpl<?> beanCtx) {
        if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        return beanCtx != null
                ? container.getBeanManager().getReference(exactBean, beanClass, beanCtx)
                : container.selectByBeanClass(beanClass);
    }

    private void invokeWithInjectedParameters(ObserverDescriptor observer, Class<?> beanClass, Method method,
            Object beanInstance, ObservedEvent observed) {
        var ctx = new io.vidocq.vauban.core.context.CreationalContextImpl<>();
        var args = observerArguments(observer, beanClass, method, observed, ctx);
        try {
            invoke(observer, beanInstance, method, args);
        } finally {
            ctx.release();
        }
    }

    private void invoke(ObserverDescriptor observer, Object beanInstance, Method method, Object... args) {
        try {
            container.getVaubanLookup().invokeMethod(beanInstance, method, args);
        } catch (jakarta.enterprise.inject.CreationException ce) {
            throw asObserverException(ce, observer);
        }
    }

    /** The arguments of an observer method: the event, its metadata, then injected beans and instances. */
    private Object[] observerArguments(ObserverDescriptor observer, Class<?> beanClass, Method method,
            ObservedEvent observed, io.vidocq.vauban.core.context.CreationalContextImpl<Object> ctx) {
        var paramTypes = method.getParameterTypes();
        var args = new Object[paramTypes.length];
        var params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (isEventParameter(params[i])) {
                args[i] = observed.event();
            } else if (paramTypes[i] == jakarta.enterprise.inject.spi.EventMetadata.class) {
                args[i] = observed.metadata();
            } else if (paramTypes[i] == jakarta.enterprise.inject.Instance.class
                    || paramTypes[i] == jakarta.inject.Provider.class) {
                args[i] = instanceArgument(observer, method, i, params[i], ctx);
            } else {
                args[i] = beanArgument(observer, beanClass, method, i, params[i], ctx);
            }
        }
        return args;
    }

    private static boolean isEventParameter(java.lang.reflect.Parameter param) {
        return param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class);
    }

    private Object instanceArgument(ObserverDescriptor observer, Method method, int index,
            java.lang.reflect.Parameter param, io.vidocq.vauban.core.context.CreationalContextImpl<Object> ctx) {
        Class<?> instanceType = Object.class;
        var genericType = method.getGenericParameterTypes()[index];
        if (genericType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length > 0
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            instanceType = c;
        }
        var paramQualifiers = observerParamQualifiers(observer, method, index, param);
        return new io.vidocq.vauban.core.container.InstanceImpl<>(
                container, instanceType, paramQualifiers, null, ctx);
    }

    private Object beanArgument(ObserverDescriptor observer, Class<?> beanClass, Method method, int index,
            java.lang.reflect.Parameter param, io.vidocq.vauban.core.context.CreationalContextImpl<Object> ctx) {
        var bm = container.getBeanManager();
        var paramQualifiers = observerParamQualifiers(observer, method, index, param);
        // Resolve generic type variables for inherited observer methods
        var resolvedParamType = resolveObserverParamType(beanClass, method, index);
        var beans = paramQualifiers.length > 0
                ? bm.getBeans(resolvedParamType, paramQualifiers)
                : bm.getBeans(resolvedParamType);
        if (beans.isEmpty()) {
            return container.resolveParameter(param.getType(), method.getGenericParameterTypes()[index]);
        }
        return bm.getReference(bm.resolve(beans), param.getType(), ctx);
    }

    /** An event being delivered, with what an {@link jakarta.enterprise.inject.spi.EventMetadata} parameter tells of it. */
    private static final class ObservedEvent {
        private final Object event;
        private final java.lang.reflect.Type selectedType;
        private final jakarta.enterprise.inject.spi.InjectionPoint injectionPoint;
        private final Annotation[] qualifiers;

        ObservedEvent(Object event, java.lang.reflect.Type selectedType,
                jakarta.enterprise.inject.spi.InjectionPoint injectionPoint, Annotation[] qualifiers) {
            this.event = event;
            this.selectedType = selectedType;
            this.injectionPoint = injectionPoint;
            this.qualifiers = qualifiers;
        }

        Object event() {
            return event;
        }

        jakarta.enterprise.inject.spi.EventMetadata metadata() {
            // CDI spec: EventMetadata.getQualifiers() returns the qualifiers
            // explicitly passed at fire-time plus @Any (always implicit).
            // @Default is NOT added implicitly to EventMetadata.
            final var metaQualifiers = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
            if (qualifiers != null) {
                Collections.addAll(metaQualifiers, qualifiers);
            }
            metaQualifiers.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
            final java.util.Set<java.lang.annotation.Annotation> immutableQualifiers =
                    java.util.Set.copyOf(metaQualifiers);
            final java.lang.reflect.Type resolvedType = resolveRuntimeEventType(event, selectedType);
            final var eventInjectionPoint = injectionPoint;
            return new jakarta.enterprise.inject.spi.EventMetadata() {
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
                case TypeInfo.ClassType(DotName(var name)) -> {
                    var primitiveClass = PRIMITIVE_NAME_TO_CLASS.get(name);
                    yield primitiveClass != null ? primitiveClass : Class.forName(name, true, cl);
                }
                case TypeInfo.ParameterizedType(DotName(var rawName), _) -> Class.forName(rawName, true, cl);
                case TypeInfo.ArrayType(var componentType, _) -> {
                    var component = resolveObservedType(componentType);
                    yield component != null
                            ? java.lang.reflect.Array.newInstance(component, 0).getClass()
                            : null;
                }
                case TypeInfo.PrimitiveType(var kind) -> switch (kind) {
                    case BOOLEAN -> boolean.class;
                    case BYTE -> byte.class;
                    case CHAR -> char.class;
                    case SHORT -> short.class;
                    case INT -> int.class;
                    case LONG -> long.class;
                    case FLOAT -> float.class;
                    case DOUBLE -> double.class;
                };
                case TypeInfo.TypeVariable(_, var bounds) ->
                        bounds.isEmpty() ? Object.class : resolveObservedType(bounds.getFirst());
                case TypeInfo.WildcardType(var upperBound, _) ->
                        upperBound == null ? Object.class : resolveObservedType(upperBound);
                default -> null;
            };
        } catch (ClassNotFoundException _) {
            return null;
        }
    }

    private static java.lang.reflect.Type resolveObserverParamType(Class<?> beanClass, Method method, int paramIndex) {
        var typeMapping = io.vidocq.vauban.core.container.ManagedBean.buildTypeVariableMapping(beanClass);
        var genericType = method.getGenericParameterTypes()[paramIndex];
        return io.vidocq.vauban.core.container.ManagedBean.resolveType(genericType, typeMapping);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?> eventType) {
        var current = clazz;
        while (current != null && current != Object.class) {
            var method = declaredObserverMethod(current, name, eventType);
            if (method != null) {
                return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /** The method {@code name} that {@code type} declares and that observes {@code eventType}, or {@code null}. */
    private static Method declaredObserverMethod(Class<?> type, String name, Class<?> eventType) {
        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() >= 1 && observes(method, eventType)) {
                return method;
            }
        }
        return null;
    }

    /** Its event parameter accepts {@code eventType} or is a type variable; else its first parameter accepts it. */
    private static boolean observes(Method method, Class<?> eventType) {
        var params = method.getParameters();
        for (int p = 0; p < params.length; p++) {
            if (isEventParameter(params[p])
                    && (params[p].getType().isAssignableFrom(eventType)
                            || method.getGenericParameterTypes()[p] instanceof java.lang.reflect.TypeVariable<?>)) {
                return true;
            }
        }
        return method.getParameterTypes()[0].isAssignableFrom(eventType);
    }

    /**
     * The qualifiers of an observer's non-event parameter. The declaring bean's descriptor records
     * them (CDI 4.1 §10.4.3 makes them injection points), so nothing is read off the method; a
     * parameter no descriptor describes — a synthetic observer's — still falls back on the method.
     */
    private java.lang.annotation.Annotation[] observerParamQualifiers(
            io.vidocq.vauban.core.bean.model.ObserverDescriptor observer,
            java.lang.reflect.Method method, int index, java.lang.reflect.Parameter param) {
        var described = container.describedParameterQualifiers(
                container.managedDescriptor(observer.declaringClass()), method, index);
        if (described == null) {
            return extractQualifierAnnotations(param);
        }
        return described;
    }

    private static java.lang.annotation.Annotation[] extractQualifierAnnotations(java.lang.reflect.Parameter param) {
        io.vidocq.vauban.core.annotation.AnnotationReflection.checkParameter(param);
        var quals = new java.util.ArrayList<java.lang.annotation.Annotation>();
        for (var ann : param.getAnnotations()) {
            var type = ann.annotationType();
            if (type != jakarta.enterprise.event.Observes.class
                    && type != jakarta.enterprise.event.ObservesAsync.class
                    && type != jakarta.enterprise.inject.TransientReference.class
                    && type.isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                quals.add(ann);
            }
        }
        return quals.toArray(new java.lang.annotation.Annotation[0]);
    }
}
