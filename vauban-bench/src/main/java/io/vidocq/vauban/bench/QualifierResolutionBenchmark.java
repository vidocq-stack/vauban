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
package io.vidocq.vauban.bench;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.annotation.Annotation;
import java.util.concurrent.TimeUnit;

/**
 * Qualifier matching on the paths vauban#70 reworks, in a module compiled with the Vauban annotation
 * processor:
 * <ul>
 *   <li>{@code programmaticLookup}: {@code Instance.select(qualifiers).get()} with String, enum and
 *       {@code @Nonbinding} members;</li>
 *   <li>{@code dependentCreation}: a new {@code @Dependent} bean with three qualified fields and two
 *       qualified constructor parameters;</li>
 *   <li>{@code qualifiedEvent}: an event fired with a member qualifier, delivered to two of three
 *       observers;</li>
 *   <li>{@code interceptedCall}: a business method bound to its interceptor by a member binding.</li>
 * </ul>
 *
 * <p>Only paths that resolve correctly today are measured, so that a later run compares like with like.
 * {@link #boot()} checks each of them before the first iteration and fails the trial otherwise.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class QualifierResolutionBenchmark {

    private static final OrderPlaced ORDER = new OrderPlaced("o-1");

    private final Annotation[] cardInUs = {
            new Literals.ChannelLiteral("card", "lookup"), new Literals.RegionLiteral(Zone.US)};
    private final Annotation cardChannel = new Literals.ChannelLiteral("card", "event");

    private VaubanContainer container;
    private Instance<Payment> payments;
    private Instance<Checkout> checkouts;
    private Event<OrderPlaced> orders;
    private Ledger ledger;

    @Setup(Level.Trial)
    public void boot() {
        container = VaubanContainer.builder()
                .addBeanClass(CardPayment.class)
                .addBeanClass(WirePayment.class)
                .addBeanClass(PremiumCardPayment.class)
                .addBeanClass(CashPayment.class)
                .addBeanClass(Checkout.class)
                .addBeanClass(Orders.class)
                .addBeanClass(OrderListeners.class)
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(Ledger.class)
                .build();
        payments = CDI.current().select(Payment.class);
        checkouts = CDI.current().select(Checkout.class);
        orders = CDI.current().select(Orders.class).get().event();
        ledger = CDI.current().select(Ledger.class).get();
        verify();
    }

    /** Fails the trial rather than timing a path that does not resolve what it should. */
    private void verify() {
        expect("premium-card", programmaticLookup());
        expect("card|wire|cash|premium-card|cash", dependentCreation());

        var listeners = CDI.current().select(OrderListeners.class).get();
        long card = listeners.card();
        long wire = listeners.wire();
        long every = listeners.every();
        qualifiedEvent();
        expect("card +1, wire +0, every +1", "card +" + (listeners.card() - card)
                + ", wire +" + (listeners.wire() - wire) + ", every +" + (listeners.every() - every));

        long intercepted = AuditInterceptor.CALLS.sum();
        expect("42", String.valueOf(interceptedCall()));
        expect("1 interception", (AuditInterceptor.CALLS.sum() - intercepted) + " interception");
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("benchmark path broken: expected " + expected + " but got " + actual);
        }
    }

    @TearDown(Level.Trial)
    public void close() {
        container.close();
    }

    @Benchmark
    public String programmaticLookup() {
        return payments.select(cardInUs).get().id();
    }

    @Benchmark
    public String dependentCreation() {
        var checkout = checkouts.get();
        try {
            return checkout.summary();
        } finally {
            checkouts.destroy(checkout);
        }
    }

    @Benchmark
    public void qualifiedEvent() {
        orders.select(cardChannel).fire(ORDER);
    }

    @Benchmark
    public long interceptedCall() {
        return ledger.record(41L);
    }
}
