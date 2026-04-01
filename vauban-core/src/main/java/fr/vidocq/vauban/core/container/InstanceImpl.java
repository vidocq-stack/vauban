package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Implementation of {@link Instance} for programmatic bean lookup.
 * Delegates resolution to the {@link VaubanContainer} and its {@link VaubanBeanManager}.
 */
public final class InstanceImpl<T> implements Instance<T> {

    private final VaubanContainer container;
    private final Class<T> type;
    private final Annotation[] qualifiers;

    public InstanceImpl(VaubanContainer container, Class<T> type) {
        this(container, type, new Annotation[0]);
    }

    private InstanceImpl(VaubanContainer container, Class<T> type, Annotation[] qualifiers) {
        this.container = container;
        this.type = type;
        this.qualifiers = qualifiers;
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
        var ctx = bm.createCreationalContext(bean);
        @SuppressWarnings("unchecked")
        var ref = (T) bm.getReference(bean, type, ctx);
        return ref;
    }

    @Override
    public Instance<T> select(Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        return new InstanceImpl<>(container, type, combineQualifiers(this.qualifiers, newQualifiers));
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        return new InstanceImpl<>(container, subtype, combineQualifiers(this.qualifiers, newQualifiers));
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        @SuppressWarnings("unchecked")
        var clazz = (Class<U>) subtype.getType();
        return new InstanceImpl<>(container, clazz, combineQualifiers(this.qualifiers, newQualifiers));
    }

    private static Annotation[] combineQualifiers(Annotation[] existing, Annotation[] additional) {
        if (additional == null || additional.length == 0) return existing;
        if (existing.length == 0) return additional;
        var combined = Arrays.copyOf(existing, existing.length + additional.length);
        System.arraycopy(additional, 0, combined, existing.length, additional.length);
        return combined;
    }

    private static void validateQualifiers(Annotation... qualifiers) {
        if (qualifiers == null) return;
        var seen = new HashSet<Class<?>>();
        for (var q : qualifiers) {
            if (!q.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    && q.annotationType() != jakarta.enterprise.inject.Default.class
                    && q.annotationType() != jakarta.enterprise.inject.Any.class
                    && q.annotationType() != jakarta.inject.Named.class) {
                throw new IllegalArgumentException(
                        q.annotationType().getName() + " is not a qualifier");
            }
            if (!seen.add(q.annotationType())) {
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
        var beans = container.getBeanManager().getBeans(type, qualifiers);
        if (beans.size() <= 1) return false;
        try {
            container.getBeanManager().resolve(beans);
            return false; // resolved successfully — not ambiguous
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
        if (instance == null) return;
        var bm = container.getBeanManager();
        var beans = bm.getBeans(type, qualifiers);
        if (beans.isEmpty()) return;
        var bean = (Bean<T>) bm.resolve(beans);
        // For normal-scoped beans, use AlterableContext.destroy()
        var scope = bean.getScope();
        try {
            var ctx = bm.getContext(scope);
            if (ctx instanceof jakarta.enterprise.context.spi.AlterableContext ac) {
                ac.destroy((jakarta.enterprise.context.spi.Contextual<?>) bean);
            }
        } catch (Exception e) {
            // Best effort — context may not be active
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
        var ctx = bm.createCreationalContext(bean);
        @SuppressWarnings("unchecked")
        var ref = (T) bm.getReference(bean, type, ctx);
        return new HandleImpl<>(ref, bean, container);
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        var bm = container.getBeanManager();
        var beans = bm.getBeans(type, qualifiers);
        var result = new ArrayList<Handle<T>>();
        for (var b : beans) {
            @SuppressWarnings("unchecked")
            var bean = (Bean<T>) b;
            var ctx = bm.createCreationalContext(bean);
            @SuppressWarnings("unchecked")
            var ref = (T) bm.getReference(bean, type, ctx);
            result.add(new HandleImpl<>(ref, bean, container));
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
        var beans = bm.getBeans(type, qualifiers);
        var instances = new ArrayList<T>();
        for (var b : beans) {
            @SuppressWarnings("unchecked")
            var bean = (Bean<T>) b;
            var ctx = bm.createCreationalContext(bean);
            @SuppressWarnings("unchecked")
            var ref = (T) bm.getReference(bean, type, ctx);
            instances.add(ref);
        }
        return instances.iterator();
    }

    private int resolveCount() {
        return container.getBeanManager().getBeans(type, qualifiers).size();
    }

    /**
     * Implementation of {@link Handle} wrapping a bean instance and its metadata.
     */
    private static final class HandleImpl<T> implements Handle<T> {
        private final T instance;
        private final Bean<T> bean;
        private final VaubanContainer container;

        HandleImpl(T instance, Bean<T> bean, VaubanContainer container) {
            this.instance = instance;
            this.bean = bean;
            this.container = container;
        }

        @Override
        public T get() {
            return instance;
        }

        @Override
        public Bean<T> getBean() {
            return bean;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void destroy() {
            try {
                var bm = container.getBeanManager();
                var ctx = bm.getContext(bean.getScope());
                if (ctx instanceof jakarta.enterprise.context.spi.AlterableContext ac) {
                    ac.destroy((jakarta.enterprise.context.spi.Contextual<?>) bean);
                }
            } catch (Exception e) {
                // Best effort
            }
        }

        @Override
        public void close() {
            destroy();
        }
    }
}
