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

import io.vidocq.vauban.api.GeneratedCoverage;
import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.bean.model.DisposerDescriptor;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.interceptor.InterceptedShape;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.interceptor.InvocationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which code generator covers each operation a bean, an observer or an interceptor needs, and which operations fall
 * back to reflection: the build's answer, from what each generated {@link VaubanComponentProvider} declares in
 * {@link VaubanComponentProvider#coverage()}. For diagnostics, such as the Vidocq dev console.
 *
 * <p>It reads metadata only — declared fields and methods without {@code setAccessible}, and decisions the container
 * recorded at boot — creates no bean, reads no context and calls no provider method but {@code coverage()}, once per
 * provider when {@link VaubanContainer#codegenCoverage()} builds it.
 */
public final class CodegenCoverage {

    /** What covers a row, summed up. */
    public enum Verdict {
        /** Every operation runs code the annotation processor generated. */
        APT,
        /** Every operation runs code the Class-File API generated. */
        CLASS_FILE,
        /** Every operation runs generated code, from both generators. */
        APT_AND_CLASS_FILE,
        /** Some operations run generated code, the others fall back. */
        PARTIAL,
        /** No operation runs generated code. */
        REFLECTION,
        /** A provider predating {@code coverage()} serves an uncovered operation: whether it runs it is unknown. */
        UNKNOWN,
        /** Nothing to cover: a synthetic or built-in bean, or a synthetic observer. */
        NOT_APPLICABLE
    }

    /**
     * The coverage of one row.
     *
     * @param verdict      what covers it
     * @param byReflection the operations that fall back — or, for {@link Verdict#UNKNOWN}, those whose coverage is
     *                     unknown — such as {@code field logger} or {@code @PostConstruct init()}, in the order the
     *                     container runs them
     */
    public record Coverage(Verdict verdict, List<String> byReflection) {
        public Coverage {
            Objects.requireNonNull(verdict, "verdict");
            byReflection = List.copyOf(byReflection);
        }
    }

    /** How the container runs an operation: through which provider method, or none. */
    enum Kind {
        INSTANTIATE, INJECT_FIELD, INVOKE, CLIENT_PROXY,
        /** A class generated at build time and loaded as is: covered, with no generator of its own. */
        PRE_GENERATED,
        /** No provider method runs it: always reflection or generation at boot. */
        NONE
    }

    /**
     * One operation a row needs.
     *
     * @param kind  how the container runs it
     * @param key   the key the container passes to the provider for it, built as the container builds it
     * @param label how the dev console names it
     * @param owner the class whose package's provider would run it
     */
    record Operation(Kind kind, String key, String label, Class<?> owner) {}

    private record Declared(VaubanComponentProvider provider, GeneratedCoverage coverage) {}

    private final VaubanContainer container;
    private final List<Declared> declared;

    CodegenCoverage(VaubanContainer container) {
        this.container = container;
        this.declared = container.componentProviders().providers().stream()
                .map(provider -> new Declared(provider, provider.coverage())).toList();
    }

    /** The coverage of a bean the bean manager lists. */
    public Coverage of(Bean<?> bean) {
        return verdict(operations(bean));
    }

    /** The coverage of an observer method the event dispatcher lists. */
    public Coverage of(ObserverDescriptor observer) {
        return verdict(operations(observer));
    }

    /** The coverage of an interceptor the interceptor manager lists. */
    public Coverage of(InterceptorDescriptor interceptor) {
        return verdict(operations(interceptor));
    }

    List<Operation> operations(ObserverDescriptor observer) {
        if (observer.isSynthetic()) {
            return List.of();
        }
        Class<?> declaring = load(observer.declaringClass().value());
        if (declaring == null) {
            return List.of();
        }
        Method method = observerMethod(declaring, observer);
        return List.of(method == null ? none("observer " + observer.methodName() + "()", declaring)
                : invoke(method, "observer"));
    }

    List<Operation> operations(InterceptorDescriptor interceptor) {
        Class<?> type = load(interceptor.interceptorClass().value());
        if (type == null) {
            return List.of();
        }
        var ops = new ArrayList<Operation>();
        ops.add(new Operation(Kind.INSTANTIATE, type.getName(), "constructor", type));
        // An interceptor is created as a bean, so performInjection runs its fields and initializers alike.
        for (Member member : BeanInjector.injectionOrder(type)) {
            ops.add(member instanceof Field field ? field(field) : invoke((Method) member, "initializer"));
        }
        Method own = ownPostConstruct(type);
        if (own != null) {
            ops.add(invoke(own, "@PostConstruct"));
        }
        // VaubanInvocationContext.InterceptorInvocation calls these by Method.invoke: no provider runs them (#109).
        if (interceptor.aroundInvokeMethod() != null) {
            ops.add(none("@AroundInvoke " + interceptor.aroundInvokeMethod() + "()", type));
        }
        if (interceptor.aroundConstructMethod() != null) {
            ops.add(none("@AroundConstruct " + interceptor.aroundConstructMethod() + "()", type));
        }
        for (Method method : lifecycleInterceptorMethods(type, PostConstruct.class)) {
            ops.add(none("@PostConstruct " + method.getName() + "(InvocationContext)", type));
        }
        for (Method method : lifecycleInterceptorMethods(type, PreDestroy.class)) {
            ops.add(none("@PreDestroy " + method.getName() + "(InvocationContext)", type));
        }
        return ops;
    }

    /** The class, loaded as the container loads it but never initialized: no static initializer of the application runs. */
    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, container.classLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * The observer's method, as {@code EventDispatcher.findMethod} finds it, but by the declared event type: no event
     * is at hand. Up the hierarchy, a method of that name with an {@code @Observes} or {@code @ObservesAsync}
     * parameter of that type; failing that, the first such method of that name.
     */
    private static Method observerMethod(Class<?> declaring, ObserverDescriptor observer) {
        String event = rawName(observer.eventType());
        Method byName = null;
        for (Class<?> type = declaring; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(observer.methodName())) continue;
                for (var parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(Observes.class)
                            || parameter.isAnnotationPresent(ObservesAsync.class)) {
                        if (event == null || parameter.getType().getName().equals(event)) {
                            return method;
                        }
                        if (byName == null) {
                            byName = method;
                        }
                    }
                }
            }
        }
        return byName;
    }

    private static String rawName(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType classType -> classType.name().value();
            case TypeInfo.ParameterizedType parameterized -> parameterized.rawType().value();
            case null, default -> null;
        };
    }

    /** An interceptor's own {@code @PostConstruct}, as {@code BeanLifecycle.callPostConstruct} takes it. */
    private static Method ownPostConstruct(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostConstruct.class) && method.getParameterCount() == 0) {
                return method;
            }
        }
        return null;
    }

    /** The lifecycle callbacks an interceptor intercepts with: one {@link InvocationContext} parameter. */
    private static List<Method> lifecycleInterceptorMethods(Class<?> type, Class<? extends Annotation> annotation) {
        return BeanLifecycle.collectLifecycleMethodsInHierarchy(type, annotation).stream()
                .filter(method -> method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == InvocationContext.class)
                .toList();
    }

    List<Operation> operations(Bean<?> bean) {
        if (!(bean instanceof ManagedBean<?> managed) || managed.descriptor().kind() == null) {
            return List.of();
        }
        return switch (managed.descriptor().kind()) {
            case MANAGED -> managedOperations(managed);
            case PRODUCER_METHOD -> producerMethodOperations(managed);
            case PRODUCER_FIELD -> producerFieldOperations(managed);
            case SYNTHETIC -> List.of();
        };
    }

    private List<Operation> managedOperations(ManagedBean<?> bean) {
        Class<?> beanClass = bean.getBeanClass();
        var ops = new ArrayList<Operation>();
        Boolean preGenerated = container.interceptedSubclassPreGenerated(bean.descriptor().id());
        if (preGenerated == null) {
            ops.add(new Operation(Kind.INSTANTIATE, beanClass.getName(), "constructor", beanClass));
        } else {
            String subclass = beanClass.getName() + InterceptedShape.SUBCLASS_SUFFIX;
            ops.add(new Operation(Kind.INSTANTIATE, subclass, "constructor", beanClass));
            ops.add(new Operation(preGenerated ? Kind.PRE_GENERATED : Kind.NONE, subclass, "intercepted subclass",
                    beanClass));
        }
        for (Member member : BeanInjector.injectionOrder(beanClass)) {
            ops.add(member instanceof Field field ? field(field) : invoke((Method) member, "initializer"));
        }
        for (Method method : BeanLifecycle.collectLifecycleMethodsInHierarchy(beanClass, PostConstruct.class)) {
            ops.add(invoke(method, "@PostConstruct"));
        }
        for (Method method : BeanLifecycle.collectLifecycleMethodsInHierarchy(beanClass, PreDestroy.class)) {
            ops.add(invoke(method, "@PreDestroy"));
        }
        clientProxy(bean, ops);
        return ops;
    }

    private List<Operation> producerMethodOperations(ManagedBean<?> bean) {
        Class<?> declaring = bean.getBeanClass();
        String name = VaubanContainer.extractProducerMethodName(bean.descriptor().id());
        var ops = new ArrayList<Operation>();
        Method producer = producerMethod(declaring, name);
        ops.add(producer == null ? none("producer " + name + "()", declaring) : invoke(producer, "producer"));
        disposer(bean, declaring, ops);
        clientProxy(bean, ops);
        return ops;
    }

    private List<Operation> producerFieldOperations(ManagedBean<?> bean) {
        Class<?> declaring = bean.getBeanClass();
        var ops = new ArrayList<Operation>();
        ops.add(none("producer field " + VaubanContainer.extractProducerFieldName(bean.descriptor().id()), declaring));
        disposer(bean, declaring, ops);
        clientProxy(bean, ops);
        return ops;
    }

    private void disposer(ManagedBean<?> bean, Class<?> declaring, List<Operation> ops) {
        DisposerDescriptor disposer = container.disposerInvoker.disposerOf(bean.descriptor().id());
        if (disposer == null) {
            return;
        }
        Method method = disposerMethod(declaring, disposer);
        ops.add(method == null ? none("disposer " + disposer.methodName() + "()", declaring)
                : invoke(method, "disposer"));
    }

    /** The first declared method of that name, as {@code VaubanContainer.createProducerMethodFactory} takes it. */
    private static Method producerMethod(Class<?> declaring, String name) {
        for (Method method : declaring.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    /** The disposer's method, as {@code DisposerInvoker.callDisposer} takes it. */
    private static Method disposerMethod(Class<?> declaring, DisposerDescriptor disposer) {
        for (Method method : declaring.getDeclaredMethods()) {
            if (method.getName().equals(disposer.methodName())
                    && method.getParameterCount() > disposer.parameterIndex()) {
                return method;
            }
        }
        return null;
    }

    /** A normal-scoped bean's client proxy, keyed as {@code InterceptorBeanWrapper.getOrCreateProxy} asks for it. */
    private static void clientProxy(ManagedBean<?> bean, List<Operation> ops) {
        var scope = bean.descriptor().scope();
        if (scope != null && scope.isNormal()) {
            Class<?> target = InterceptorBeanWrapper.resolveProxyTargetClass(bean);
            ops.add(new Operation(Kind.CLIENT_PROXY, RuntimeClientProxyGenerator.proxyClassName(target),
                    "client proxy", bean.getBeanClass()));
        }
    }

    private static Operation field(Field field) {
        Class<?> declaring = field.getDeclaringClass();
        return new Operation(Kind.INJECT_FIELD, declaring.getName() + "#" + field.getName(),
                "field " + field.getName(), declaring);
    }

    private static Operation invoke(Method method, String what) {
        Class<?> declaring = method.getDeclaringClass();
        return new Operation(Kind.INVOKE, declaring.getName() + "#" + VaubanLookup.methodId(method),
                what + " " + method.getName() + "()", declaring);
    }

    private static Operation none(String label, Class<?> owner) {
        return new Operation(Kind.NONE, "", label, owner);
    }

    private Coverage verdict(List<Operation> ops) {
        if (ops.isEmpty()) {
            return new Coverage(Verdict.NOT_APPLICABLE, List.of());
        }
        var generators = EnumSet.noneOf(GeneratedCoverage.Generator.class);
        var uncovered = new ArrayList<String>();
        var unknown = new ArrayList<String>();
        boolean anyCovered = false;
        for (Operation op : ops) {
            switch (op.kind()) {
                case PRE_GENERATED -> anyCovered = true;
                case NONE -> uncovered.add(op.label());
                default -> {
                    GeneratedCoverage.Generator generator = generatorOf(op);
                    if (generator != null) {
                        generators.add(generator);
                        anyCovered = true;
                    } else if (servedByUndeclaredProvider(op.owner())) {
                        unknown.add(op.label());
                    } else {
                        uncovered.add(op.label());
                    }
                }
            }
        }
        if (!unknown.isEmpty()) {
            return new Coverage(Verdict.UNKNOWN, unknown);
        }
        if (uncovered.isEmpty() && !generators.isEmpty()) {
            return new Coverage(generators.size() == 2 ? Verdict.APT_AND_CLASS_FILE
                    : generators.contains(GeneratedCoverage.Generator.APT) ? Verdict.APT : Verdict.CLASS_FILE,
                    List.of());
        }
        return new Coverage(anyCovered ? Verdict.PARTIAL : Verdict.REFLECTION, uncovered);
    }

    /** The generator of the first provider that declares {@code op}: the container asks providers in this order. */
    private GeneratedCoverage.Generator generatorOf(Operation op) {
        for (Declared candidate : declared) {
            GeneratedCoverage coverage = candidate.coverage();
            if (coverage != null && keys(coverage, op.kind()).contains(op.key())) {
                return coverage.generator();
            }
        }
        return null;
    }

    private static Set<String> keys(GeneratedCoverage coverage, Kind kind) {
        return switch (kind) {
            case INSTANTIATE -> coverage.instantiated();
            case INJECT_FIELD -> coverage.injectedFields();
            case INVOKE -> coverage.invokedMethods();
            case CLIENT_PROXY -> coverage.clientProxies();
            case PRE_GENERATED, NONE -> Set.of();
        };
    }

    /** Whether a provider predating {@code coverage()} lives in {@code owner}'s package: providers are per package. */
    private boolean servedByUndeclaredProvider(Class<?> owner) {
        String pkg = owner.getPackageName();
        return declared.stream().anyMatch(candidate -> candidate.coverage() == null
                && candidate.provider().getClass().getPackageName().equals(pkg));
    }
}
