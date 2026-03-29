package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.context.CreationalContextImpl;
import fr.vidocq.vauban.core.event.EventDispatcher;
import fr.vidocq.vauban.core.event.EventImpl;
import fr.vidocq.vauban.core.event.VaubanObserverMethod;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Minimal BeanManager implementation for Vauban.
 * Only the essential methods are functional; the rest throw UnsupportedOperationException.
 */
public final class VaubanBeanManager implements BeanManager {

    private final VaubanContainer container;
    private final Map<Class<? extends Annotation>, Context> contexts;
    private final Collection<ManagedBean<?>> beans;
    private final EventDispatcher eventDispatcher;

    public VaubanBeanManager(VaubanContainer container,
                             Map<Class<? extends Annotation>, Context> contexts,
                             Collection<ManagedBean<?>> beans,
                             EventDispatcher eventDispatcher) {
        this.container = container;
        this.contexts = contexts;
        this.beans = List.copyOf(beans);
        this.eventDispatcher = eventDispatcher;
    }

    // --- Functional methods ---

    @Override
    public Object getReference(Bean<?> bean, Type beanType, CreationalContext<?> ctx) {
        var scope = bean.getScope();
        var context = contexts.get(scope);
        if (context == null) {
            context = contexts.get(jakarta.enterprise.context.Dependent.class);
        }
        @SuppressWarnings("unchecked")
        var contextual = (Contextual<Object>) bean;
        @SuppressWarnings("unchecked")
        var cc = (CreationalContext<Object>) ctx;
        return context.get(contextual, cc);
    }

    @Override
    public Set<Bean<?>> getBeans(Type beanType, Annotation... qualifiers) {
        var result = new LinkedHashSet<Bean<?>>();
        for (var bean : beans) {
            if (beanType instanceof Class<?> clazz) {
                if (clazz.isAssignableFrom(bean.getBeanClass())) {
                    result.add(bean);
                }
            }
        }
        return result;
    }

    @Override
    public Set<Bean<?>> getBeans(String name) {
        var result = new LinkedHashSet<Bean<?>>();
        for (var bean : beans) {
            if (Objects.equals(name, bean.getName())) {
                result.add(bean);
            }
        }
        return result;
    }

    @Override
    public <X> Bean<? extends X> resolve(Set<Bean<? extends X>> beans) {
        if (beans == null || beans.isEmpty()) return null;
        if (beans.size() == 1) return beans.iterator().next();
        return beans.iterator().next();
    }

    @Override
    public Context getContext(Class<? extends Annotation> scopeType) {
        var context = contexts.get(scopeType);
        if (context == null) {
            throw new jakarta.enterprise.context.ContextNotActiveException(
                    "No context for scope: " + scopeType.getName());
        }
        if (!context.isActive()) {
            throw new jakarta.enterprise.context.ContextNotActiveException(
                    "Context not active for scope: " + scopeType.getName());
        }
        return context;
    }

    @Override
    public Collection<Context> getContexts(Class<? extends Annotation> scopeType) {
        var context = contexts.get(scopeType);
        if (context == null) {
            return List.of();
        }
        return List.of(context);
    }

    @Override
    public <T> CreationalContext<T> createCreationalContext(Contextual<T> contextual) {
        return new CreationalContextImpl<>();
    }

    @Override
    public boolean isQualifier(Class<? extends Annotation> annotationType) {
        return annotationType.isAnnotationPresent(jakarta.inject.Qualifier.class)
                || annotationType == jakarta.enterprise.inject.Default.class
                || annotationType == jakarta.enterprise.inject.Any.class
                || annotationType == jakarta.inject.Named.class;
    }

    @Override
    public boolean isScope(Class<? extends Annotation> annotationType) {
        return annotationType.isAnnotationPresent(jakarta.inject.Scope.class)
                || annotationType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                || annotationType == jakarta.enterprise.context.Dependent.class
                || annotationType == jakarta.enterprise.context.ApplicationScoped.class
                || annotationType == jakarta.enterprise.context.RequestScoped.class
                || annotationType == jakarta.inject.Singleton.class;
    }

    @Override
    public boolean isNormalScope(Class<? extends Annotation> annotationType) {
        return annotationType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                || annotationType == jakarta.enterprise.context.ApplicationScoped.class
                || annotationType == jakarta.enterprise.context.RequestScoped.class;
    }

