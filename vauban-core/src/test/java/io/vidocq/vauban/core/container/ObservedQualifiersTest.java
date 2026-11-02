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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The request context fires its lifecycle events through {@link ObservedQualifiers}, on every request: telling
 * whether an event is observed must not call the qualifier's {@code hashCode}/{@code equals}, which an
 * {@code AnnotationLiteral} computes by reflection (VAU-PERF-001).
 */
class ObservedQualifiersTest {

    /** A qualifier that counts the calls a hash-based lookup would make. */
    private static final class CountingQualifier implements Annotation {
        final AtomicInteger calls = new AtomicInteger();

        @Override public Class<? extends Annotation> annotationType() { return Initialized.class; }
        @Override public int hashCode() { calls.incrementAndGet(); return 1; }
        @Override public boolean equals(Object o) { calls.incrementAndGet(); return this == o; }
    }

    @Test
    void anObservedQualifierIsResolvedOnceAndNeverHashed() {
        var qualifier = new CountingQualifier();
        var resolutions = new AtomicInteger();
        var fired = new ArrayList<Annotation>();
        var events = new ObservedQualifiers(q -> { resolutions.incrementAndGet(); return true; }, fired::add);

        for (int i = 0; i < 1_000; i++) {
            events.accept(qualifier);
        }

        assertEquals(1, resolutions.get(), "observers resolved once per qualifier");
        assertEquals(1_000, fired.size());
        assertEquals(0, qualifier.calls.get(), "no hashCode/equals on the qualifier");
    }

    @Test
    void anUnobservedQualifierIsNeitherFiredNorHashed() {
        var qualifier = new CountingQualifier();
        var fired = new ArrayList<Annotation>();
        var events = new ObservedQualifiers(q -> false, fired::add);

        for (int i = 0; i < 100; i++) {
            events.accept(qualifier);
        }

        assertTrue(fired.isEmpty());
        assertEquals(0, qualifier.calls.get());
    }

    @Test
    void theThreeRequestLiteralsAreTrackedSeparately() {
        var observed = List.of(Initialized.Literal.REQUEST, Destroyed.Literal.REQUEST);
        var fired = new ArrayList<Annotation>();
        var events = new ObservedQualifiers(q -> observed.stream().anyMatch(o -> o == q), fired::add);

        events.accept(Initialized.Literal.REQUEST);
        events.accept(BeforeDestroyed.Literal.REQUEST);
        events.accept(Destroyed.Literal.REQUEST);

        assertEquals(List.of(Initialized.Literal.REQUEST, Destroyed.Literal.REQUEST), fired);
        assertFalse(fired.contains(BeforeDestroyed.Literal.REQUEST));
    }
}
