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
package io.vidocq.vauban.core.container.coverage;

import io.vidocq.vauban.api.GeneratedCoverage;
import io.vidocq.vauban.api.GeneratedCoverage.Generator;
import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.container.CodegenCoverage.Coverage;
import io.vidocq.vauban.core.container.CodegenCoverage.Verdict;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.coverage.legacy.LegacyBean;
import io.vidocq.vauban.core.container.coverage.legacy.LegacyProvider;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.vidocq.vauban.core.container.coverage.CoverageFixtures.PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("CodegenCoverage")
class CodegenCoverageTest {

    static final String COVERED = PREFIX + "CoveredBean";
    static final Set<String> COVERED_METHODS = Set.of(
            COVERED + "#setUp(" + PREFIX + "Dependency)", COVERED + "#init()", COVERED + "#close()");

    /** A provider that declares what it is given and runs nothing, so the container falls back as it would. */
    record Declaring(GeneratedCoverage coverage) implements VaubanComponentProvider {
        @Override
        public Object create(String className) {
            return null;
        }
    }

    static VaubanComponentProvider declaring(Generator generator, Set<String> instantiated, Set<String> fields,
                                             Set<String> methods, Set<String> proxies) {
        return new Declaring(new GeneratedCoverage(generator, instantiated, fields, methods, proxies));
    }

    static VaubanContainer container(List<VaubanComponentProvider> providers, Class<?>... beans) {
        var builder = VaubanContainer.builder().classLoader(CodegenCoverageTest.class.getClassLoader());
        providers.forEach(builder::addComponentProvider);
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        return builder.build();
    }

    static Bean<?> bean(VaubanContainer container, Class<?> type) {
        BeanManager manager = container.getBeanManager();
        return manager.resolve(manager.getBeans(type));
    }

    static Coverage coverage(VaubanContainer container, Class<?> type) {
        return container.codegenCoverage().of(bean(container, type));
    }

    @Test
    @DisplayName("a bean whose every operation an APT provider declares is APT")
    void everyOperationDeclaredIsApt() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(COVERED),
                        Set.of(COVERED + "#dependency"), COVERED_METHODS, Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("a bean a Class-File provider covers is CLASS_FILE")
    void classFileProviderIsNamed() {
        try (var container = container(List.of(declaring(Generator.CLASS_FILE, Set.of(COVERED),
                        Set.of(COVERED + "#dependency"), COVERED_METHODS, Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.CLASS_FILE, List.of()),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("a bean both generators cover is APT_AND_CLASS_FILE")
    void bothGeneratorsAreNamed() {
        try (var container = container(List.of(
                        declaring(Generator.APT, Set.of(COVERED), Set.of(COVERED + "#dependency"), Set.of(), Set.of()),
                        declaring(Generator.CLASS_FILE, Set.of(), Set.of(), COVERED_METHODS, Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.APT_AND_CLASS_FILE, List.of()),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("each operation no provider declares is named, in the order the container runs it")
    void uncoveredOperationsAreNamedInOrder() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(COVERED), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.CoveredBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("field dependency", "initializer setUp()",
                            "@PostConstruct init()", "@PreDestroy close()")),
                    coverage(container, CoverageFixtures.CoveredBean.class));
        }
    }

    @Test
    @DisplayName("a private field no provider can assign makes the bean partial")
    void privateFieldIsPartial() {
        String bean = PREFIX + "PrivateFieldBean";
        try (var container = container(List.of(declaring(Generator.APT, Set.of(bean), Set.of(), Set.of(), Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.PrivateFieldBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("field dependency")),
                    coverage(container, CoverageFixtures.PrivateFieldBean.class));
        }
    }

    @Test
    @DisplayName("a bean no provider declares is reflection")
    void undeclaredBeanIsReflection() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(COVERED), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("constructor")),
                    coverage(container, CoverageFixtures.BareBean.class));
        }
    }

    @Test
    @DisplayName("a deployment with no provider at all is reflection, row by row")
    void noProviderAtAllIsReflection() {
        try (var container = container(List.of(), CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("constructor")),
                    coverage(container, CoverageFixtures.BareBean.class));
        }
    }