    @Override
    public boolean isStereotype(Class<? extends Annotation> annotationType) {
        return annotationType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class);
    }

    @Override
    public boolean isInterceptorBinding(Class<? extends Annotation> annotationType) {
        return annotationType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class);
    }

    @Override
    public boolean isPassivatingScope(Class<? extends Annotation> annotationType) {
        var normalScope = annotationType.getAnnotation(jakarta.enterprise.context.NormalScope.class);
        return normalScope != null && normalScope.passivating();
    }

    // --- Stub methods (UnsupportedOperationException) ---

    @Override
    public Object getInjectableReference(InjectionPoint ij, CreationalContext<?> ctx) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Bean<?> getPassivationCapableBean(String id) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void validate(InjectionPoint injectionPoint) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, Annotation... qualifiers) {
        var matching = eventDispatcher.findMatchingObservers(event.getClass(), false);
        var result = new LinkedHashSet<ObserverMethod<? super T>>();
        for (var descriptor : matching) {
            result.add(new VaubanObserverMethod(descriptor, eventDispatcher));
        }
        return result;
    }

    @Override
    public List<Interceptor<?>> resolveInterceptors(InterceptionType type, Annotation... interceptorBindings) {
        return List.of();
    }

    @Override
    public List<Decorator<?>> resolveDecorators(Set<Type> types, Annotation... qualifiers) {
        return List.of();
    }

    @Override
    public Set<Annotation> getInterceptorBindingDefinition(Class<? extends Annotation> bindingType) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Set<Annotation> getStereotypeDefinition(Class<? extends Annotation> stereotype) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean areQualifiersEquivalent(Annotation qualifier1, Annotation qualifier2) {
        return qualifier1.equals(qualifier2);
    }

    @Override
    public boolean areInterceptorBindingsEquivalent(Annotation interceptorBinding1, Annotation interceptorBinding2) {
        return interceptorBinding1.equals(interceptorBinding2);
    }

    @Override
    public int getQualifierHashCode(Annotation qualifier) {
        return qualifier.hashCode();
    }

    @Override
    public int getInterceptorBindingHashCode(Annotation interceptorBinding) {
        return interceptorBinding.hashCode();
    }

    @Override
    @SuppressWarnings("deprecation")
    public ELResolver getELResolver() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    @SuppressWarnings("deprecation")
    public ExpressionFactory wrapExpressionFactory(ExpressionFactory expressionFactory) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T> AnnotatedType<T> createAnnotatedType(Class<T> type) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T> InjectionTargetFactory<T> getInjectionTargetFactory(AnnotatedType<T> annotatedType) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <X> ProducerFactory<X> getProducerFactory(AnnotatedField<? super X> field, Bean<X> declaringBean) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <X> ProducerFactory<X> getProducerFactory(AnnotatedMethod<? super X> method, Bean<X> declaringBean) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T> BeanAttributes<T> createBeanAttributes(AnnotatedType<T> type) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public BeanAttributes<?> createBeanAttributes(AnnotatedMember<?> type) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T> Bean<T> createBean(BeanAttributes<T> attributes, Class<T> beanClass,
                                  InjectionTargetFactory<T> injectionTargetFactory) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T, X> Bean<T> createBean(BeanAttributes<T> attributes, Class<X> beanClass,
                                     ProducerFactory<X> producerFactory) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public InjectionPoint createInjectionPoint(AnnotatedField<?> field) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public InjectionPoint createInjectionPoint(AnnotatedParameter<?> parameter) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T extends Extension> T getExtension(Class<T> extensionClass) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public <T> InterceptionFactory<T> createInterceptionFactory(CreationalContext<T> ctx, Class<T> clazz) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Event<Object> getEvent() {
        return new EventImpl<>(eventDispatcher);
    }

    @Override
    public Instance<Object> createInstance() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean isMatchingBean(Set<Type> beanTypes, Set<Annotation> beanQualifiers,
                                  Type requiredType, Set<Annotation> requiredQualifiers) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean isMatchingEvent(Type specifiedType, Set<Annotation> specifiedQualifiers,
                                   Type observedEventType, Set<Annotation> observedEventQualifiers) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
