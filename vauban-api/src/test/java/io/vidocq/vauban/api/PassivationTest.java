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
package io.vidocq.vauban.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.spi.BeanManager;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The binding a web container opens while it reads the session values another container wrote (Vidocq dev swap,
 * foy#114): the reads inside it resolve their beans in the bound container.
 */
class PassivationTest {

    /** Each test only compares identities: no method of the bean manager is ever called. */
    private static BeanManager beanManager() {
        return (BeanManager) java.lang.reflect.Proxy.newProxyInstance(
                PassivationTest.class.getClassLoader(), new Class<?>[] {BeanManager.class}, (_, method, _) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void noBeanManagerIsBoundOutsideAnImport() {
        assertEquals(Optional.empty(), Passivation.importingBeanManager());
    }

    @Test
    void theImporterSeesTheBeanManagerAndItsResultIsReturned() {
        var bound = beanManager();
        var seen = new AtomicReference<Optional<BeanManager>>();

        var result = Passivation.importInto(bound, () -> {
            seen.set(Passivation.importingBeanManager());
            return "read";
        });

        assertEquals("read", result);
        assertSame(bound, seen.get().orElseThrow());
    }

    @Test
    void theBindingEndsWithTheImport() {
        Passivation.importInto(beanManager(), () -> null);

        assertTrue(Passivation.importingBeanManager().isEmpty());
    }

    @Test
    void theBindingEndsEvenWhenTheImporterThrows() {
        var bound = beanManager();

        assertThrows(IllegalStateException.class, () -> Passivation.importInto(bound, () -> {
            throw new IllegalStateException("unreadable");
        }));

        assertTrue(Passivation.importingBeanManager().isEmpty());
    }

    @Test
    void aNestedImportWinsInsideAndTheOuterOneIsRestoredAfter() {
        var outer = beanManager();
        var inner = beanManager();
        var seenInside = new AtomicReference<BeanManager>();
        var seenAfter = new AtomicReference<BeanManager>();

        Passivation.importInto(outer, () -> {
            Passivation.importInto(inner, () -> {
                seenInside.set(Passivation.importingBeanManager().orElseThrow());
                return null;
            });
            seenAfter.set(Passivation.importingBeanManager().orElseThrow());
            return null;
        });

        assertSame(inner, seenInside.get());
        assertSame(outer, seenAfter.get());
    }

    @Test
    void theBindingHoldsOnTheReadingThreadOnly() {
        var elsewhere = new AtomicReference<Optional<BeanManager>>();

        Passivation.importInto(beanManager(), () -> {
            var thread = Thread.ofVirtual().start(() -> elsewhere.set(Passivation.importingBeanManager()));
            try {
                thread.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertEquals(Optional.empty(), elsewhere.get());
    }

    @Test
    void aNullBeanManagerIsRejected() {
        var thrown = assertThrows(NullPointerException.class, () -> Passivation.importInto(null, () -> "read"));

        assertEquals("beanManager", thrown.getMessage());
    }

    @Test
    void aNullImporterIsRejected() {
        var bound = beanManager();

        var thrown = assertThrows(NullPointerException.class, () -> Passivation.importInto(bound, null));

        assertEquals("importer", thrown.getMessage());
    }
}
