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
import io.vidocq.vauban.core.interceptor.InterceptedShape;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.spi.Bean;

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

    List<Operation> operations(Bean<?> bean) {
        if (!(bean instanceof ManagedBean<?> managed) || managed.descriptor().kind() == null) {
            return List.of();
        }
        return switch (managed.descriptor().kind()) {
            case MANAGED -> managedOperations(managed);
            case PRODUCER_METHOD, PRODUCER_FIELD, SYNTHETIC -> List.of();
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
