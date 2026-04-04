package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import fr.vidocq.vauban.core.context.CreationalContextImpl;

/**
 * Implementation of {@link Instance} for programmatic bean lookup.
 * Delegates resolution to the {@link VaubanContainer} and its {@link VaubanBeanManager}.
 */
public final class InstanceImpl<T> implements Instance<T> {

    private final VaubanContainer container;
    private final Class<T> type;
    private final Annotation[] qualifiers;
    private final jakarta.enterprise.inject.spi.InjectionPoint injectionPoint;
    private final CreationalContextImpl<?> parentCreationalContext;
    private final Map<Object, jakarta.enterprise.context.spi.CreationalContext<?>> dependentInstances = new IdentityHashMap<>();

    public InstanceImpl(VaubanContainer container, Class<T> type) {
        this(container, type, new Annotation[0], null, null);
    }

    public InstanceImpl(VaubanContainer container, Class<T> type, jakarta.enterprise.inject.spi.InjectionPoint injectionPoint) {
        this(container, type, new Annotation[0], injectionPoint, null);
    }

    public InstanceImpl(VaubanContainer container, Class<T> type, Annotation[] qualifiers, jakarta.enterprise.inject.spi.InjectionPoint injectionPoint) {
        this(container, type, qualifiers, injectionPoint, null);
    }

    public InstanceImpl(VaubanContainer container, Class<T> type, Annotation[] qualifiers,
                        jakarta.enterprise.inject.spi.InjectionPoint injectionPoint,
                        CreationalContextImpl<?> parentCreationalContext) {
        this.container = container;
        this.type = type;
        this.injectionPoint = injectionPoint;
        this.parentCreationalContext = parentCreationalContext;
        // If qualifiers are empty, default to @Any and @Default
        if (qualifiers == null || qualifiers.length == 0) {
            this.qualifiers = new Annotation[] {
                jakarta.enterprise.inject.Any.Literal.INSTANCE,
                jakarta.enterprise.inject.Default.Literal.INSTANCE
            };
        } else {
            // Ensure @Any is present (CDI 4.1 Section 2.3.1)
            var set = new java.util.LinkedHashSet<Annotation>(java.util.Arrays.asList(qualifiers));
            set.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
            // DO NOT add @Default when explicit qualifiers are present.
            // @Any alone means "wildcard matching" — adding @Default would restrict resolution.
            this.qualifiers = set.toArray(new Annotation[0]);
        }
    }

    @Override
    public T get() {
        var bm = container.getBeanManager();
        var beans = bm.getBeans(type, qualifiers);
        if (beans.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No bean found for type: " + type.getName() + " with qualifiers: " + Arrays.toString(qualifiers));
        }
        @SuppressWarnings("unchecked")
        var bean = (Bean<T>) bm.resolve(beans);

        var previousIp = VaubanContainer.getCurrentInjectionPoint();
        if (injectionPoint != null) {
            VaubanContainer.setInjectionPoint(injectionPoint);
        }
        try {
            var ctx = bm.createCreationalContext(bean);
            @SuppressWarnings("unchecked")
            var ref = (T) bm.getReference(bean, type, ctx);
            if (ref != null && bean.getScope() == jakarta.enterprise.context.Dependent.class) {
                dependentInstances.put(ref, ctx);
                if (parentCreationalContext != null) {
                    parentCreationalContext.addDependentInstance(bean, ref, ctx);
                }
            }
            return ref;
        } finally {
            VaubanContainer.setInjectionPoint(previousIp);
        }
    }

    @Override
    public Instance<T> select(Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        return new InstanceImpl<>(container, type, combineQualifiers(this.qualifiers, newQualifiers), injectionPoint, parentCreationalContext);
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        var combinedQuals = combineQualifiers(this.qualifiers, newQualifiers);
        var updatedIp = updateInjectionPointType(subtype, combinedQuals);
        return new InstanceImpl<>(container, subtype, combinedQuals, updatedIp, parentCreationalContext);
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        @SuppressWarnings("unchecked")
        var clazz = (Class<U>) subtype.getType();
        var combinedQuals = combineQualifiers(this.qualifiers, newQualifiers);
        var updatedIp = updateInjectionPointType(clazz, combinedQuals);
        return new InstanceImpl<>(container, clazz, combinedQuals, updatedIp, parentCreationalContext);
    }

