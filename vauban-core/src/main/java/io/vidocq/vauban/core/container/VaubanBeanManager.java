/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.annotation.AnnotationKey;
import io.vidocq.vauban.core.annotation.AnnotationTypes;
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.event.EventDispatcher;
import io.vidocq.vauban.core.event.EventImpl;
import io.vidocq.vauban.core.event.VaubanObserverMethod;
import io.vidocq.vauban.core.interceptor.InterceptorManager;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.core.types.TypeInfoUtils;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.el.ELResolver;
import jakarta.el.ExpressionFactory;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.*;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Minimal BeanManager implementation for Vauban.
 * Only the essential methods are functional; the rest throw UnsupportedOperationException.
 *
 * <p>Serializable, but never written itself: it belongs to its container. It writes a {@link BeanManagerReference}
 * instead, which reads back as the importing container's bean manager, so a session bean that holds the built-in
 * {@code BeanManager} moves to another container (foy#114). Serialization calls {@code writeReplace} on a
 * serializable class only.</p>
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class VaubanBeanManager implements BeanManager, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // Volatile immutable sets: assigned once at startup, read-only after — thread-safe by design
    @SuppressWarnings("java:S3077")
    private static volatile Set<Class<? extends Annotation>> customQualifierTypes = Set.of();
    @SuppressWarnings("java:S3077")
    private static volatile Set<Class<? extends Annotation>> customInterceptorBindingTypes = Set.of();
    @SuppressWarnings("java:S3077")
    private static volatile Set<Class<? extends Annotation>> customStereotypeTypes = Set.of();

    public static void setCustomQualifierTypes(Set<Class<? extends Annotation>> types) {
        customQualifierTypes = types != null ? types : Set.of();
    }

    public static void setCustomInterceptorBindingTypes(Set<Class<? extends Annotation>> types) {
        customInterceptorBindingTypes = types != null ? types : Set.of();
    }

    public static void setCustomStereotypeTypes(Set<Class<? extends Annotation>> types) {
        customStereotypeTypes = types != null ? types : Set.of();
    }

    public static boolean isCustomQualifier(Class<? extends Annotation> annotationType) {
        return customQualifierTypes.contains(annotationType);
    }

    /** The interceptor binding types a BCE declared; a new set replaces it as a whole. */
    public static Set<Class<? extends Annotation>> customInterceptorBindingTypes() {
        return customInterceptorBindingTypes;
    }

    public static boolean isCustomInterceptorBinding(Class<?> annotationType) {
        return customInterceptorBindingTypes.contains(annotationType);
    }

    private final transient VaubanContainer container;
    private final transient Map<Class<? extends Annotation>, List<Context>> contexts;
    private static final String MSG_NOT_IMPLEMENTED = "Not yet implemented";
    private static final String MSG_NOT_A_QUALIFIER = "Not a qualifier: ";

    private final transient Collection<ManagedBean<?>> beans;
    private final transient EventDispatcher eventDispatcher;
    private final transient InterceptorManager interceptorManager;
    private transient List<Bean<?>> builtInBeans;
    /**
     * The bean types of this container's beans, in the index model. Each bean builds its types once, so this holds one
     * entry per bean type of the deployment. A caller's type never goes in: a {@code ParameterizedType} of its own,
     * whose {@code equals} may be identity, would add an entry at each call.
     */
    private final transient Map<Type, TypeInfo> convertedTypes = new java.util.concurrent.ConcurrentHashMap<>();

    public EventDispatcher getEventDispatcher() {
        return eventDispatcher;
    }

    /** The container this bean manager belongs to. */
    VaubanContainer container() {
        return container;
    }

    /** The mark written in place of this bean manager: it reads back as the importing container's. */
    @Serial
    private Object writeReplace() throws ObjectStreamException {
        return new BeanManagerReference();
    }

    /**
     * Refuses a stream that carries a bean manager itself rather than its form: none is ever written, and its fields
     * would read back {@code null}.
     */
    @Serial
    private void readObject(ObjectInputStream in) throws InvalidObjectException {
        throw new InvalidObjectException("a bean manager is read through its serial form only");
    }

    public VaubanBeanManager(VaubanContainer container,
                             Map<Class<? extends Annotation>, List<Context>> contexts,
                             Collection<ManagedBean<?>> beans,
                             EventDispatcher eventDispatcher,
                             InterceptorManager interceptorManager) {
        this.container = container;
        this.contexts = contexts;
        this.beans = new ArrayList<>(beans);
        this.eventDispatcher = eventDispatcher;
        this.interceptorManager = interceptorManager;
    }

    /** The container's request context, for the built-in {@code @ActivateRequestContext} interceptor. */
    public io.vidocq.vauban.core.context.RequestContext requestContext() {
        return container.requestContext();
    }

    private List<Bean<?>> getBuiltInBeans() {
        if (builtInBeans == null) {
            builtInBeans = List.of(
                new BuiltInBean<>(BeanManager.class,
                    Set.of(BeanManager.class, jakarta.enterprise.inject.spi.BeanContainer.class),
                    () -> this),
                new BuiltInBean<>(Event.class,
                    Set.of(Event.class, Object.class),
                    this::getEvent),
                // CDI 4.1 §5.6.2: the built-in bean of type Instance<X> is of type Provider<X> too (BUG-20261116-10)
                new BuiltInBean<>(Instance.class,
                    Set.of(Instance.class, jakarta.inject.Provider.class),
                    this::createInstance),
                new BuiltInBean<>(InjectionPoint.class,
                    Set.of(InjectionPoint.class),
                    VaubanContainer::getCurrentInjectionPoint),
                // CDI 4.1 §6.5.8: @Dependent, one controller per lookup (Vidocq/vauban#147).
                new BuiltInBean<>(jakarta.enterprise.context.control.RequestContextController.class,
                    Set.of(jakarta.enterprise.context.control.RequestContextController.class, Object.class),
                    () -> new io.vidocq.vauban.core.context.VaubanRequestContextController(container.requestContext()))
            );
        }
        return builtInBeans;
    }

    // --- Functional methods ---

    /** Whether {@code beanType} is {@code Event}, {@code Instance} or {@code Provider}: built per injection point. */
    static boolean isBuiltInPerInjectionPoint(Type beanType) {
        var raw = beanType instanceof java.lang.reflect.ParameterizedType pt ? pt.getRawType() : beanType;
        return raw == Event.class || raw == Instance.class || raw == jakarta.inject.Provider.class;
    }

    @Override
    public Object getReference(Bean<?> bean, Type beanType, CreationalContext<?> ctx) {
        // Validate that beanType is actually a type of the bean. The types of a bean of this container are converted
        // once, as getBeans converts them; those of a Bean a caller makes are not kept.
        Predicate<Type> typeMatching = bean instanceof ManagedBean<?> || bean instanceof BuiltInBean<?>
                ? deploymentTypesMatching(beanType)
                : bt -> beanTypeMatches(bt, beanType);
        boolean valid = bean.getTypes().stream().anyMatch(typeMatching);
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

        // A built-in Event, Instance or Provider takes the requested type (BUG-20261116-07), which the built-in bean's
        // own supplier does not know. getReference has no injection point, even while one is being injected: its
        // qualifier is @Default, and it is a programmatic lookup.
        if (bean instanceof BuiltInBean<?> && isBuiltInPerInjectionPoint(beanType)) {
            return container.lookedUpBuiltIn(beanType,
                    new Annotation[] {jakarta.enterprise.inject.Default.Literal.INSTANCE});
        }

        // For normal-scoped beans, return client proxy via container
        if (bean instanceof ManagedBean<?> mb
                && mb.descriptor().scope().isNormal()) {
            return container.getOrCreateProxyForBean(mb, beanType);
        }

        var scope = bean.getScope();
        var context = getFirstContext(scope);
        if (context == null) {
            context = getFirstContext(jakarta.enterprise.context.Dependent.class);
        }
        if (context == null) {
            throw new jakarta.enterprise.context.ContextNotActiveException(
                    "No active context for scope: " + scope.getName());
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
        validateQualifiers(qualifiers);
        // CDI spec: with no qualifier, @Default is required
        var required = qualifiers == null || qualifiers.length == 0
                ? Set.of(AnnotationKey.DEFAULT)
                : annotationTypes().keys(qualifiers);
        return getBeans(beanType, required);
    }

    /**
     * Resolution on keys: a bean's qualifiers are reduced to their keys once, and an injection point
     * or a programmatic lookup converts its own once, so matching reads no annotation.
     */
    @SuppressWarnings({"java:S135", "java:S1199"})
    Set<Bean<?>> getBeans(Type beanType, Set<AnnotationKey> requiredKeys) {
        var required = requiredKeys.isEmpty() ? Set.of(AnnotationKey.DEFAULT) : requiredKeys;
        // CDI spec: primitive types and wrappers are identical
        if (beanType instanceof Class<?> c && c.isPrimitive()) {
            beanType = primitiveToWrapper(c);
        }
        // Validate: type variable not allowed
        if (beanType instanceof java.lang.reflect.TypeVariable<?>) {
            throw new IllegalArgumentException("TypeVariable is not a legal bean type");
        }

        var result = new LinkedHashSet<Bean<?>>();
        var matcher = container.qualifierMatcher();
        var typeMatching = deploymentTypesMatching(beanType);

        // Check built-in beans
        for (var builtIn : getBuiltInBeans()) {
            boolean typeMatch = false;
            for (var bt : builtIn.getTypes()) {
                if (typeMatching.test(bt)) {
                    typeMatch = true;
                    break;
                }
                // Built-in beans like Instance and Event match any parameterization of their raw type
                // Name comparison intentional: cross-classloader CDI type matching
                @SuppressWarnings("java:S1872")
                boolean rawMatch = bt instanceof Class<?> btClass && beanType instanceof java.lang.reflect.ParameterizedType reqPt
                        && reqPt.getRawType() instanceof Class<?> reqRaw
                        && (btClass == reqRaw || btClass.getName().equals(reqRaw.getName()));
                if (rawMatch) {
                    typeMatch = true;
                    break;
                }
            }
            if (typeMatch) {
                // CDI spec 3.11: Event and Instance built-in beans have "every qualifier type"
                // (they match any qualifier combination, so qualifier matching always succeeds)
                boolean isWildcardQualifier = builtIn.getBeanClass() == Event.class
                        || builtIn.getBeanClass() == Instance.class;
                if (isWildcardQualifier || matches(annotationTypes().keys(builtIn.getQualifiers()), required)) {
                    result.add(builtIn);
                }
            }
        }

        for (var bean : beans) {
            // CDI spec: disabled alternatives (no @Priority) are excluded from resolution
            if (bean.isAlternative() && bean instanceof ManagedBean<?> mb
                    && !mb.descriptor().priorityDeclared()) {
                continue;
            }

            // Type matching (supports Class, ParameterizedType, etc.)
            boolean typeMatch = false;
            for (var bt : bean.getTypes()) {
                if (typeMatching.test(bt)) {
                    typeMatch = true;
                    break;
                }
            }

            if (!typeMatch) continue;

            if (matches(bean.qualifierKeys(matcher), required)) {
                result.add(bean);
            }
        }

        return result;
    }

    /** CDI 4.1 §2.5: every required qualifier must be one of the bean's; {@code @Any} matches every bean. */
    private static boolean matches(Set<AnnotationKey> beanKeys, Set<AnnotationKey> required) {
        for (var key : required) {
            if (key.isAny()) continue;
            if (!beanKeys.contains(key)) return false;
        }
        return true;
    }

    private AnnotationTypes annotationTypes() {
        return container.qualifierMatcher().types();
    }

    /** CDI spec: every qualifier of a lookup must be a qualifier type, and no type twice unless repeatable. */
    private void validateQualifiers(Annotation... qualifiers) {
        if (qualifiers == null) return;
        var seen = new HashSet<Class<?>>();
        for (var q : qualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException(q.annotationType().getName() + " is not a qualifier");
            }
            if (!seen.add(q.annotationType())
                    && !q.annotationType().isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                throw new IllegalArgumentException("Duplicate qualifier: " + q.annotationType().getName());
            }
        }
    }

    @Override
    public Set<Bean<?>> getBeans(String name) {
        var result = new LinkedHashSet<Bean<?>>();
        for (var bean : beans) {
            if (Objects.equals(name, bean.getName())) {
                // CDI spec: disabled alternatives (no @Priority) are excluded from resolution
                if (bean.isAlternative() && bean instanceof ManagedBean<?> mb
                        && !mb.descriptor().priorityDeclared()) {
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

        var matching = new java.util.ArrayList<String>(beans.size());
        for (var b : beans) {
            matching.add(b.getBeanClass().getName() + b.getQualifiers());
        }
        throw new jakarta.enterprise.inject.AmbiguousResolutionException(
            "Ambiguous dependency: " + beans.size() + " beans match: " + matching);
    }

    @Override
    public Context getContext(Class<? extends Annotation> scopeType) {
        var list = findContexts(scopeType);
        if (list.isEmpty()) {
            throw new jakarta.enterprise.context.ContextNotActiveException(
                    "No context for scope: " + scopeType.getName());
        }
        for (var ctx : list) {
            if (ctx.isActive()) return ctx;
        }
        throw new jakarta.enterprise.context.ContextNotActiveException(
                "Context not active for scope: " + scopeType.getName());
    }

    @Override
    public Collection<Context> getContexts(Class<? extends Annotation> scopeType) {
        return List.copyOf(findContexts(scopeType));
    }

    /** The contexts registered for {@code scopeType}, matched by name across class loaders; empty when none. */
    private List<Context> findContexts(Class<? extends Annotation> scopeType) {
        var list = contexts.get(scopeType);
        if (list != null && !list.isEmpty()) return list;
        var scopeName = scopeType.getName();
        for (var entry : contexts.entrySet()) {
            if (entry.getKey().getName().equals(scopeName)) {
                return java.util.Objects.requireNonNullElse(entry.getValue(), List.of());
            }
        }
        return List.of();
    }

    private Context getFirstContext(Class<? extends Annotation> scopeType) {
        var list = findContexts(scopeType);
        return list.isEmpty() ? null : list.getFirst();
    }

    @Override
    public <T> CreationalContext<T> createCreationalContext(Contextual<T> contextual) {
        return new CreationalContextImpl<>();
    }

    @Override
    public boolean isQualifier(Class<? extends Annotation> annotationType) {
        return ClassMetadataCache.isDeclaredQualifier(annotationType)
                || annotationType == jakarta.enterprise.inject.Default.class
                || annotationType == jakarta.enterprise.inject.Any.class
                || annotationType == jakarta.inject.Named.class
                || customQualifierTypes.contains(annotationType);
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
        return annotationType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)
                || customStereotypeTypes.contains(annotationType);
    }

    @Override
    public boolean isInterceptorBinding(Class<? extends Annotation> annotationType) {
        return annotationType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)
                || customInterceptorBindingTypes.contains(annotationType);
    }

    @Override
    public boolean isPassivatingScope(Class<? extends Annotation> annotationType) {
        var normalScope = annotationType.getAnnotation(jakarta.enterprise.context.NormalScope.class);
        return normalScope != null && normalScope.passivating();
    }

    // --- Stub methods (UnsupportedOperationException) ---

    @Override
    public Object getInjectableReference(InjectionPoint ij, CreationalContext<?> ctx) {
        var matchingBeans = getBeans(ij.getType(), ij.getQualifiers().toArray(new Annotation[0]));
        if (matchingBeans.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "No bean for injection point: " + ij);
        }
        var bean = resolve(matchingBeans);
        if (bean instanceof BuiltInBean<?> && isBuiltInPerInjectionPoint(ij.getType())) {
            // What an injection at ij gives, with its type and qualifiers (BUG-20261116-07)
            return container.injectedBuiltIn(ij);
        }
        try {
            return VaubanContainer.callWithInjectionPoint(ij, () -> getReference(bean, ij.getType(), ctx));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.inject.CreationException(e);
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
        var candidateBeans = getBeans(injectionPoint.getType(),
                injectionPoint.getQualifiers().toArray(new Annotation[0]));
        if (candidateBeans.isEmpty()) {
            throw new jakarta.enterprise.inject.UnsatisfiedResolutionException(
                    "Unsatisfied: " + injectionPoint);
        }
        if (candidateBeans.size() > 1) {
            var resolved = resolve(candidateBeans);
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
        // CDI spec: if the runtime type of the event object contains unresolvable type variables, throw IAE
        if (event.getClass().getTypeParameters().length > 0) {
            throw new IllegalArgumentException(
                    "Event type contains unresolvable type variable: " + event.getClass());
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
        var eventKeys = annotationTypes().keys(qualifiers);
        // CDI spec: resolveObserverMethods returns both sync and async observers
        var matching = new java.util.ArrayList<>(eventDispatcher.findMatchingObservers(event.getClass(), false, eventKeys));
        matching.addAll(eventDispatcher.findMatchingObservers(event.getClass(), true, eventKeys));
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

        var descriptors = interceptorManager.resolveInterceptors(java.util.Arrays.asList(interceptorBindings));
        var result = new ArrayList<Interceptor<?>>();
        for (var descriptor : descriptors) {
            var vi = new VaubanInterceptor<>(descriptor, interceptorManager::getOrCreateInstance,
                    container.classLoader());
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
        Collections.addAll(result, bindingType.getAnnotations());
        return result;
    }

    @Override
    public Set<Annotation> getStereotypeDefinition(Class<? extends Annotation> stereotype) {
        var result = new LinkedHashSet<Annotation>();
        Collections.addAll(result, stereotype.getAnnotations());
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
        throw new IllegalStateException(MSG_NOT_IMPLEMENTED);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ExpressionFactory wrapExpressionFactory(ExpressionFactory expressionFactory) {
        throw new IllegalStateException(MSG_NOT_IMPLEMENTED);
    }

    @Override
    public <T> AnnotatedType<T> createAnnotatedType(Class<T> type) {
        return new VaubanAnnotatedType<>(type);
    }

    @Override
    public <T> InjectionTargetFactory<T> getInjectionTargetFactory(AnnotatedType<T> annotatedType) {
        return bean -> new InjectionTarget<T>() {
            @Override
            @SuppressWarnings("unchecked")
            public T produce(CreationalContext<T> ctx) {
                Class<T> clazz = annotatedType.getJavaClass();
                java.lang.reflect.Constructor<T> injectCtor = null;
                for (var ctor : clazz.getDeclaredConstructors()) {
                    if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        injectCtor = (java.lang.reflect.Constructor<T>) ctor;
                        break;
                    }
                }
                try {
                    if (injectCtor == null) {
                        return container.getVaubanLookup().newInstance(clazz);
                    }
                    var paramTypes = injectCtor.getParameterTypes();
                    var genericParamTypes = injectCtor.getGenericParameterTypes();
                    var params = injectCtor.getParameters();
                    var args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        var quals = QualifierHelper.extractParamQualifiers(params[i]);
                        args[i] = container.resolveParameter(paramTypes[i], genericParamTypes[i], ctx, quals, injectCtor, null, params[i], i);
                    }
                    return clazz.cast(container.getVaubanLookup().newInstance(injectCtor, args));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new jakarta.enterprise.inject.CreationException(e);
                }
            }

            @Override
            public void inject(T instance, CreationalContext<T> ctx) {
                container.beanInjector().performInjection(instance, null, ctx);
            }

            @Override
            public void postConstruct(T instance) {
                container.beanLifecycle.callPostConstruct(instance, null, null);
            }

            @Override
            public void preDestroy(T instance) {
                for (var m : BeanLifecycle.collectLifecycleMethodsInHierarchy(
                        instance.getClass(), jakarta.annotation.PreDestroy.class)) {
                    try {
                        container.getVaubanLookup().invokeMethod(instance, m);
                    } catch (Exception e) {
                        throw new LifecycleCallbackException("@PreDestroy failed: " + m, e);
                    }
                }
            }

            @Override
            public void dispose(T instance) {
                // no-op: lifecycle managed by the caller
            }

            @Override
            public java.util.Set<InjectionPoint> getInjectionPoints() {
                return java.util.Set.of();
            }
        };
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
        throw new IllegalStateException(MSG_NOT_IMPLEMENTED);
    }

    @Override
    public <T> InterceptionFactory<T> createInterceptionFactory(CreationalContext<T> ctx, Class<T> clazz) {
        throw new IllegalStateException(MSG_NOT_IMPLEMENTED);
    }

    @Override
    public Event<Object> getEvent() {
        var ip = VaubanContainer.getCurrentInjectionPoint();
        return new EventImpl<>(eventDispatcher, new java.lang.annotation.Annotation[]{
                jakarta.enterprise.inject.Default.Literal.INSTANCE,
                jakarta.enterprise.inject.Any.Literal.INSTANCE
        }, ip);
    }

    @Override
    public Instance<Object> createInstance() {
        return new InstanceImpl<>(container, Object.class);
    }

    @SuppressWarnings("java:S135")
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
                throw new IllegalArgumentException(MSG_NOT_A_QUALIFIER + q.annotationType());
            }
        }
        for (var q : beanQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException(MSG_NOT_A_QUALIFIER + q.annotationType());
            }
        }

        // Type matching — required type must be assignable from at least one of the beanTypes
        // CDI spec: isMatchingBean checks if requiredType is assignable from a bean type
        boolean typeMatch = false;
        for (var bt : beanTypes) {
            if (bt instanceof java.lang.reflect.TypeVariable<?>) continue;
            if (bt instanceof java.lang.reflect.WildcardType) continue;
            // CDI spec: parameterized types with wildcards are not legal bean types
            if (bt instanceof java.lang.reflect.ParameterizedType bpt
                    && containsWildcard(bpt)) continue;
            // The rules of getBeans and of the resolution of an injection point: a raw required type took any
            // parameterization of its class here (BUG-20261117-16)
            if (beanTypeMatches(bt, requiredType)) {
                typeMatch = true;
                break;
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
                throw new IllegalArgumentException(MSG_NOT_A_QUALIFIER + q.annotationType());
            }
        }
        for (var q : observedEventQualifiers) {
            if (!isQualifier(q.annotationType())) {
                throw new IllegalArgumentException(MSG_NOT_A_QUALIFIER + q.annotationType());
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
    private static boolean containsWildcard(java.lang.reflect.ParameterizedType pt) {
        for (var arg : pt.getActualTypeArguments()) {
            if (arg instanceof java.lang.reflect.WildcardType) return true;
            if (arg instanceof java.lang.reflect.ParameterizedType nested && containsWildcard(nested)) return true;
        }
        return false;
    }

    /**
     * The rules of the resolution of an injection point with no index, the subtypes read from the classes: for
     * {@link #typesMatch(Type, Type)}, which has no container at hand.
     */
    private static final AssignabilityRules UNINDEXED_RULES = new AssignabilityRules(new IndexBuilder().build());

    /** {@link #typesMatch(AssignabilityRules, Type, Type, Function)} with no index and no cache. */
    static boolean typesMatch(Type beanType, Type requiredType) {
        return typesMatch(UNINDEXED_RULES, beanType, requiredType, TypeInfoUtils::fromReflectType);
    }

    /**
     * {@link #typesMatch(AssignabilityRules, Type, Type, Function)} with the container's rules, over its index, for
     * bean types a caller gives: nothing is kept.
     */
    private boolean beanTypeMatches(Type beanType, Type requiredType) {
        return typesMatch(container.assignability(), beanType, requiredType, TypeInfoUtils::fromReflectType);
    }

    /**
     * Whether a bean type of this container's beans matches {@code requiredType}, as
     * {@link #beanTypeMatches(Type, Type)}: the required type converted once, here, and each bean type once for the
     * life of the container ({@link #convertedTypes}).
     */
    private Predicate<Type> deploymentTypesMatching(Type requiredType) {
        if (!isRequiredType(requiredType)) {
            return requiredType::equals;
        }
        var rules = container.assignability();
        var required = TypeInfoUtils.fromReflectType(boxed(requiredType));
        return beanType -> beanType.equals(requiredType) || rules.beanTypeMatches(
                convertedTypes.computeIfAbsent(boxed(beanType), TypeInfoUtils::fromReflectType), required);
    }

    /**
     * {@code beanType} matches {@code requiredType} by the rules the resolution of an injection point applies at build
     * and at start, {@link AssignabilityRules#beanTypeMatches}, a primitive boxed first. {@code getBeans} kept a copy
     * of them that read a type variable's declared bounds where the resolution read the uppermost ones, and took a
     * wildcard for an unbounded type variable: an injection point the start validated failed at run time
     * (BUG-20261117-16).
     */
    private static boolean typesMatch(AssignabilityRules rules, Type beanType, Type requiredType,
                                      Function<Type, TypeInfo> converter) {
        return beanType.equals(requiredType) || (isRequiredType(requiredType) && rules.beanTypeMatches(
                converter.apply(boxed(beanType)), converter.apply(boxed(requiredType))));
    }

    /**
     * A class, a parameterized type or an array type: what a bean type is matched against. A type variable or a
     * wildcard matches nothing but itself, as in Weld's {@code BeanTypeAssignabilityRules}.
     */
    private static boolean isRequiredType(Type type) {
        return type instanceof Class<?> || type instanceof java.lang.reflect.ParameterizedType
                || type instanceof java.lang.reflect.GenericArrayType;
    }

    /** {@code type}, or its wrapper when it is a primitive class. */
    private static Type boxed(Type type) {
        return type instanceof Class<?> c && c.isPrimitive() ? primitiveToWrapper(c) : type;
    }

    /**
     * An event type argument against an observed one (CDI 4.1, 10.3.1). Neither holds a type variable:
     * {@code isMatchingEvent} refuses one before it gets here, so the rules of a type variable have no use.
     */
    private static boolean typeArgumentsMatch(Type eventArg, Type observedArg) {
        if (eventArg.equals(observedArg)) return true;

        if (observedArg instanceof java.lang.reflect.WildcardType wildcard) {
            var lower = wildcard.getLowerBounds();
            if (lower.length > 0) {
                for (Type lb : lower) {
                    if (!isTypeAssignableTo(lb, eventArg)) return false;
                }
                return true;
            }
            for (Type ub : wildcard.getUpperBounds()) {
                if (!isTypeAssignableTo(eventArg, ub)) return false;
            }
            return true;
        }

        if (eventArg instanceof java.lang.reflect.WildcardType eventWildcard) {
            var upper = eventWildcard.getUpperBounds();
            if (upper.length > 0) {
                return isTypeAssignableTo(upper[0], observedArg);
            }
        }

        if (eventArg instanceof java.lang.reflect.ParameterizedType eventPt
                && observedArg instanceof java.lang.reflect.ParameterizedType observedPt) {
            return sameRawTypeAndArguments(eventPt, observedPt);
        }

        return false;
    }

    /**
     * The same raw type, by name, and each argument matching by {@link #typeArgumentsMatch}: how the type arguments of
     * an event recurse into a parameterized argument (CDI 4.1, 10.3.1, "according to these rules"). The bean types
     * of {@code getBeans} follow {@link AssignabilityRules} instead (BUG-20261117-16).
     */
    private static boolean sameRawTypeAndArguments(java.lang.reflect.ParameterizedType sub,
                                                   java.lang.reflect.ParameterizedType sup) {
        if (!sub.getRawType().getTypeName().equals(sup.getRawType().getTypeName())) return false;
        var subArgs = sub.getActualTypeArguments();
        var supArgs = sup.getActualTypeArguments();
        if (subArgs.length != supArgs.length) return false;
        for (int i = 0; i < subArgs.length; i++) {
            if (!typeArgumentsMatch(subArgs[i], supArgs[i])) return false;
        }
        return true;
    }

    private static boolean isTypeAssignableTo(Type subType, Type superType) {
        if (subType.equals(superType)) return true;
        if (superType == Object.class) return true;
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
            return sameRawTypeAndArguments(subPt, superPt);
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

}
