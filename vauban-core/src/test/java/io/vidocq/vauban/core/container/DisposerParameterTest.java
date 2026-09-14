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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI 4.1 §10.4.3: every parameter of a disposer method other than the {@code @Disposes} one is an
 * injection point, qualifiers included. The container resolved them by type alone — a qualified
 * parameter silently received the {@code @Default} bean — and read what qualifiers it did look at
 * off the reflective {@code Parameter}, because {@code DisposerDescriptor} models only the disposed
 * one (vauban#89).
 */
@DisplayName("vauban#89: a disposer's other parameters are injection points, qualifiers included")
class DisposerParameterTest {

    static final List<String> DISPOSED = new CopyOnWriteArrayList<>();

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Channel {
        String value();
    }

    public interface Payment {
        String id();
    }

    @Channel("card")
    @Dependent
    public static class CardPayment implements Payment {
        @Override public String id() { return "card"; }
    }

    @Channel("wire")
    @Dependent
    public static class WirePayment implements Payment {
        @Override public String id() { return "wire"; }
    }

    public record Ledger(String name) {
    }

    @Dependent
    public static class Books {

        @Produces
        @Dependent
        public Ledger ledger() {
            return new Ledger("ledger");
        }

        /** The second parameter is qualified: the wire bean must arrive, not the card one. */
        public void close(@Disposes Ledger ledger, @Channel("wire") Payment payment) {
            DISPOSED.add(ledger.name() + ":" + payment.id());
        }
    }

    @Dependent
    public static class PlainBooks {

        @Produces
        @Dependent
        public Ledger ledger() {
            return new Ledger("plain");
        }

        /** No qualifier on the second parameter: it resolves on @Default, as it always did. */
        public void close(@Disposes Ledger ledger, Registry registry) {
            DISPOSED.add(ledger.name() + ":" + registry.name());
        }
    }

    @Dependent
    public static class Registry {
        public String name() { return "registry"; }
    }

    @Test
    @DisplayName("an unqualified disposer parameter still resolves, and shows the same call count")
    void anUnqualifiedParameterIsUnaffected() {
        DISPOSED.clear();

        try (var container = VaubanContainer.builder()
                .addBeanClass(Registry.class)
                .addBeanClass(PlainBooks.class)
                .build()) {

            var beanManager = container.getBeanManager();
            @SuppressWarnings("unchecked")
            var bean = (jakarta.enterprise.inject.spi.Bean<Ledger>)
                    beanManager.resolve(beanManager.getBeans(Ledger.class));
            var context = beanManager.createCreationalContext(bean);
            var ledger = (Ledger) beanManager.getReference(bean, Ledger.class, context);

            bean.destroy(ledger, context);

            assertFalse(DISPOSED.isEmpty(), "the disposer must run");
            assertTrue(DISPOSED.stream().allMatch("plain:registry"::equals), DISPOSED.toString());
            // The same count as the qualified case: BUG-20260914-18 does not depend on qualifiers.
            assertEquals(2, DISPOSED.size(),
                    "pins BUG-20260914-18 as it stands, so a fix for it fails here and gets noticed");
        }
    }

    @Test
    @DisplayName("a qualified disposer parameter receives the bean its qualifier names")
    void aQualifiedDisposerParameterIsResolvedOnItsQualifier() {
        DISPOSED.clear();

        try (var container = VaubanContainer.builder()
                .addBeanClass(CardPayment.class)
                .addBeanClass(WirePayment.class)
                .addBeanClass(Books.class)
                .build()) {

            // Destroying the produced instance is what runs its disposer, so do exactly that rather
            // than rely on when a context happens to be released.
            var beanManager = container.getBeanManager();
            @SuppressWarnings("unchecked")
            var bean = (jakarta.enterprise.inject.spi.Bean<Ledger>)
                    beanManager.resolve(beanManager.getBeans(Ledger.class));
            var context = beanManager.createCreationalContext(bean);
            var ledger = (Ledger) beanManager.getReference(bean, Ledger.class, context);

            bean.destroy(ledger, context);

            // What this test is about is WHICH bean the parameter received, not how many times the
            // disposer ran: `destroy` runs it twice, which is BUG-20260914-18 and older than this.
            assertFalse(DISPOSED.isEmpty(), "the disposer must run");
            assertTrue(DISPOSED.stream().allMatch("ledger:wire"::equals),
                    "every call must have received the wire payment, never the card one: " + DISPOSED);
        }
    }
}
