package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import fr.vidocq.vauban.core.context.CreationalContextImpl;
import fr.vidocq.vauban.core.event.EventDispatcher;
import fr.vidocq.vauban.core.event.EventImpl;
import fr.vidocq.vauban.core.event.VaubanObserverMethod;
import fr.vidocq.vauban.core.interceptor.InterceptorManager;
import fr.vidocq.vauban.indexer.model.DotName;
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
    private final InterceptorManager interceptorManager;

    public VaubanBeanManager(VaubanContainer container,
                             Map<Class<? extends Annotation>, Context> contexts,
                             Collection<ManagedBean<?>> beans,
                             EventDispatcher eventDispatcher,
                             InterceptorManager interceptorManager) {
        this.container = container;
        this.contexts = contexts;
        this.beans = List.copyOf(beans);
        this.eventDispatcher = eventDispatcher;
        this.interceptorManager = interceptorManager;
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

        // Determine required qualifiers: if none specified, CDI uses @Default
        Set<Class<? extends Annotation>> requiredQualifiers = new LinkedHashSet<>();
        if (qualifiers == null || qualifiers.length == 0) {
            requiredQualifiers.add(jakarta.enterprise.inject.Default.class);
        } else {
            for (var q : qualifiers) {
                requiredQualifiers.add(q.annotationType());
            }
        }

        for (var bean : beans) {
            // Type matching (supports Class, ParameterizedType, etc.)
            boolean typeMatch = false;
            for (var bt : bean.getTypes()) {
                if (typesMatch(bt, beanType)) {
                    typeMatch = true;
                    break;
                }
            }

            if (!typeMatch) continue;

            // Qualifier matching: @Any matches everything
            if (requiredQualifiers.contains(jakarta.enterprise.inject.Any.class)) {
                result.add(bean);
                continue;
            }

            // Check if bean has all required qualifiers
            if (bean instanceof ManagedBean<?> mb) {
                boolean qualifiersMatch = true;
                for (var reqQual : requiredQualifiers) {
                    var reqName = DotName.of(reqQual.getName());
                    boolean found = mb.descriptor().qualifiers().stream()
                        .anyMatch(q -> q.annotationName().equals(reqName));
                    if (!found) {
                        qualifiersMatch = false;
                        break;
                    }
                }
                if (qualifiersMatch) {
                    result.add(bean);
                }
            } else {
                // Non-ManagedBean: fall back to getQualifiers()
                var beanQualTypes = new LinkedHashSet<Class<? extends Annotation>>();
                for (var bq : bean.getQualifiers()) {
                    beanQualTypes.add(bq.annotationType());
                }
                if (beanQualTypes.containsAll(requiredQualifiers)) {
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

        // Try to resolve using alternatives with @Priority
        Bean<? extends X> best = null;
        int bestPriority = -1;
        boolean hasAlternative = false;

        for (var bean : beans) {
            if (bean.isAlternative()) {
                hasAlternative = true;
                int priority = 0;
                if (bean instanceof ManagedBean<?> mb) {
                    priority = mb.descriptor().priority();
                }
                if (priority > bestPriority) {
                    bestPriority = priority;
                    best = bean;
                }
            }
        }

        if (hasAlternative && best != null) return best;

        // Still ambiguous — CDI spec requires AmbiguousResolutionException
        throw new jakarta.enterprise.inject.AmbiguousResolutionException(
            "Ambiguous dependency: " + beans.size() + " beans match");
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
        var beans = getBeans(ij.getType(), ij.getQualifiers().toArray(new Annotation[0]));
        if (beans.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No bean for injection point: " + ij);
        }
        var bean = resolve(beans);
        return getReference(bean, ij.getType(), ctx);
    }

    @Override
    public Bean<?> getPassivationCapableBean(String id) {
        for (var bean : beans) {
            if (bean instanceof ManagedBean<?> mb && id.equals(mb.descriptor().id().value())) {
                return bean;
            }
        }
        return null;
    }

    @Override
    public void validate(InjectionPoint injectionPoint) {
        var beans = getBeans(injectionPoint.getType(),
                injectionPoint.getQualifiers().toArray(new Annotation[0]));
        if (beans.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "Unsatisfied: " + injectionPoint);
        }
        if (beans.size() > 1) {
            var resolved = resolve(beans);
            if (resolved == null) {
                throw new jakarta.enterprise.inject.AmbiguousResolutionException(
                        "Ambiguous: " + injectionPoint);
            }
        }
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Interceptor<?>> resolveInterceptors(InterceptionType type, Annotation... interceptorBindings) {
        if (interceptorBindings == null || interceptorBindings.length == 0) {
            throw new IllegalArgumentException("At least one interceptor binding must be specified");
        }

        var bindingNames = new LinkedHashSet<DotName>();
        for (var binding : interceptorBindings) {
            bindingNames.add(DotName.of(binding.annotationType().getName()));
        }

        var descriptors = interceptorManager.resolveInterceptors(bindingNames);
        var result = new ArrayList<Interceptor<?>>();
        for (var descriptor : descriptors) {
            result.add(new VaubanInterceptor(descriptor));
        }
        return result;
    }

    @Override
    public List<Decorator<?>> resolveDecorators(Set<Type> types, Annotation... qualifiers) {
        return List.of();
    }

    @Override
    public Set<Annotation> getInterceptorBindingDefinition(Class<? extends Annotation> bindingType) {
        var result = new LinkedHashSet<Annotation>();
        for (var ann : bindingType.getAnnotations()) {
            result.add(ann);
        }
        return result;
    }

    @Override
    public Set<Annotation> getStereotypeDefinition(Class<? extends Annotation> stereotype) {
        var result = new LinkedHashSet<Annotation>();
        for (var ann : stereotype.getAnnotations()) {
            result.add(ann);
        }
        return result;
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
        return new VaubanAnnotatedType<>(type);
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
        return new InstanceImpl<>(container, Object.class);
    }

    @Override
    public boolean isMatchingBean(Set<Type> beanTypes, Set<Annotation> beanQualifiers,
                                  Type requiredType, Set<Annotation> requiredQualifiers) {
        if (requiredType == null) {
            throw new IllegalArgumentException("Required type must not be null");
        }
        for (var q : requiredQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException("Not a qualifier: " + q.annotationType());
            }
        }

        // Type matching
        boolean typeMatch = false;
        for (var bt : beanTypes) {
            if (typesMatch(bt, requiredType)) {
                typeMatch = true;
                break;
            }
        }
        if (!typeMatch) return false;

        // Qualifier matching: every required qualifier must be present in bean's qualifiers
        for (var req : requiredQualifiers) {
            if (req.annotationType() == jakarta.enterprise.inject.Any.class) continue;
            boolean found = beanQualifiers.stream()
                .anyMatch(bq -> bq.annotationType().equals(req.annotationType()));
            if (!found) return false;
        }
        return true;
    }

    @Override
    public boolean isMatchingEvent(Type specifiedType, Set<Annotation> specifiedQualifiers,
                                   Type observedEventType, Set<Annotation> observedEventQualifiers) {
        if (specifiedType == null || observedEventType == null) {
            throw new IllegalArgumentException("Event types must not be null");
        }
        for (var q : specifiedQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException("Not a qualifier: " + q.annotationType());
            }
        }
        if (observedEventType instanceof java.lang.reflect.TypeVariable<?>) {
            throw new IllegalArgumentException("Observed event type cannot be a type variable");
        }

        // Type matching: observed type must be assignable from specified type
        // (i.e., the observer's type must be a supertype of the fired event type)
        if (!typesMatch(specifiedType, observedEventType)) return false;

        // Qualifier matching: every observed qualifier must be present in specified qualifiers
        for (var obs : observedEventQualifiers) {
            boolean found = specifiedQualifiers.stream()
                .anyMatch(sq -> sq.annotationType().equals(obs.annotationType()));
            if (!found) return false;
        }
        return true;
    }

    /**
     * Checks if a bean type is assignable to a required type,
     * supporting Class, ParameterizedType, and raw/parameterized compatibility.
     */
    private static boolean typesMatch(Type beanType, Type requiredType) {
        if (beanType.equals(requiredType)) return true;

        if (requiredType instanceof Class<?> reqClass) {
            if (beanType instanceof Class<?> btClass) {
                return reqClass.isAssignableFrom(btClass);
            }
            if (beanType instanceof java.lang.reflect.ParameterizedType pt) {
                return reqClass.isAssignableFrom((Class<?>) pt.getRawType());
            }
        }

        if (requiredType instanceof java.lang.reflect.ParameterizedType reqPt) {
            if (beanType instanceof java.lang.reflect.ParameterizedType beanPt) {
                // Raw types must be assignable
                if (!typesMatch(beanPt.getRawType(), reqPt.getRawType())) return false;
                // Type arguments must match exactly (invariant)
                var reqArgs = reqPt.getActualTypeArguments();
                var beanArgs = beanPt.getActualTypeArguments();
                if (reqArgs.length != beanArgs.length) return false;
                for (int i = 0; i < reqArgs.length; i++) {
                    if (!reqArgs[i].equals(beanArgs[i])) return false;
                }
                return true;
            }
            // Raw class matches parameterized type (unsafe but CDI allows)
            if (beanType instanceof Class<?> btClass) {
                return ((Class<?>) reqPt.getRawType()).isAssignableFrom(btClass);
            }
        }

        return false;
    }
}
