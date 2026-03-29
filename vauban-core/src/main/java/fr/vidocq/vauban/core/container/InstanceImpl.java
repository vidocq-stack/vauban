package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Implementation of {@link Instance} for programmatic bean lookup.
 * Delegates resolution to the {@link VaubanContainer} and its {@link VaubanBeanManager}.
 */
public final class InstanceImpl<T> implements Instance<T> {

    private final VaubanContainer container;
    private final Class<T> type;

    public InstanceImpl(VaubanContainer container, Class<T> type) {
        this.container = container;
        this.type = type;
    }

    @Override
    public T get() {
        return container.select(type);
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        validateQualifiers(qualifiers);
        return this;
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        validateQualifiers(qualifiers);
        return new InstanceImpl<>(container, subtype);
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        validateQualifiers(qualifiers);
        @SuppressWarnings("unchecked")
        var clazz = (Class<U>) subtype.getType();
        return new InstanceImpl<>(container, clazz);
    }

    private static void validateQualifiers(Annotation... qualifiers) {
        if (qualifiers == null) return;
        var seen = new java.util.HashSet<Class<?>>();
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
        return resolveCount() > 1;
    }

    @Override
    public boolean isResolvable() {
        return resolveCount() == 1;
    }

    @Override
    public void destroy(T instance) {
        // No-op for now — dependent context cleanup not yet implemented
    }

    @Override
    public Handle<T> getHandle() {
        var bm = container.getBeanManager();
        var beans = bm.getBeans(type);
        if (beans.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No bean found for type: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        var bean = (Bean<T>) bm.resolve(beans);
        var ctx = bm.createCreationalContext(bean);
        @SuppressWarnings("unchecked")
        var ref = (T) bm.getReference(bean, type, ctx);
        return new HandleImpl<>(ref, bean);
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        var bm = container.getBeanManager();
        var beans = bm.getBeans(type);
        var result = new ArrayList<Handle<T>>();
        for (var b : beans) {
            @SuppressWarnings("unchecked")
            var bean = (Bean<T>) b;
            var ctx = bm.createCreationalContext(bean);
            @SuppressWarnings("unchecked")
            var ref = (T) bm.getReference(bean, type, ctx);
            result.add(new HandleImpl<>(ref, bean));
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
        var beans = bm.getBeans(type);
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
        return container.getBeanManager().getBeans(type).size();
    }

    /**
     * Implementation of {@link Handle} wrapping a bean instance and its metadata.
     */
    private record HandleImpl<T>(T instance, Bean<T> bean) implements Handle<T> {

        @Override
        public T get() {
            return instance;
        }

        @Override
        public Bean<T> getBean() {
            return bean;
        }

        @Override
        public void destroy() {
            // No-op for now
        }

        @Override
        public void close() {
            destroy();
        }
    }
}
