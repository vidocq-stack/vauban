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

import java.util.concurrent.TimeUnit;

/**
 * The paths that run the code the Vauban annotation processor generates (vauban#227):
 * <ul>
 *   <li>{@code plainCall}: a {@code @Dependent} bean with nothing generated around it, the floor;</li>
 *   <li>{@code clientProxyCall}: an {@code @ApplicationScoped} bean, through its {@code _ClientProxy};</li>
 *   <li>{@code interceptedCall}: a {@code @Dependent} bean, through its {@code $$Intercepted} subclass;</li>
 *   <li>{@code proxiedInterceptedCall}: both, the client proxy forwarding to the intercepted subclass;</li>
 *   <li>{@code interceptedCreation}: a new intercepted {@code @Dependent} bean, created and destroyed;</li>
 *   <li>{@code bytecodeInterceptedCreation}: the same, for a subclass the processor emits as bytecode, which the
 *       provider instantiates through its lookup (BUG-20261004-09).</li>
 * </ul>
 *
 * <p>{@link #boot()} checks each path before the first iteration and fails the trial otherwise.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class GeneratedCodeBenchmark {

    private VaubanContainer container;
    private Plain plain;
    private Counter counter;
    private AuditedTally tally;
    private Ledger ledger;
    private Instance<AuditedTally> tallies;
    private Instance<AuditedHiddenTally> hiddenTallies;

    @Setup(Level.Trial)
    public void boot() {
        container = VaubanContainer.builder()
                .addBeanClass(Plain.class)
                .addBeanClass(Counter.class)
                .addBeanClass(AuditedTally.class)
                .addBeanClass(AuditedHiddenTally.class)
                .addBeanClass(Ledger.class)
                .addBeanClass(AuditInterceptor.class)
                .build();
        plain = CDI.current().select(Plain.class).get();
        counter = CDI.current().select(Counter.class).get();
        tally = CDI.current().select(AuditedTally.class).get();
        ledger = CDI.current().select(Ledger.class).get();
        tallies = CDI.current().select(AuditedTally.class);
        hiddenTallies = CDI.current().select(AuditedHiddenTally.class);
        verify();
    }

    /** Fails the trial rather than timing a path that does not run the generated code it should. */
    private void verify() {
        expect(false, plain.getClass().getName().contains("$$"), "plain bean is not subclassed");
        expect(true, counter.getClass().getName().endsWith("_ClientProxy"), "counter is a client proxy");
        expect(true, tally.getClass().getName().endsWith("$$Intercepted"), "tally is intercepted");
        expect(true, ledger.getClass().getName().endsWith("_ClientProxy"), "ledger is a client proxy");
        var hiddenTally = hiddenTallies.get();
        expect(true, hiddenTally.getClass().getName().endsWith("$$Intercepted"), "hidden tally is intercepted");
        hiddenTallies.destroy(hiddenTally);
        expect(42L, plainCall(), "plainCall");
        expect(42L, clientProxyCall(), "clientProxyCall");

        long before = AuditInterceptor.CALLS.sum();
        expect(42L, interceptedCall(), "interceptedCall");
        expect(42L, proxiedInterceptedCall(), "proxiedInterceptedCall");
        expect(42L, interceptedCreation(), "interceptedCreation");
        expect(42L, bytecodeInterceptedCreation(), "bytecodeInterceptedCreation");
        expect(4L, AuditInterceptor.CALLS.sum() - before, "interceptions");
    }

    private static void expect(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("benchmark path broken (" + what + "): expected " + expected
                    + " but got " + actual);
        }
    }

    @TearDown(Level.Trial)
    public void close() {
        container.close();
    }

    @Benchmark
    public long plainCall() {
        return plain.next(41L);
    }

    @Benchmark
    public long clientProxyCall() {
        return counter.next(41L);
    }

    @Benchmark
    public long interceptedCall() {
        return tally.next(41L);
    }

    @Benchmark
    public long proxiedInterceptedCall() {
        return ledger.record(41L);
    }

    @Benchmark
    public long interceptedCreation() {
        var created = tallies.get();
        try {
            return created.next(41L);
        } finally {
            tallies.destroy(created);
        }
    }

    @Benchmark
    public long bytecodeInterceptedCreation() {
        var created = hiddenTallies.get();
        try {
            return created.next(41L);
        } finally {
            hiddenTallies.destroy(created);
        }
    }
}
