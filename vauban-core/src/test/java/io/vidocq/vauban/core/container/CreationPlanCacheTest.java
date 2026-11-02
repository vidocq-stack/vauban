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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the container reads from a bean class to create an instance — the injection order, the lifecycle callbacks,
 * the type-variable mapping — is computed once per class and reused, not read again by reflection at each creation of
 * a bean (VAU-PERF-003).
 */
class CreationPlanCacheTest {

    static class Base<T> {
        @Inject String baseField;
        @PostConstruct void baseInit() { /* no behaviour needed */ }
    }

    static class Leaf extends Base<Integer> {
        @Inject Object leafField;
        @Inject void initializer(String value) { /* no behaviour needed */ }
        @PreDestroy void destroy() { /* no behaviour needed */ }
    }

    @Test
    void theInjectionOrderIsComputedOnce() {
        var first = BeanInjector.injectionOrder(Leaf.class);
        var second = BeanInjector.injectionOrder(Leaf.class);

        assertSame(first, second);
        assertEquals(3, first.size(), "base field, leaf field, initializer: " + first);
        assertThrows(UnsupportedOperationException.class, () -> first.add(null));
    }

    @Test
    void theLifecycleCallbacksAreComputedOncePerKind() {
        var postConstruct = BeanLifecycle.collectLifecycleMethodsInHierarchy(Leaf.class, PostConstruct.class);

        assertSame(postConstruct, BeanLifecycle.collectLifecycleMethodsInHierarchy(Leaf.class, PostConstruct.class));
        assertEquals(List.of("baseInit"), postConstruct.stream().map(java.lang.reflect.Method::getName).toList());
        assertEquals(List.of("destroy"), BeanLifecycle.collectLifecycleMethodsInHierarchy(Leaf.class, PreDestroy.class)
                .stream().map(java.lang.reflect.Method::getName).toList());
    }

    @Test
    void theTypeVariableMappingIsComputedOnce() {
        var mapping = ManagedBean.buildTypeVariableMapping(Leaf.class);

        assertSame(mapping, ManagedBean.buildTypeVariableMapping(Leaf.class));
        assertEquals(Integer.class, mapping.get(Base.class.getTypeParameters()[0]));
    }
}