    private jakarta.enterprise.inject.spi.InjectionPoint updateInjectionPointType(
            Class<?> newType, Annotation[] newQualifiers) {
        if (injectionPoint == null) return null;
        var qualSet = new java.util.LinkedHashSet<Annotation>();
        for (var q : newQualifiers) qualSet.add(q);
        if (qualSet.isEmpty()) qualSet.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        return new VaubanInjectionPoint(newType, qualSet, injectionPoint.getBean(), injectionPoint.getMember());
    }

    private static Annotation[] combineQualifiers(Annotation[] existing, Annotation[] additional) {
        if (additional == null || additional.length == 0) return existing;
        if (existing == null || existing.length == 0) {
            var set = new java.util.LinkedHashSet<Annotation>(java.util.Arrays.asList(additional));
            set.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
            return set.toArray(new Annotation[0]);
        }

        var result = new java.util.LinkedHashSet<Annotation>(java.util.Arrays.asList(existing));
        for (var q : additional) {
            // Remove @Default if we're adding a non-@Any qualifier
            if (q.annotationType() != jakarta.enterprise.inject.Any.class
                    && q.annotationType() != jakarta.enterprise.inject.Default.class) {
                result.removeIf(ann -> ann.annotationType() == jakarta.enterprise.inject.Default.class);
            }
            // Remove existing same-type qualifier to avoid duplicates
            result.removeIf(ann -> ann.annotationType() == q.annotationType());
            result.add(q);
        }
        result.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        return result.toArray(new Annotation[0]);
    }

    private static void validateQualifiers(Annotation... qualifiers) {
        if (qualifiers == null) return;
        var seen = new java.util.HashSet<Class<?>>();
        for (var q : qualifiers) {
            if (!q.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    && q.annotationType() != jakarta.enterprise.inject.Default.class
                    && q.annotationType() != jakarta.enterprise.inject.Any.class
                    && q.annotationType() != jakarta.inject.Named.class
                    && !VaubanBeanManager.isCustomQualifier(q.annotationType())) {
                throw new IllegalArgumentException(
                        q.annotationType().getName() + " is not a qualifier");
            }
            if (!seen.add(q.annotationType())
                    && !q.annotationType().isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                throw new IllegalArgumentException(
                        "Duplicate qualifier: " + q.annotationType().getName());
            }
        }
    }

    @Override
    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public boolean isUnsatisfied() {
        return resolveCount() == 0;
    }

    @Override
    public boolean isAmbiguous() {
        var beans = getEffectiveBeans();
        if (beans.size() <= 1) return false;
        try {
            container.getBeanManager().resolve(beans);
            return false;
        } catch (jakarta.enterprise.inject.AmbiguousResolutionException e) {
            return true;
        }
    }

