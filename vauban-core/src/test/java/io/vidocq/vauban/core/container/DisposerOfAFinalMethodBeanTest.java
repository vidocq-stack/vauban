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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A disposer runs on the contextual instance of its declaring bean, and a disposer that throws leaves the destruction
 * to complete (CDI 4.1 §6.1). A normal-scoped declaring bean whose class inherits a final method has no client proxy
 * typed by its class (BUG-20261118-19): the disposer asked for one, and never ran.
 */
@DisplayName("A disposer of a bean that inherits a final method")
class DisposerOfAFinalMethodBeanTest {

    /** Its final method makes a subclass unproxyable as this class or below (CDI 4.1 §3.10). */
    public static class SealedBase {
        public final String tag() {
            return "sealed";
        }
    }

    /** What {@link Factory} produces and disposes of. */
    public static class Made {
    }

    /** Disposed of by the contextual instance of {@link Factory}. */
    static final List<Made> DISPOSED = new CopyOnWriteArrayList<>();

    /** Declares a producer and its disposer, and inherits a final method: no client proxy stands for its class. */
    @ApplicationScoped
    public static class Factory extends SealedBase {
        @Produces
        @Dependent
        Made make() {
            return new Made();
        }

        void dispose(@Disposes Made made) {
            DISPOSED.add(made);
        }
    }

    /** What {@link FailingFactory} produces. */
    public static class Spoiled {
    }

    /** Its disposer throws: the destruction catches it (CDI 4.1 §6.1). */
    @ApplicationScoped
    public static class FailingFactory extends SealedBase {
        @Produces
        @Dependent
        Spoiled make() {
            return new Spoiled();
        }

        void dispose(@Disposes Spoiled spoiled) {
            throw new IllegalStateException("spoiled");
        }
    }

    @Test
    @DisplayName("runs on the contextual instance of its declaring bean")
    void disposerOfABeanWithAFinalMethod() {
        // Review round 6: the disposer asked for a reference typed by the declaring class, which no client proxy
        // stands for, and the exception was swallowed.
        DISPOSED.clear();
        try (var container = VaubanContainer.builder().addBeanClass(Factory.class).build()) {
            var instance = container.getBeanManager().createInstance().select(Made.class);
            var made = instance.get();
            instance.destroy(made);
            assertEquals(DISPOSED, List.of(made));
        }
    }

    @Test
    @DisplayName("that throws leaves the destruction to complete")
    void disposerThatThrows() {
        try (var container = VaubanContainer.builder().addBeanClass(FailingFactory.class).build()) {
            var instance = container.getBeanManager().createInstance().select(Spoiled.class);
            var spoiled = instance.get();
            assertDoesNotThrow(() -> instance.destroy(spoiled));
        }
    }
}
