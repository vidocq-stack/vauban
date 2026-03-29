package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.ScopeInfo;
import fr.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * CDI Bean implementation backed by a BeanDescriptor and a BeanFactory.
 */
public final class ManagedBean<T> implements Bean<T> {

    private final BeanDescriptor descriptor;
    private final BeanFactory<T> factory;
    private final Class<T> beanClass;
    private Consumer<Object> injector;

    @SuppressWarnings("unchecked")
    public ManagedBean(BeanDescriptor descriptor, BeanFactory<T> factory) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.factory = Objects.requireNonNull(factory);
        try {
            this.beanClass = (Class<T>) Class.forName(descriptor.beanClass().value());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Bean class not found: " + descriptor.beanClass(), e);
        }
    }

    /**
     * Sets the injector callback that will be called after instance creation
     * to resolve and inject @Inject fields.
     */
    public void setInjector(Consumer<Object> injector) {
        this.injector = injector;
    }

    @Override
    public T create(CreationalContext<T> creationalContext) {
        T instance = factory.create();
        if (injector != null) {
            injector.accept(instance);
        }
        return instance;
    }

    @Override
    public void destroy(T instance, CreationalContext<T> creationalContext) {
        if (creationalContext != null) {
            creationalContext.release();
        }
    }

    @Override
    public Class<?> getBeanClass() {
        return beanClass;
    }

    @Override
    public Set<Type> getTypes() {
        return Set.of(beanClass, Object.class);
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return Set.of();
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return scopeAnnotationClass(descriptor.scope());
    }

    @Override
    public String getName() {
        return descriptor.name();
    }

    @Override
    public Set<Class<? extends Annotation>> getStereotypes() {
        return Set.of();
    }

    @Override
    public boolean isAlternative() {
        return descriptor.isAlternative();
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
        return Set.of();
    }

    public BeanDescriptor descriptor() {
        return descriptor;
    }

    private static Class<? extends Annotation> scopeAnnotationClass(ScopeInfo scope) {
        var name = scope.annotationName().value();
        try {
            @SuppressWarnings("unchecked")
            var clazz = (Class<? extends Annotation>) Class.forName(name);
            return clazz;
        } catch (ClassNotFoundException e) {
            return jakarta.enterprise.context.Dependent.class;
        }
    }
}
