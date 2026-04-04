package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.types.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects synthetic bean and observer definitions during @Synthesis phase.
 */
public final class VaubanSyntheticComponents implements SyntheticComponents {

    private final List<VaubanSyntheticBeanBuilder<?>> beanDefinitions = new ArrayList<>();
    private final List<VaubanSyntheticObserverBuilder<?>> observerDefinitions = new ArrayList<>();

    @Override
    public <T> SyntheticBeanBuilder<T> addBean(Class<T> implementationClass) {
        var builder = new VaubanSyntheticBeanBuilder<>(implementationClass);
        beanDefinitions.add(builder);
        return builder;
    }

    @Override
    public <T> SyntheticObserverBuilder<T> addObserver(Class<T> eventType) {
        var builder = new VaubanSyntheticObserverBuilder<>(eventType);
        observerDefinitions.add(builder);
        return builder;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> SyntheticObserverBuilder<T> addObserver(Type eventType) {
        var builder = new VaubanSyntheticObserverBuilder<T>(eventType);
        observerDefinitions.add(builder);
        return builder;
    }

    public List<VaubanSyntheticBeanBuilder<?>> getBeanDefinitions() {
        return beanDefinitions;
    }

    public List<VaubanSyntheticObserverBuilder<?>> getObserverDefinitions() {
        return observerDefinitions;
    }
}
