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

import java.lang.annotation.Annotation;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Fires a lifecycle event of the request context only when it has observers, deciding that once per
 * qualifier. The request context calls it with the same three constants on every request
 * ({@code Initialized.Literal.REQUEST}…), so the qualifiers are matched by identity: a hash-based map
 * would call {@code AnnotationLiteral.hashCode()}, which reads the annotation's members by reflection,
 * on every request (VAU-PERF-001).
 */
final class ObservedQualifiers implements Consumer<Annotation> {

    private record Entry(Annotation qualifier, boolean observed) {}

    private final Predicate<Annotation> hasObservers;
    private final Consumer<Annotation> fire;
    /** Copy-on-write: read without locking on every request, replaced as a whole once per new qualifier. */
    private final java.util.concurrent.atomic.AtomicReference<Entry[]> entries =
            new java.util.concurrent.atomic.AtomicReference<>(new Entry[0]);

    ObservedQualifiers(Predicate<Annotation> hasObservers, Consumer<Annotation> fire) {
        this.hasObservers = hasObservers;
        this.fire = fire;
    }

    @Override
    public void accept(Annotation qualifier) {
        if (observed(qualifier)) {
            fire.accept(qualifier);
        }
    }

    private boolean observed(Annotation qualifier) {
        var known = find(entries.get(), qualifier);
        return known != null ? known.observed() : resolve(qualifier);
    }

    private synchronized boolean resolve(Annotation qualifier) {
        var current = entries.get();
        var known = find(current, qualifier); // another thread may have resolved it meanwhile
        if (known != null) {
            return known.observed();
        }
        var observed = hasObservers.test(qualifier);
        var next = java.util.Arrays.copyOf(current, current.length + 1);
        next[current.length] = new Entry(qualifier, observed);
        entries.set(next);
        return observed;
    }

    private static Entry find(Entry[] entries, Annotation qualifier) {
        for (var entry : entries) {
            if (entry.qualifier() == qualifier) {
                return entry;
            }
        }
        return null;
    }
}
