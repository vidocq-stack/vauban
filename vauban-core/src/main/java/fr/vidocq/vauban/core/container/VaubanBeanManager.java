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
        this.beans = new ArrayList<>(beans);
        this.eventDispatcher = eventDispatcher;
        this.interceptorManager = interceptorManager;
    }

    private List<Bean<?>> getBuiltInBeans() {
        if (builtInBeans == null) {
            builtInBeans = List.of(
                new BuiltInBean<>(BeanManager.class,
                    Set.of(BeanManager.class, jakarta.enterprise.inject.spi.BeanContainer.class),
                    () -> this),
                new BuiltInBean<>(Event.class,
                    Set.of(Event.class, Object.class),
                    () -> getEvent()),
                new BuiltInBean<>(Instance.class,
                    Set.of(Instance.class),
                    () -> createInstance()),
                new BuiltInBean<>(InjectionPoint.class,
                    Set.of(InjectionPoint.class),
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
        // Built-in beans match any parameterization of their raw type
        if (!valid && bean instanceof BuiltInBean<?>) {
            valid = bean.getTypes().stream().anyMatch(bt ->
                bt instanceof Class<?> btClass && beanType instanceof java.lang.reflect.ParameterizedType reqPt
                    && reqPt.getRawType() instanceof Class<?> reqRaw
                    && (btClass == reqRaw || btClass.getName().equals(reqRaw.getName())));
        }
        if (!valid) {
            throw new IllegalArgumentException(
                "Type " + beanType + " is not a bean type of " + bean.getBeanClass());
        }

        // For normal-scoped beans, return client proxy via container
        if (bean instanceof ManagedBean<?> mb
                && mb.descriptor().scope().isNormal()
                && mb.descriptor().kind() == fr.vidocq.vauban.core.bean.model.BeanDescriptor.BeanKind.MANAGED) {
            return container.getOrCreateProxyForBean(mb);
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
        
        Object instance = context.get(contextual, cc);

        // Ensure dependent instances are registered for cleanup in the provided context
        if (scope == jakarta.enterprise.context.Dependent.class && cc instanceof CreationalContextImpl<?> vcc && instance != null) {
             vcc.addDependentInstance(contextual, instance, cc);
        }
        
        return instance;
    }

    @Override
    public Set<Bean<?>> getBeans(Type beanType, Annotation... qualifiers) {
        // CDI spec: primitive types and wrappers are identical
        if (beanType instanceof Class<?> c && c.isPrimitive()) {
            beanType = primitiveToWrapper(c);
        }
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
                if (!seen.add(q.annotationType())
                        && !q.annotationType().isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                    throw new IllegalArgumentException(
                        "Duplicate qualifier: " + q.annotationType().getName());
                }
            }
        }

        var result = new LinkedHashSet<Bean<?>>();

        // Determine required qualifiers: if none specified, CDI uses @Default
        Set<Annotation> requiredQualifierAnnotations = new LinkedHashSet<>();
        Set<Class<? extends Annotation>> requiredQualifiers = new LinkedHashSet<>();
        if (qualifiers == null || qualifiers.length == 0) {
            requiredQualifiers.add(jakarta.enterprise.inject.Default.class);
            requiredQualifierAnnotations.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        } else {
            for (var q : qualifiers) {
                requiredQualifiers.add(q.annotationType());
                requiredQualifierAnnotations.add(q);
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
                // Built-in beans like Instance and Event match any parameterization of their raw type
                if (bt instanceof Class<?> btClass && beanType instanceof java.lang.reflect.ParameterizedType reqPt
                        && reqPt.getRawType() instanceof Class<?> reqRaw
                        && (btClass == reqRaw || btClass.getName().equals(reqRaw.getName()))) {
                    typeMatch = true;
                    break;
                }
            }
            if (typeMatch) {
                // CDI spec 3.11: Event and Instance built-in beans have "every qualifier type"
                // (they match any qualifier combination, so qualifier matching always succeeds)
                boolean isWildcardQualifier = builtIn.getBeanClass() == Event.class
                        || builtIn.getBeanClass() == Instance.class;
                boolean qualifiersMatch = isWildcardQualifier;
                if (!qualifiersMatch) {
                    qualifiersMatch = true;
                    var beanQualifiers = builtIn.getQualifiers();
                    for (var reqAnn : requiredQualifierAnnotations) {
                        // @Any always matches — all beans implicitly have @Any
                        if (reqAnn.annotationType() == jakarta.enterprise.inject.Any.class) continue;
                        boolean found = beanQualifiers.stream()
                            .anyMatch(bq -> qualifierEquals(bq, reqAnn));
                        if (!found) {
                            qualifiersMatch = false;
                            break;
                        }
                    }
                }
                if (qualifiersMatch) {
                    result.add(builtIn);
                }
            }
        }

        for (var bean : beans) {
            // CDI spec: disabled alternatives (no @Priority) are excluded from resolution
            if (bean.isAlternative() && bean instanceof ManagedBean<?> mb
                    && mb.descriptor().priority() <= 0) {
                continue;
            }

            // Type matching (supports Class, ParameterizedType, etc.)
            boolean typeMatch = false;
            for (var bt : bean.getTypes()) {
                if (typesMatch(bt, beanType)) {
                    typeMatch = true;
                    break;
                }
            }

            if (!typeMatch) continue;

            // Qualifier matching
            {
                var beanQualifiers = bean.getQualifiers();
                boolean qualifiersMatch = true;
                for (var reqAnn : requiredQualifierAnnotations) {
                    // @Any always matches — all beans implicitly have @Any
                    if (reqAnn.annotationType() == jakarta.enterprise.inject.Any.class) continue;
                    boolean found = beanQualifiers.stream()
                        .anyMatch(bq -> qualifierEquals(bq, reqAnn));
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
                // CDI spec: disabled alternatives (no @Priority) are excluded from resolution
                if (bean.isAlternative() && bean instanceof ManagedBean<?> mb
                        && mb.descriptor().priority() <= 0) {
                    continue;
                }
                result.add(bean);
            }
        }
        return result;
    }

    @Override
    public <X> Bean<? extends X> resolve(Set<Bean<? extends X>> beans) {
        if (beans == null || beans.isEmpty()) return null;
        if (beans.size() == 1) return beans.iterator().next();

        // Filter out beans that are not alternatives if at least one alternative is present
        var alternatives = beans.stream().filter(Bean::isAlternative).toList();
        if (!alternatives.isEmpty()) {
            // Among alternatives, pick the one with highest priority
            Bean<? extends X> best = null;
            int bestPriority = -1;
            boolean duplicatePriority = false;

            for (var bean : alternatives) {
                int priority = 0;
                if (bean instanceof ManagedBean<?> mb) {
                    priority = mb.descriptor().priority();
                }
                if (priority > bestPriority) {
                    bestPriority = priority;
                    best = bean;
                    duplicatePriority = false;
                } else if (priority == bestPriority && priority != -1) {
                    duplicatePriority = true;
                }
            }

            if (best != null && !duplicatePriority) {
                return best;
            } else {
                throw new jakarta.enterprise.inject.AmbiguousResolutionException(
                    "Ambiguous dependency: multiple alternatives with the same highest priority among " + beans.size() + " beans");
            }
        }

        // If no alternatives, and we have multiple beans, check if they are the SAME bean (same class/id)
        // or if one is a "built-in" bean that should have priority.
        // Actually CDI spec says it's ambiguous. 
        // But in the TCK, sometimes we get the same bean multiple times if discovery is messy.
        var first = beans.iterator().next();
        boolean allSame = true;
        for (var b : beans) {
            if (!b.equals(first)) {
                allSame = false;
                break;
            }
        }
        if (allSame) return first;

        throw new jakarta.enterprise.inject.AmbiguousResolutionException(
            "Ambiguous dependency: " + beans.size() + " beans match");
    }

    @Override
    public Context getContext(Class<? extends Annotation> scopeType) {
        var context = findContext(scopeType);
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
        var context = findContext(scopeType);
        if (context == null) {
            return List.of();
        }
        return List.of(context);
    }

    /**
     * Find a context by scope type, handling cross-classloader scenarios.
     */
    private Context findContext(Class<? extends Annotation> scopeType) {
        var context = contexts.get(scopeType);
        if (context != null) return context;
        // Fallback: match by class name (for cross-classloader support in test frameworks)
        var scopeName = scopeType.getName();
        for (var entry : contexts.entrySet()) {
            if (entry.getKey().getName().equals(scopeName)) {
                return entry.getValue();
            }
        }
        return null;
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
        var previousIp = VaubanContainer.getCurrentInjectionPoint();
        VaubanContainer.setInjectionPoint(ij);
        try {
            return getReference(bean, ij.getType(), ctx);
        } finally {
            VaubanContainer.setInjectionPoint(previousIp);
        }
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
                // CDI spec: qualifier must have @Retention(RUNTIME)
                var retention = q.annotationType().getAnnotation(java.lang.annotation.Retention.class);
                if (retention == null || retention.value() != java.lang.annotation.RetentionPolicy.RUNTIME) {
                    throw new IllegalArgumentException(
                        q.annotationType().getName() + " does not have @Retention(RUNTIME)");
                }
                if (!seen.add(q.annotationType())
                        && !q.annotationType().isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                    throw new IllegalArgumentException(
                        "Duplicate qualifier: " + q.annotationType().getName());
                }
            }
        }
        var eventQualifiers = new java.util.LinkedHashSet<fr.vidocq.vauban.indexer.model.DotName>();
        for (var q : qualifiers) {
            eventQualifiers.add(fr.vidocq.vauban.indexer.model.DotName.of(q.annotationType().getName()));
        }
        // CDI spec: resolveObserverMethods returns both sync and async observers
        var matching = new java.util.ArrayList<>(eventDispatcher.findMatchingObservers(event.getClass(), false, eventQualifiers, qualifiers));
        matching.addAll(eventDispatcher.findMatchingObservers(event.getClass(), true, eventQualifiers, qualifiers));
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
            result.add(new VaubanObserverMethod(descriptor, eventDispatcher, declaringBean, container.classLoader()));
        }
        // CDI spec: resolveObserverMethods returns observers ordered by priority
        var sorted = new java.util.ArrayList<>(result);
        sorted.sort(Comparator.comparingInt(om -> ((ObserverMethod<?>) om).getPriority()));
        return new LinkedHashSet<>(sorted);
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

        var descriptors = interceptorManager.resolveInterceptors(java.util.Arrays.asList(interceptorBindings));
        var result = new ArrayList<Interceptor<?>>();
        for (var descriptor : descriptors) {
            var vi = new VaubanInterceptor(descriptor);
            if (vi.intercepts(type)) {
                result.add(vi);
            }
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
        var ip = VaubanContainer.getCurrentInjectionPoint();
        return new EventImpl<>(eventDispatcher, new java.lang.annotation.Annotation[0], ip);
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

        // Type matching — required type must be assignable from at least one of the beanTypes
        // CDI spec: isMatchingBean checks if requiredType is assignable from a bean type
        boolean typeMatch = false;
        for (var bt : beanTypes) {
            if (bt instanceof java.lang.reflect.TypeVariable<?>) continue;
            if (bt instanceof java.lang.reflect.WildcardType) continue;
            if (bt.equals(requiredType)) {
                typeMatch = true;
                break;
            }
            if (bt instanceof Class<?> btClass && requiredType instanceof Class<?> reqClass) {
                // CDI assignability: requiredType must be assignable FROM beanType
                // But ONLY consider bean types explicitly listed, plus Object
                if (btClass == reqClass || isPrimitiveWrapperMatch(btClass, reqClass)) {
                    typeMatch = true;
                    break;
                }
            }
            // Parameterized type matching
            if (bt instanceof java.lang.reflect.ParameterizedType beanPt
                    && requiredType instanceof java.lang.reflect.ParameterizedType reqPt) {
                if (typesMatch(beanPt, reqPt)) {
                    typeMatch = true;
                    break;
                }
            }
            // Raw type requested, parameterized bean type
            if (requiredType instanceof Class<?> reqClass
                    && bt instanceof java.lang.reflect.ParameterizedType beanPt) {
                if (reqClass == beanPt.getRawType()) {
                    typeMatch = true;
                    break;
                }
            }
        }
        // Object always matches any bean type (implicit supertype)
        if (!typeMatch && requiredType == Object.class && !beanTypes.isEmpty()) {
            typeMatch = true;
        }
        if (!typeMatch) return false;

        // CDI qualifier matching:
        // CDI spec 2.3.4: if no qualifier other than @Any/@Named, @Default is implied
        var effectiveBeanQualifiers = new java.util.LinkedHashSet<>(beanQualifiers);
        boolean hasExplicitQualifier = beanQualifiers.stream()
                .anyMatch(q -> q.annotationType() != jakarta.enterprise.inject.Any.class
                        && q.annotationType() != jakarta.inject.Named.class);
        if (!hasExplicitQualifier) {
            effectiveBeanQualifiers.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        effectiveBeanQualifiers.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);

        // If no required qualifiers specified, CDI implies @Default
        var effectiveRequired = requiredQualifiers.isEmpty()
                ? Set.<Annotation>of(jakarta.enterprise.inject.Default.Literal.INSTANCE)
                : requiredQualifiers;

        for (var req : effectiveRequired) {
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
        if (containsTypeVariable(specifiedType)) {
            throw new IllegalArgumentException("Specified event type cannot contain a type variable");
        }
        if (containsTypeVariable(observedEventType)) {
            throw new IllegalArgumentException("Observed event type cannot contain a type variable");
        }

        // Event type matching uses assignability (CDI spec: event type is assignable to observed type)
        if (!eventTypesMatch(specifiedType, observedEventType)) return false;

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

        // CDI 4.1 Section 5.2.5: raw types must be identical
        // Use name comparison to handle cross-classloader scenarios
        if (requiredType instanceof Class<?> reqClass) {
            if (beanType instanceof Class<?> btClass) {
                return reqClass == btClass || reqClass.getName().equals(btClass.getName());
            }
            // CDI 5.2.4: parameterized bean type matches raw required type only if
            // raw types are identical AND all type params are unbounded TVs or Object
            if (beanType instanceof java.lang.reflect.ParameterizedType pt) {
                if (reqClass != pt.getRawType()
                        && !reqClass.getName().equals(((Class<?>) pt.getRawType()).getName())) return false;
                for (Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof java.lang.reflect.TypeVariable<?> tv) {
                        for (Type bound : tv.getBounds()) {
                            if (bound != Object.class) return false;
                        }
                    } else if (arg != Object.class) {
                        return false;
                    }
                }
                return true;
            }
        }

        if (requiredType instanceof java.lang.reflect.ParameterizedType reqPt) {
            if (beanType instanceof java.lang.reflect.ParameterizedType beanPt) {
                // Raw types must be identical (compare by name for cross-classloader)
                if (beanPt.getRawType() != reqPt.getRawType()
                        && (!(beanPt.getRawType() instanceof Class<?> bc && reqPt.getRawType() instanceof Class<?> rc)
                            || !bc.getName().equals(rc.getName()))) return false;
                // Type arguments must match exactly (invariant)
                var reqArgs = reqPt.getActualTypeArguments();
                var beanArgs = beanPt.getActualTypeArguments();
                if (reqArgs.length != beanArgs.length) return false;
                for (int i = 0; i < reqArgs.length; i++) {
                    if (!typeArgumentsMatch(beanArgs[i], reqArgs[i])) return false;
                }
                return true;
            }
            // Raw bean type matches parameterized required type only if raw types identical
            if (beanType instanceof Class<?> btClass) {
                if (btClass != reqPt.getRawType()) return false;
                for (Type arg : reqPt.getActualTypeArguments()) {
                    if (arg instanceof java.lang.reflect.TypeVariable<?> tv) {
                        for (Type bound : resolvedBounds(tv)) {
                            if (bound != Object.class) return false;
                        }
                    } else if (arg instanceof java.lang.reflect.WildcardType wt) {
                        for (Type ub : wt.getUpperBounds()) {
                            if (ub != Object.class) return false;
                        }
                        for (Type lb : wt.getLowerBounds()) {
                            if (lb != Object.class && lb != null) return false; // wait, lower bounds shouldn't exist for it to be treated as unbounded
                        }
                    } else if (arg != Object.class) {
                        return false;
                    }
                }
                return true;
            }
        }

        return false;
    }

    private static boolean typeArgumentsMatch(Type beanArg, Type requiredArg) {
        if (beanArg.equals(requiredArg)) return true;

        // CDI spec (c/da/dc): required is wildcard
        if (requiredArg instanceof java.lang.reflect.WildcardType reqWild) {
            var upper = reqWild.getUpperBounds();
            var lower = reqWild.getLowerBounds();
            if (lower.length > 0) {
                // (dc) bean is TV: lower bound must be assignable to TV upper bounds
                // (c) bean is actual: lower bound must be assignable to bean arg
                if (beanArg instanceof java.lang.reflect.TypeVariable<?> beanTv) {
                    for (Type lb : lower) {
                        if (!lowerBoundAssignableToBounds(lb, beanTv)) return false;
                    }
                    return true;
                }
                for (Type lb : lower) {
                    if (!isTypeAssignableTo(lb, beanArg)) return false;
                }
                return true;
            }
            // ? extends X or unbounded ?
            if (upper.length == 1 && upper[0] == Object.class) return true;
            // (da) bean is TV: upper bound assignable to or from wildcard upper bound
            // (c) bean is actual: actual assignable to wildcard upper bound
            if (beanArg instanceof java.lang.reflect.TypeVariable<?> beanTv) {
                for (Type ub : upper) {
                    if (!upperBoundAssignableToOrFrom(ub, beanTv)) return false;
                }
                return true;
            }
            for (Type ub : upper) {
                if (!isTypeAssignableTo(beanArg, ub)) return false;
            }
            return true;
        }

        // CDI spec (f): required is TypeVariable, bean is TypeVariable
        // Each bean TV bound must be covered by at least one required TV bound
        if (requiredArg instanceof java.lang.reflect.TypeVariable<?> reqTv) {
            if (beanArg instanceof java.lang.reflect.TypeVariable<?> beanTv) {
                Type[] reqBounds = resolvedBounds(reqTv);
                for (Type beanBound : beanTv.getBounds()) {
                    if (beanBound == Object.class) continue;
                    boolean covered = false;
                    for (Type reqBound : reqBounds) {
                        if (isTypeAssignableTo(reqBound, beanBound)) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) return false;
                }
                return true;
            }
            // Bean is actual type, required is TypeVariable
            for (Type bound : reqTv.getBounds()) {
                if (bound != Object.class && !isTypeAssignableTo(beanArg, bound)) return false;
            }
            return true;
        }

        // CDI spec (e): bean is TypeVariable, required is actual type
        if (beanArg instanceof java.lang.reflect.TypeVariable<?> beanTv) {
            // Required actual type must be assignable to each upper bound of the TV
            for (Type bound : beanTv.getBounds()) {
                if (bound != Object.class && !isTypeAssignableTo(requiredArg, bound)) return false;
            }
            return true;
        }

        if (beanArg instanceof java.lang.reflect.WildcardType beanWild) {
            var upper = beanWild.getUpperBounds();
            if (upper.length > 0) {
                return isTypeAssignableTo(upper[0], requiredArg);
            }
        }

        if (beanArg instanceof java.lang.reflect.ParameterizedType && requiredArg instanceof java.lang.reflect.ParameterizedType) {
            return typesMatch(beanArg, requiredArg);
        }

        return false;
    }

    private static Type[] resolvedBounds(java.lang.reflect.TypeVariable<?> tv) {
        var bounds = tv.getBounds();
        var result = new java.util.ArrayList<Type>();
        for (Type b : bounds) {
            if (b instanceof java.lang.reflect.TypeVariable<?> nested) {
                for (Type nb : resolvedBounds(nested)) result.add(nb);
            } else if (b != Object.class) {
                result.add(b);
            }
        }
        return result.isEmpty() ? new Type[]{ Object.class } : result.toArray(new Type[0]);
    }

    private static boolean lowerBoundAssignableToBounds(Type lowerBound, java.lang.reflect.TypeVariable<?> tv) {
        Type[] lbBounds = (lowerBound instanceof java.lang.reflect.TypeVariable<?> lbTv)
                ? lbTv.getBounds() : new Type[]{ lowerBound };
        for (Type beanBound : tv.getBounds()) {
            if (beanBound == Object.class) continue;
            boolean satisfied = false;
            for (Type lb : lbBounds) {
                if (isTypeAssignableTo(lb, beanBound)) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) return false;
        }
        return true;
    }

    private static boolean upperBoundAssignableToOrFrom(Type type, java.lang.reflect.TypeVariable<?> tv) {
        Type[] otherBounds = (type instanceof java.lang.reflect.TypeVariable<?> otherTv)
                ? otherTv.getBounds() : new Type[]{ type };
        for (Type beanBound : tv.getBounds()) {
            if (beanBound == Object.class) continue;
            boolean satisfied = false;
            for (Type otherBound : otherBounds) {
                if (otherBound == Object.class) continue;
                if (isTypeAssignableTo(otherBound, beanBound) || isTypeAssignableTo(beanBound, otherBound)) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) return false;
        }
        return true;
    }

    private static boolean isTypeAssignableTo(Type subType, Type superType) {
        if (subType.equals(superType)) return true;
        if (superType == Object.class) return true;
        // Resolve TypeVariable to its first (primary) upper bound
        if (subType instanceof java.lang.reflect.TypeVariable<?> tv) {
            var bounds = tv.getBounds();
            return bounds.length > 0 && isTypeAssignableTo(bounds[0], superType);
        }
        if (subType instanceof Class<?> subClass && superType instanceof Class<?> superClass) {
            return superClass.isAssignableFrom(subClass);
        }
        if (subType instanceof java.lang.reflect.ParameterizedType subPt && superType instanceof Class<?> superClass) {
            return superClass.isAssignableFrom((Class<?>) subPt.getRawType());
        }
        if (subType instanceof Class<?> subClass && superType instanceof java.lang.reflect.ParameterizedType superPt) {
            return ((Class<?>) superPt.getRawType()).isAssignableFrom(subClass);
        }
        if (subType instanceof java.lang.reflect.ParameterizedType subPt && superType instanceof java.lang.reflect.ParameterizedType superPt) {
            return typesMatch(subPt, superPt);
        }
        return false;
    }

    /**
     * Event type matching uses assignability (CDI spec: fired event type must be assignable to observed type).
     */
    private static boolean eventTypesMatch(Type eventType, Type observedType) {
        if (eventType.equals(observedType)) return true;
        if (observedType instanceof Class<?> obsClass && eventType instanceof Class<?> evtClass) {
            return obsClass.isAssignableFrom(evtClass);
        }
        if (observedType instanceof java.lang.reflect.ParameterizedType obsPt) {
            if (eventType instanceof java.lang.reflect.ParameterizedType evtPt) {
                if (!eventTypesMatch(evtPt.getRawType(), obsPt.getRawType())) return false;
                var obsArgs = obsPt.getActualTypeArguments();
                var evtArgs = evtPt.getActualTypeArguments();
                if (obsArgs.length != evtArgs.length) return false;
                for (int i = 0; i < obsArgs.length; i++) {
                    if (!typeArgumentsMatch(evtArgs[i], obsArgs[i])) return false;
                }
                return true;
            }
            if (eventType instanceof Class<?> evtClass) {
                return ((Class<?>) obsPt.getRawType()).isAssignableFrom(evtClass);
            }
        }
        return false;
    }

    private static boolean isPrimitiveWrapperMatch(Class<?> a, Class<?> b) {
        if (a.isPrimitive()) return b == primitiveToWrapper(a);
        if (b.isPrimitive()) return a == primitiveToWrapper(b);
        return false;
    }

    private static boolean containsTypeVariable(Type type) {
        if (type instanceof java.lang.reflect.TypeVariable<?>) return true;
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) return true;
            }
        }
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            return containsTypeVariable(gat.getGenericComponentType());
        }
        if (type instanceof java.lang.reflect.WildcardType wt) {
            for (var b : wt.getUpperBounds()) if (containsTypeVariable(b)) return true;
            for (var b : wt.getLowerBounds()) if (containsTypeVariable(b)) return true;
        }
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

    /**
     * CDI qualifier matching: two qualifiers are equal if they have the same type
     * and all non-@Nonbinding members have the same values.
     */
    private static boolean qualifierEquals(Annotation a, Annotation b) {
        if (!a.annotationType().equals(b.annotationType())) return false;
        // If annotation has no members, type equality is sufficient
        var methods = a.annotationType().getDeclaredMethods();
        if (methods.length == 0) return true;
        // Compare all non-@Nonbinding members
        try {
            for (var method : methods) {
                if (method.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) continue;
                var valA = method.invoke(a);
                var valB = method.invoke(b);
                if (!java.util.Objects.deepEquals(valA, valB)) return false;
            }
        } catch (Exception e) {
            return a.equals(b);
        }
        return true;
    }
}