    @Test
    @DisplayName("a normal-scoped bean needs its client proxy")
    void normalScopedBeanNeedsItsClientProxy() {
        String bean = PREFIX + "ScopedBean";
        try (var container = container(List.of(declaring(Generator.APT, Set.of(bean), Set.of(), Set.of(), Set.of())),
                CoverageFixtures.ScopedBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("client proxy")),
                    coverage(container, CoverageFixtures.ScopedBean.class));
        }
        try (var container = container(List.of(declaring(Generator.APT, Set.of(bean), Set.of(), Set.of(),
                        Set.of(bean + "_ClientProxy"))),
                CoverageFixtures.ScopedBean.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()),
                    coverage(container, CoverageFixtures.ScopedBean.class));
        }
    }

    @Test
    @DisplayName("an intercepted bean is created through its subclass, defined at boot when not pre-generated")
    void interceptedBeanNeedsItsSubclass() {
        String subclass = PREFIX + "AuditedBean$$Intercepted";
        try (var container = container(List.of(declaring(Generator.APT, Set.of(subclass), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.AuditInterceptor.class,
                CoverageFixtures.AuditedBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("intercepted subclass")),
                    coverage(container, CoverageFixtures.AuditedBean.class));
        }
    }

    @Test
    @DisplayName("a subclass an earlier boot defined in the same loader is still not pre-generated")
    void subclassDefinedByAnEarlierBootIsNotPreGenerated() {
        String subclass = PREFIX + "AuditedBean$$Intercepted";
        for (int boot = 1; boot <= 2; boot++) {
            try (var container = container(List.of(declaring(Generator.APT, Set.of(subclass), Set.of(), Set.of(),
                            Set.of())),
                    CoverageFixtures.Dependency.class, CoverageFixtures.AuditInterceptor.class,
                    CoverageFixtures.AuditedBean.class)) {
                assertEquals(new Coverage(Verdict.PARTIAL, List.of("intercepted subclass")),
                        coverage(container, CoverageFixtures.AuditedBean.class), "boot " + boot);
            }
        }
    }

    @Test
    @DisplayName("a subclass built ahead of time is pre-generated: the bean needs no reflection for it")
    void preGeneratedSubclassIsCovered() throws Exception {
        Class<?> bean = CoverageFixtures.PreGeneratedBean.class;
        String subclass = PREFIX + "PreGeneratedBean$$Intercepted";
        try {
            Class.forName(subclass, false, bean.getClassLoader());
        } catch (ClassNotFoundException absent) {
            // Defined by the test, not by Vauban: what a subclass shipped by the build looks like at boot.
            var generated = io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator.generate(bean);
            java.lang.invoke.MethodHandles.privateLookupIn(bean, java.lang.invoke.MethodHandles.lookup())
                    .defineClass(generated.bytecode());
        }
        try (var container = container(List.of(declaring(Generator.APT, Set.of(subclass), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.AuditInterceptor.class,
                CoverageFixtures.PreGeneratedBean.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()),
                    coverage(container, CoverageFixtures.PreGeneratedBean.class));
        }
    }

    @Test
    @DisplayName("a built-in bean is not evaluated")
    void builtInBeanIsNotApplicable() {
        try (var container = container(List.of(), CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.NOT_APPLICABLE, List.of()), coverage(container, BeanManager.class));
        }
    }

    @Test
    @DisplayName("what a provider predating coverage() may cover is unknown, not reflection")
    void oldProviderMakesItsPackageUnknown() {
        try (var container = container(List.of(new LegacyProvider()), LegacyBean.class)) {
            assertEquals(new Coverage(Verdict.UNKNOWN, List.of("constructor", "field beanManager")),
                    coverage(container, LegacyBean.class));
        }
    }

    static final String FACTORY = PREFIX + "Factory";

    @Test
    @DisplayName("a producer method and its disposer are covered by the methods a provider invokes")
    void producerMethodAndDisposer() {
        Set<String> methods = Set.of(FACTORY + "#widget()", FACTORY + "#dispose(" + PREFIX + "Widget)");
        try (var container = container(List.of(declaring(Generator.APT, Set.of(), Set.of(), methods, Set.of())),
                CoverageFixtures.Factory.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()), coverage(container, CoverageFixtures.Widget.class));
        }
        try (var container = container(List.of(), CoverageFixtures.Factory.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("producer widget()", "disposer dispose()")),
                    coverage(container, CoverageFixtures.Widget.class));
        }
    }

    @Test
    @DisplayName("a producer field is always read by reflection: no provider method reads a field")
    void producerFieldIsReflection() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(FACTORY), Set.of(), Set.of(),
                        Set.of())),
                CoverageFixtures.Factory.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("producer field gadget")),
                    coverage(container, CoverageFixtures.Gadget.class));
        }
    }

    static final String PINGER = PREFIX + "Pinger";
    static final String AUDIT = PREFIX + "AuditInterceptor";

    static ObserverDescriptor observer(VaubanContainer container, String declaringClass) {
        return container.eventDispatcher().observers().stream()
                .filter(observer -> observer.declaringClass().value().equals(declaringClass))
                .findFirst().orElseThrow();
    }

    static InterceptorDescriptor interceptor(VaubanContainer container, String type) {
        return container.interceptorManager().getInterceptors().stream()
                .filter(interceptor -> interceptor.interceptorClass().value().equals(type))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an observer is covered by the method a provider invokes")
    void observerMethod() {
        Set<String> methods = Set.of(PINGER + "#onPing(" + PREFIX + "Ping)");
        try (var container = container(List.of(declaring(Generator.APT, Set.of(), Set.of(), methods, Set.of())),
                CoverageFixtures.Pinger.class)) {
            assertEquals(new Coverage(Verdict.APT, List.of()),
                    container.codegenCoverage().of(observer(container, PINGER)));
        }
        try (var container = container(List.of(), CoverageFixtures.Pinger.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("observer onPing()")),
                    container.codegenCoverage().of(observer(container, PINGER)));
        }
    }

    @Test
    @DisplayName("a synthetic observer is not evaluated")
    void syntheticObserverIsNotApplicable() {
        var synthetic = new ObserverDescriptor(DotName.of(PINGER), "synthetic",
                new TypeInfo.ClassType(DotName.of(PREFIX + "Ping")), List.of(), false, 0, null, null,
                (instance, qualifiers) -> {});
        try (var container = container(List.of(), CoverageFixtures.Pinger.class)) {
            assertEquals(new Coverage(Verdict.NOT_APPLICABLE, List.of()), container.codegenCoverage().of(synthetic));
        }
    }

    @Test
    @DisplayName("an interceptor's @AroundInvoke is always called by reflection, so the interceptor is partial")
    void interceptorAroundInvokeIsReflection() {
        try (var container = container(List.of(declaring(Generator.APT, Set.of(AUDIT),
                        Set.of(AUDIT + "#dependency"), Set.of(), Set.of())),
                CoverageFixtures.Dependency.class, CoverageFixtures.AuditInterceptor.class,
                CoverageFixtures.AuditedBean.class)) {
            assertEquals(new Coverage(Verdict.PARTIAL, List.of("initializer setUp()", "@AroundInvoke around()")),
                    container.codegenCoverage().of(interceptor(container, AUDIT)));
        }
    }

    @Test
    @DisplayName("reading a class's coverage never runs its static initializer")
    void readingCoverageInitializesNoClass() {
        var probe = new InterceptorDescriptor(DotName.of(PREFIX + "StaticInitProbe"), Set.of(), "around", 1);
        try (var container = container(List.of(), CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.REFLECTION, List.of("constructor", "@AroundInvoke around()")),
                    container.codegenCoverage().of(probe));
        }
        assertEquals(Set.of(), Set.copyOf(CoverageFixtures.INITIALIZED));
    }

    @Test
    @DisplayName("an interceptor whose class cannot be loaded is not evaluated")
    void unloadableInterceptorIsNotApplicable() {
        var missing = new InterceptorDescriptor(DotName.of("does.not.Exist"), Set.of(), "around", 1);
        try (var container = container(List.of(), CoverageFixtures.BareBean.class)) {
            assertEquals(new Coverage(Verdict.NOT_APPLICABLE, List.of()), container.codegenCoverage().of(missing));
        }
    }

    @Test
    @DisplayName("in a package an old and a new provider share, only the uncovered operations are unknown")
    void mixedPackageIsUnknownOnlyWhereUncovered() {
        try (var container = container(List.of(new LegacyProvider(), declaring(Generator.APT,
                        Set.of(LegacyBean.class.getName()), Set.of(), Set.of(), Set.of())),
                LegacyBean.class)) {
            assertEquals(new Coverage(Verdict.UNKNOWN, List.of("field beanManager")),
                    coverage(container, LegacyBean.class));
        }
    }
}
