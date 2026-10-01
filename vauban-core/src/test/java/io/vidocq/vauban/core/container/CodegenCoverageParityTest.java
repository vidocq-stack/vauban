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

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.container.coverage.CoverageFixtures;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CodegenCoverage - the keys the container asks providers for")
class CodegenCoverageParityTest {

    /** Records every key the container asks for and runs nothing, so the container falls back as without it. */
    static final class Recording implements VaubanComponentProvider {
        final Set<String> instantiated = ConcurrentHashMap.newKeySet();
        final Set<String> injectedFields = ConcurrentHashMap.newKeySet();
        final Set<String> invokedMethods = ConcurrentHashMap.newKeySet();
        final Set<String> clientProxies = ConcurrentHashMap.newKeySet();

        @Override
        public Object create(String className) {
            instantiated.add(className);
            return null;
        }

        @Override
        public Object create(String className, Object[] args) {
            instantiated.add(className);
            return null;
        }

        @Override
        public boolean injectField(Object bean, String className, String fieldName, Object value) {
            injectedFields.add(className + "#" + fieldName);
            return false;
        }

        @Override
        public Object invoke(Object target, String className, String methodId, Object[] args) {
            invokedMethods.add(className + "#" + methodId);
            return NOT_INVOKED;
        }

        @Override
        public Object createClientProxy(String proxyClassName, Supplier<?> delegate) {
            clientProxies.add(proxyClassName);
            return null;
        }

        Set<String> asked(CodegenCoverage.Kind kind) {
            return switch (kind) {
                case INSTANTIATE -> instantiated;
                case INJECT_FIELD -> injectedFields;
                case INVOKE -> invokedMethods;
                case CLIENT_PROXY -> clientProxies;
                case PRE_GENERATED, NONE -> Set.of();
            };
        }
    }

    @Test
    @DisplayName("the keys the coverage computes are exactly the keys the container asks providers for")
    void computedKeysAreAskedKeys() {
        var recording = new Recording();
        var operations = new ArrayList<CodegenCoverage.Operation>();
        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addComponentProvider(recording)
                .addBeanClass(CoverageFixtures.Dependency.class)
                .addBeanClass(CoverageFixtures.CoveredBean.class)
                .addBeanClass(CoverageFixtures.ScopedBean.class)
                .addBeanClass(CoverageFixtures.Factory.class)
                .addBeanClass(CoverageFixtures.Pinger.class)
                .addBeanClass(CoverageFixtures.AuditInterceptor.class)
                .addBeanClass(CoverageFixtures.AuditedBean.class)
                .build()) {
            BeanManager manager = container.getBeanManager();
            var covered = manager.createInstance().select(CoverageFixtures.CoveredBean.class);
            covered.destroy(covered.get());
            Bean<?> scoped = manager.resolve(manager.getBeans(CoverageFixtures.ScopedBean.class));
            ((CoverageFixtures.ScopedBean) manager.getReference(scoped, CoverageFixtures.ScopedBean.class,
                    manager.createCreationalContext(scoped))).hello();
            var widgets = manager.createInstance().select(CoverageFixtures.Widget.class);
            widgets.destroy(widgets.get());
            container.select(CoverageFixtures.AuditedBean.class).work();
            manager.getEvent().select(CoverageFixtures.Ping.class).fire(new CoverageFixtures.Ping("x"));

            var coverage = container.codegenCoverage();
            manager.getBeans(Object.class, jakarta.enterprise.inject.Any.Literal.INSTANCE).stream()
                    .filter(bean -> bean.getBeanClass().getName().startsWith(CoverageFixtures.PREFIX))
                    .forEach(bean -> operations.addAll(coverage.operations(bean)));
            container.eventDispatcher().observers().stream()
                    .filter(o -> o.declaringClass().value().startsWith(CoverageFixtures.PREFIX))
                    .forEach(o -> operations.addAll(coverage.operations(o)));
            container.interceptorManager().getInterceptors().stream()
                    .filter(i -> i.interceptorClass().value().startsWith(CoverageFixtures.PREFIX))
                    .forEach(i -> operations.addAll(coverage.operations(i)));
        }

        assertFalse(operations.isEmpty(), "the fixtures need operations");
        for (var kind : List.of(CodegenCoverage.Kind.INSTANTIATE, CodegenCoverage.Kind.INJECT_FIELD,
                CodegenCoverage.Kind.INVOKE, CodegenCoverage.Kind.CLIENT_PROXY)) {
            Set<String> computed = new java.util.TreeSet<>();
            operations.stream().filter(op -> op.kind() == kind).forEach(op -> computed.add(op.key()));
            Set<String> asked = new java.util.TreeSet<>();
            recording.asked(kind).stream().filter(key -> key.startsWith(CoverageFixtures.PREFIX)).forEach(asked::add);
            assertTrue(asked.containsAll(computed), kind + ": computed but never asked " + minus(computed, asked));
            assertTrue(computed.containsAll(asked), kind + ": asked but not computed " + minus(asked, computed));
        }
    }

    private static Set<String> minus(Set<String> left, Set<String> right) {
        Set<String> rest = new java.util.TreeSet<>(left);
        rest.removeAll(right);
        return rest;
    }
}
