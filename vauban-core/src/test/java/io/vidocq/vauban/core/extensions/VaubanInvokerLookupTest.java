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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invoker that looks its target instance and arguments up: a dependent looked-up instance is destroyed after
 * the call, a normal-scoped one is not; and the validation of argument lookups against the deployed beans.
 */
class VaubanInvokerLookupTest {

    private static final AtomicInteger DESTROYED = new AtomicInteger();

    @Dependent
    public static class Part {
        public String name() {
            return "part";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    @ApplicationScoped
    public static class Assembly {
        public String assemble(Part part) {
            return "assembled " + part.name();
        }
    }

    @Dependent
    public static class Worker {
        public String work(Part part) {
            return "worked with " + part.name();
        }
    }

    public interface Holder<H> {
    }

    @Dependent
    public static class TextHolder implements Holder<String> {
    }

    @Dependent
    public static class HolderReader {
        public String read(Holder<String> holder) {
            return holder.getClass().getSimpleName();
        }
    }

    /**
     * An argument lookup of a {@code Holder<String>} parameter resolves {@code Holder<String>}, which the bean of
     * {@code TextHolder implements Holder<String>} satisfies. The lookup resolves the erased class, {@code Holder}, a
     * raw required type that a parameterized bean type of another argument than {@code Object} does not satisfy (CDI
     * 4.1, 5.2.4; BUG-20261117-27).
     */
    @Test
    @Disabled("BUG-20261117-27: an argument lookup resolves the erased class of its parameter")
    @DisplayName("an argument lookup of a parameterized type resolves that type")
    void anArgumentLookupOfAParameterizedTypeResolvesThatType() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(TextHolder.class).addBeanClass(HolderReader.class)
                .build()) {
            var read = new VaubanInvoker(HolderReader.class.getMethod("read", Holder.class), HolderReader.class, true,
                    Set.of(0));

            assertEquals("TextHolder", read.invoke(null, new Object[] {null}));
        }
    }

    @Test
    void lookedUpDependentsAreDestroyedAfterTheCallAndNormalScopedInstancesKept() throws Exception {
        try (var _ = VaubanContainer.builder()
                .addBeanClass(Part.class)
                .addBeanClass(Assembly.class)
                .addBeanClass(Worker.class)
                .build()) {
            var assemble = new VaubanInvoker(Assembly.class.getMethod("assemble", Part.class), Assembly.class, true,
                    Set.of(0));
            var work = new VaubanInvoker(Worker.class.getMethod("work", Part.class), Worker.class, true, Set.of(0));
            int before = DESTROYED.get();

            assertEquals("assembled part", assemble.invoke(null, new Object[] {null}));
            assertEquals(before + 1, DESTROYED.get());
            assertEquals("worked with part", work.invoke(null, new Object[] {null}));
            assertEquals(before + 2, DESTROYED.get());
        }
    }

    @Test
    void anArgumentLookupMatchingOnlyUnloadableBeansIsUnsatisfied() throws NoSuchMethodException {
        var factory = new VaubanInvokerFactory(getClass().getClassLoader());
        var builder = new VaubanInvokerBuilder(Assembly.class.getMethod("assemble", Part.class), Assembly.class);
        builder.withArgumentLookup(0);
        factory.getBuilders().add(builder);
        var errors = new ArrayList<String>();

        ExtensionMethodValidator.validateInvokerLookups(factory,
                List.of(BeanDescriptor.minimal(DotName.of("com.acme.Missing"))), getClass().getClassLoader(), errors);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().startsWith("Invoker argument lookup unsatisfied: no bean found for parameter type "
                + Part.class.getName()), errors.getFirst());
    }
}
