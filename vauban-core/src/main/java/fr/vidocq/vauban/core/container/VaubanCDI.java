package fr.vidocq.vauban.core.container;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Vauban implementation of {@link CDI}, providing programmatic access
 * to the running CDI container via {@code CDI.current()}.
 */
public class VaubanCDI extends CDI<Object> {

    static final VaubanCDI INSTANCE = new VaubanCDI();

    @Override
    public BeanManager getBeanManager() {
        return getContainer().getBeanManager();
    }

    @Override
    public Instance<Object> select(Annotation... qualifiers) {
        return new InstanceImpl<>(getContainer(), Object.class);
    }

    @Override
    public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new InstanceImpl<>(getContainer(), subtype);
    }

    @Override
    public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        @SuppressWarnings("unchecked")
        var clazz = (Class<U>) subtype.getType();
        return new InstanceImpl<>(getContainer(), clazz);
    }

    @Override
    public boolean isUnsatisfied() {
        return false;
    }

    @Override
    public boolean isAmbiguous() {
        return false;
    }

    @Override
    public boolean isResolvable() {
        return true;
    }

    @Override
    public Object get() {
        throw new IllegalStateException("Cannot call get() on CDI root instance");
    }

    @Override
    public void destroy(Object instance) {
        // No-op
    }

    @Override
    public Handle<Object> getHandle() {
        throw new UnsupportedOperationException("getHandle() not supported on CDI root instance");
    }

    @Override
    public Iterable<? extends Handle<Object>> handles() {
        return List.of();
    }

    @Override
    public Stream<Object> stream() {
        return Stream.empty();
    }

    @Override
    public Iterator<Object> iterator() {
        return Collections.emptyIterator();
    }

    private static VaubanContainer getContainer() {
        var container = VaubanContainer.current();
        if (container == null) {
            throw new IllegalStateException("No Vauban CDI container is running");
        }
        return container;
    }
}
