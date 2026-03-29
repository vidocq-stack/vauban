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
    private List<Bean<?>> builtInBeans;

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

    private List<Bean<?>> getBuiltInBeans() {
        if (builtInBeans == null) {
            builtInBeans = List.of(
                new BuiltInBean<>(BeanManager.class,
                    Set.of(BeanManager.class, jakarta.enterprise.inject.spi.BeanContainer.class, Object.class),
                    () -> this),
                new BuiltInBean<>(Event.class,
                    Set.of(Event.class, Object.class),
                    () -> getEvent()),
                new BuiltInBean<>(Instance.class,
                    Set.of(Instance.class, Object.class),
                    () -> createInstance()),
                new BuiltInBean<>(InjectionPoint.class,
                    Set.of(InjectionPoint.class, Object.class),
                    VaubanContainer::getCurrentInjectionPoint)
            );
        }
        return builtInBeans;
    }

    // --- Functional methods ---

    @Override
    public Object getReference(Bean<?> bean, Type beanType, CreationalContext<?> ctx) {
        // Validate that beanType is actually a type of the bean
        boolean valid = bean.getTypes().stream()
            .anyMatch(bt -> typesMatch(bt, beanType));
        if (!valid) {
            throw new IllegalArgumentException(
                "Type " + beanType + " is not a bean type of " + bean.getBeanClass());
        }

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
        // Validate: type variable not allowed
        if (beanType instanceof java.lang.reflect.TypeVariable<?>) {
            throw new IllegalArgumentException("TypeVariable is not a legal bean type");
        }
        // Validate: all qualifiers must be qualifier annotations, no duplicates
        if (qualifiers != null) {
            var seen = new HashSet<Class<?>>();
            for (var q : qualifiers) {
                if (!isQualifier(q.annotationType())) {
                    throw new IllegalArgumentException(
                        q.annotationType().getName() + " is not a qualifier");
                }
                if (!seen.add(q.annotationType())) {
                    throw new IllegalArgumentException(
                        "Duplicate qualifier: " + q.annotationType().getName());
                }
            }
        }

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

        // Check built-in beans
        for (var builtIn : getBuiltInBeans()) {
            boolean typeMatch = false;
            for (var bt : builtIn.getTypes()) {
                if (typesMatch(bt, beanType)) {
                    typeMatch = true;
                    break;
                }
            }
            if (typeMatch) {
                // Built-in beans have @Default and @Any qualifiers
                if (requiredQualifiers.contains(jakarta.enterprise.inject.Any.class) ||
                    requiredQualifiers.contains(jakarta.enterprise.inject.Default.class)) {
                    result.add(builtIn);
                }
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

            // Check if bean has all required qualifiers via bean.getQualifiers()
            {
                var beanQualifiers = bean.getQualifiers();
                boolean qualifiersMatch = true;
                for (var reqQualClass : requiredQualifiers) {
                    boolean found = beanQualifiers.stream()
                        .anyMatch(bq -> bq.annotationType().equals(reqQualClass));
                    if (!found) {
                        qualifiersMatch = false;
                        break;
                    }
                }
                if (qualifiersMatch) {
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
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        // Validate qualifiers
        if (qualifiers != null) {
            var seen = new HashSet<Class<?>>();
            for (var q : qualifiers) {
                if (!isQualifier(q.annotationType())) {
                    throw new IllegalArgumentException(
                        q.annotationType().getName() + " is not a qualifier");
                }
                if (!seen.add(q.annotationType())) {
                    throw new IllegalArgumentException(
                        "Duplicate qualifier: " + q.annotationType().getName());
                }
            }
        }
        var eventQualifiers = new java.util.LinkedHashSet<fr.vidocq.vauban.indexer.model.DotName>();
        for (var q : qualifiers) {
            eventQualifiers.add(fr.vidocq.vauban.indexer.model.DotName.of(q.annotationType().getName()));
        }
        var matching = eventDispatcher.findMatchingObservers(event.getClass(), false, eventQualifiers);
        var result = new LinkedHashSet<ObserverMethod<? super T>>();
        for (var descriptor : matching) {
            // Find the declaring bean
            Bean<?> declaringBean = null;
            for (var bean : beans) {
                if (bean.getBeanClass().getName().equals(descriptor.declaringClass().value())) {
                    declaringBean = bean;
                    break;
                }
            }
            result.add(new VaubanObserverMethod(descriptor, eventDispatcher, declaringBean));
        }
        return result;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Interceptor<?>> resolveInterceptors(InterceptionType type, Annotation... interceptorBindings) {
        if (interceptorBindings == null || interceptorBindings.length == 0) {
            throw new IllegalArgumentException("At least one interceptor binding must be specified");
        }
        // Validate: all must be interceptor bindings, no duplicates
        var seenBindings = new HashSet<Class<?>>();
        for (var binding : interceptorBindings) {
            if (!isInterceptorBinding(binding.annotationType())) {
                throw new IllegalArgumentException(
                    binding.annotationType().getName() + " is not an interceptor binding");
            }
            if (!seenBindings.add(binding.annotationType())) {
                throw new IllegalArgumentException(
                    "Duplicate interceptor binding: " + binding.annotationType().getName());
            }
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
        throw new IllegalStateException("Not yet implemented");
    }

    @Override
    @SuppressWarnings("deprecation")
    public ExpressionFactory wrapExpressionFactory(ExpressionFactory expressionFactory) {
        throw new IllegalStateException("Not yet implemented");
    }

    @Override
    public <T> AnnotatedType<T> createAnnotatedType(Class<T> type) {
        return new VaubanAnnotatedType<>(type);
    }

    @Override
    public <T> InjectionTargetFactory<T> getInjectionTargetFactory(AnnotatedType<T> annotatedType) {
        throw new IllegalStateException("Not yet implemented");
    }

    @Override
    public <X> ProducerFactory<X> getProducerFactory(AnnotatedField<? super X> field, Bean<X> declaringBean) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        throw new IllegalArgumentException("Producer factory not supported for: " + field);
    }

    @Override
    public <X> ProducerFactory<X> getProducerFactory(AnnotatedMethod<? super X> method, Bean<X> declaringBean) {
        if (method == null) throw new IllegalArgumentException("method must not be null");
        throw new IllegalArgumentException("Producer factory not supported for: " + method);
    }

    @Override
    public <T> BeanAttributes<T> createBeanAttributes(AnnotatedType<T> type) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        throw new IllegalArgumentException("createBeanAttributes not supported for: " + type);
    }

    @Override
    public BeanAttributes<?> createBeanAttributes(AnnotatedMember<?> type) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        throw new IllegalArgumentException("createBeanAttributes not supported for: " + type);
    }

    @Override
    public <T> Bean<T> createBean(BeanAttributes<T> attributes, Class<T> beanClass,
                                  InjectionTargetFactory<T> injectionTargetFactory) {
        throw new IllegalArgumentException("createBean not yet supported");
    }

    @Override
    public <T, X> Bean<T> createBean(BeanAttributes<T> attributes, Class<X> beanClass,
                                     ProducerFactory<X> producerFactory) {
        throw new IllegalArgumentException("createBean not yet supported");
    }

    @Override
    public InjectionPoint createInjectionPoint(AnnotatedField<?> field) {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        throw new IllegalArgumentException("createInjectionPoint not supported for: " + field);
    }

    @Override
    public InjectionPoint createInjectionPoint(AnnotatedParameter<?> parameter) {
        if (parameter == null) throw new IllegalArgumentException("parameter must not be null");
        throw new IllegalArgumentException("createInjectionPoint not supported for: " + parameter);
    }

    @Override
    public <T extends Extension> T getExtension(Class<T> extensionClass) {
        throw new IllegalStateException("Not yet implemented");
    }

    @Override
    public <T> InterceptionFactory<T> createInterceptionFactory(CreationalContext<T> ctx, Class<T> clazz) {
        throw new IllegalStateException("Not yet implemented");
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
        if (beanTypes == null || beanQualifiers == null || requiredQualifiers == null) {
            throw new IllegalArgumentException("Arguments must not be null");
        }
        if (requiredType == null) {
            throw new IllegalArgumentException("Required type must not be null");
        }
        // Validate all qualifiers
        for (var q : requiredQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException("Not a qualifier: " + q.annotationType());
            }
        }
        for (var q : beanQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException("Not a qualifier: " + q.annotationType());
            }
        }

        // Type matching — required type must be in beanTypes (exact type equality or CDI assignability)
        boolean typeMatch = false;
        for (var bt : beanTypes) {
            if (bt instanceof java.lang.reflect.TypeVariable<?>) continue;
            if (bt instanceof java.lang.reflect.WildcardType) continue;
            if (typesMatch(bt, requiredType)) {
                typeMatch = true;
                break;
            }
        }
        if (!typeMatch) return false;

        // CDI qualifier matching:
        // - A bean with no qualifiers implicitly has @Default and @Any
        // - @Any in required qualifiers matches everything
        var effectiveBeanQualifiers = beanQualifiers.isEmpty()
                ? Set.<Annotation>of(jakarta.enterprise.inject.Default.Literal.INSTANCE,
                                     jakarta.enterprise.inject.Any.Literal.INSTANCE)
                : beanQualifiers;

        for (var req : requiredQualifiers) {
            if (req.annotationType() == jakarta.enterprise.inject.Any.class) continue;
            boolean found = effectiveBeanQualifiers.stream()
                .anyMatch(bq -> bq.annotationType().equals(req.annotationType())
                        && (bq.equals(req) || req.equals(bq)
                            || bq.annotationType().getDeclaredMethods().length == 0));
            if (!found) return false;
        }
        return true;
    }

    @Override
    public boolean isMatchingEvent(Type specifiedType, Set<Annotation> specifiedQualifiers,
                                   Type observedEventType, Set<Annotation> observedEventQualifiers) {
        if (specifiedQualifiers == null || observedEventQualifiers == null) {
            throw new IllegalArgumentException("Qualifiers must not be null");
        }
        if (specifiedType == null || observedEventType == null) {
            throw new IllegalArgumentException("Event types must not be null");
        }
        for (var q : specifiedQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException("Not a qualifier: " + q.annotationType());
            }
        }
        for (var q : observedEventQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException("Not a qualifier: " + q.annotationType());
            }
        }
        if (observedEventType instanceof java.lang.reflect.TypeVariable<?>) {
            throw new IllegalArgumentException("Observed event type cannot be a type variable");
        }

        // Type matching: observed type must be assignable from specified type
        if (!typesMatch(specifiedType, observedEventType)) return false;

        // CDI event qualifier matching:
        // - An event with no qualifiers implicitly has @Default and @Any
        // - An observer with @Any matches all events
        // - Every observed qualifier must be present in specified qualifiers
        var effectiveSpecifiedQualifiers = specifiedQualifiers.isEmpty()
                ? Set.<Annotation>of(jakarta.enterprise.inject.Default.Literal.INSTANCE,
                                     jakarta.enterprise.inject.Any.Literal.INSTANCE)
                : specifiedQualifiers;

        for (var obs : observedEventQualifiers) {
            if (obs.annotationType() == jakarta.enterprise.inject.Any.class) continue;
            boolean found = effectiveSpecifiedQualifiers.stream()
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

        // Primitive <-> wrapper matching
        if (requiredType instanceof Class<?> reqClass && beanType instanceof Class<?> btClass) {
            if (isPrimitiveWrapperMatch(reqClass, btClass)) return true;
        }

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

    private static boolean isPrimitiveWrapperMatch(Class<?> a, Class<?> b) {
        if (a.isPrimitive()) return b == primitiveToWrapper(a);
        if (b.isPrimitive()) return a == primitiveToWrapper(b);
        return false;
    }

    private static Class<?> primitiveToWrapper(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        if (primitive == short.class) return Short.class;
        if (primitive == void.class) return Void.class;
        return primitive;
    }
}
