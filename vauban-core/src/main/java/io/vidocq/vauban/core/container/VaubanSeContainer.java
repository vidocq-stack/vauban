package io.vidocq.vauban.core.container;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.stream.Stream;

public final class VaubanSeContainer implements SeContainer {

    private final VaubanContainer container;
    private final InstanceImpl<Object> rootInstance;

    VaubanSeContainer(VaubanContainer container) {
        this.container = container;
        this.rootInstance = new InstanceImpl<>(container, Object.class);
    }

    @Override
    public void close() {
        if (!container.isRunning()) {
            throw new IllegalStateException("Container is already shut down");
        }
        container.close();
    }

    @Override
    public boolean isRunning() {
        return container.isRunning();
    }

    @Override
    public BeanManager getBeanManager() {
        if (!container.isRunning()) {
            throw new IllegalStateException("Container is already shut down");
        }
        return container.getBeanManager();
    }

    @Override
    public Instance<Object> select(Annotation... qualifiers) {
        return rootInstance.select(qualifiers);
    }

    @Override
    public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return rootInstance.select(subtype, qualifiers);
    }

    @Override
    public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return rootInstance.select(subtype, qualifiers);
    }

    @Override
    public boolean isUnsatisfied() {
        return rootInstance.isUnsatisfied();
    }

    @Override
    public boolean isAmbiguous() {
        return rootInstance.isAmbiguous();
    }

    @Override
    public boolean isResolvable() {
        return rootInstance.isResolvable();
    }

    @Override
    public Object get() {
        return rootInstance.get();
    }

    @Override
    public void destroy(Object instance) {
        rootInstance.destroy(instance);
    }

    @Override
    public Handle<Object> getHandle() {
        return rootInstance.getHandle();
    }

    @Override
    public Iterable<? extends Handle<Object>> handles() {
        return rootInstance.handles();
    }

    @Override
    public Stream<Object> stream() {
        return rootInstance.stream();
    }

    @Override
    public Iterator<Object> iterator() {
        return rootInstance.iterator();
    }
}