    @Override
    public boolean isResolvable() {
        var beans = container.getBeanManager().getBeans(type, qualifiers);
        if (beans.isEmpty()) return false;
        if (beans.size() == 1) return true;
        try {
            container.getBeanManager().resolve(beans);
            return true;
        } catch (jakarta.enterprise.inject.AmbiguousResolutionException e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy(T instance) {
        if (instance == null) throw new NullPointerException("Instance to destroy must not be null");
        var bm = container.getBeanManager();
        var beans = bm.getBeans(type, qualifiers);
        if (beans.isEmpty()) return;
        var bean = (Bean<T>) bm.resolve(beans);
        var scope = bean.getScope();
        if (scope == jakarta.enterprise.context.Dependent.class) {
            var trackedCtx = dependentInstances.remove(instance);
            if (trackedCtx != null) {
                trackedCtx.release();
            } else {
                bean.destroy(instance, bm.createCreationalContext(bean));
            }
        } else {
            try {
                var ctx = bm.getContext(scope);
                if (ctx instanceof jakarta.enterprise.context.spi.AlterableContext ac) {
                    ac.destroy((jakarta.enterprise.context.spi.Contextual<?>) bean);
                }
            } catch (Exception e) {
                // Best effort -- context may not be active
            }
        }
    }

    @Override
    public Handle<T> getHandle() {
        var bm = container.getBeanManager();
        var beans = bm.getBeans(type, qualifiers);
        if (beans.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No bean found for type: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        var bean = (Bean<T>) bm.resolve(beans);
        return new HandleImpl<>(bean, type, container, injectionPoint);
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        var bm = container.getBeanManager();
        var beans = getEffectiveBeans();
        var result = new ArrayList<Handle<T>>();
        for (var b : beans) {
            @SuppressWarnings("unchecked")
            var bean = (Bean<T>) b;
            result.add(new HandleImpl<>(bean, type, container, injectionPoint));
        }
        return result;
    }

    @Override
    public Stream<? extends Handle<T>> handlesStream() {
        var handles = handles();
        return StreamSupport.stream(handles.spliterator(), false);
    }

    @Override
    public Iterator<T> iterator() {
        var bm = container.getBeanManager();
        var beans = getEffectiveBeans();
        var instances = new ArrayList<T>();
        
        var previousIp = VaubanContainer.getCurrentInjectionPoint();
        if (injectionPoint != null) {
            VaubanContainer.setInjectionPoint(injectionPoint);
        }
        try {
            for (var b : beans) {
                @SuppressWarnings("unchecked")
                var bean = (Bean<T>) b;
                var ctx = bm.createCreationalContext(bean);
                @SuppressWarnings("unchecked")
                var ref = (T) bm.getReference(bean, type, ctx);
                instances.add(ref);
            }
        } finally {
            VaubanContainer.setInjectionPoint(previousIp);
        }
        return instances.iterator();
    }

    private int resolveCount() {
        return getEffectiveBeans().size();
    }

    /**
     * CDI spec: when alternatives with @Priority are present, non-alternative beans are excluded.
     * Only the highest-priority alternative(s) are returned.
     */
    private Set<Bean<?>> getEffectiveBeans() {
        var beans = container.getBeanManager().getBeans(type, qualifiers);
        if (beans.size() <= 1) return beans;
        // Check if any enabled alternatives are present
        var alternatives = beans.stream()
                .filter(Bean::isAlternative)
                .toList();
        if (alternatives.isEmpty()) return beans;
        // Only keep the highest-priority alternative(s)
        int maxPriority = alternatives.stream()
                .mapToInt(b -> b instanceof ManagedBean<?> mb ? mb.descriptor().priority() : 0)
                .max().orElse(0);
        var best = alternatives.stream()
                .filter(b -> {
                    int p = b instanceof ManagedBean<?> mb ? mb.descriptor().priority() : 0;
                    return p == maxPriority;
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return best;
    }

    /**
     * Implementation of {@link Handle} wrapping a bean instance and its metadata.
     */
    private static final class HandleImpl<T> implements Handle<T> {
        private final Bean<T> bean;
        private final Class<T> type;
        private final VaubanContainer container;
        private final jakarta.enterprise.inject.spi.InjectionPoint injectionPoint;
        private T instance;
        private jakarta.enterprise.context.spi.CreationalContext<T> creationalContext;
        private boolean destroyed;

        HandleImpl(Bean<T> bean, Class<T> type, VaubanContainer container, jakarta.enterprise.inject.spi.InjectionPoint injectionPoint) {
            this.bean = bean;
            this.type = type;
            this.container = container;
            this.injectionPoint = injectionPoint;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T get() {
            if (destroyed) {
                throw new IllegalStateException("Handle has been destroyed");
            }
            if (instance == null) {
                var previousIp = VaubanContainer.getCurrentInjectionPoint();
                if (injectionPoint != null) {
                    VaubanContainer.setInjectionPoint(injectionPoint);
                }
                try {
                    var bm = container.getBeanManager();
                    creationalContext = bm.createCreationalContext(bean);
                    instance = (T) bm.getReference(bean, type, creationalContext);
                } finally {
                    VaubanContainer.setInjectionPoint(previousIp);
                }
            }
            return instance;
        }

        @Override
        public Bean<T> getBean() {
            return bean;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void destroy() {
            if (destroyed) return;
            destroyed = true;
            if (instance == null) return;
            var scope = bean.getScope();
            if (scope == jakarta.enterprise.context.Dependent.class) {
                if (creationalContext != null) {
                    creationalContext.release();
                }
            } else {
                try {
                    var bm = container.getBeanManager();
                    var ctx = bm.getContext(scope);
                    if (ctx instanceof jakarta.enterprise.context.spi.AlterableContext ac) {
                        ac.destroy((jakarta.enterprise.context.spi.Contextual<?>) bean);
                    }
                } catch (Exception e) {
                    // Best effort
                }
            }
            instance = null;
        }

        @Override
        public void close() {
            destroy();
        }
    }
}
