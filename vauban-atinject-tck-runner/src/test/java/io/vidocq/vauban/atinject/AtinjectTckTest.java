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
package io.vidocq.vauban.atinject;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import junit.framework.TestResult;

import org.atinject.tck.Tck;
import org.atinject.tck.auto.Car;
import org.atinject.tck.auto.Convertible;
import org.atinject.tck.auto.DriversSeat;
import org.atinject.tck.auto.FuelTank;
import org.atinject.tck.auto.Seat;
import org.atinject.tck.auto.Seatbelt;
import org.atinject.tck.auto.Tire;
import org.atinject.tck.auto.V8Engine;
import org.atinject.tck.auto.accessories.Cupholder;
import org.atinject.tck.auto.accessories.RoundThing;
import org.atinject.tck.auto.accessories.SpareTire;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Runs the official Jakarta Dependency Injection 2.0 (atinject) TCK against
 * Vauban. The TCK exercises constructor / field / method injection, qualifiers
 * ({@code @Drivers}, {@code @Named("spare")}) and circular {@code Provider}
 * references by building a fully-wired {@link Car} graph and running the suite
 * returned by {@link Tck#testsFor}.
 *
 * <p>The graph is materialised through the standard CDI SE bootstrap: every
 * concrete {@code org.atinject.tck.auto} class is registered as a bean and the
 * container resolves {@link Car} (implemented by {@link Convertible}). CDI
 * performs private and member injection but not static injection, so
 * {@code supportsStatic} is {@code false}.</p>
 */
class AtinjectTckTest {

    @Test
    void jakarta_inject_2_0_tck_passes() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(
                        Convertible.class, DriversSeat.class, Seat.class, Seatbelt.class,
                        Tire.class, V8Engine.class, FuelTank.class,
                        Cupholder.class, RoundThing.class, SpareTire.class,
                        // BCE that re-creates the atinject injector bindings as CDI
                        // qualifiers (@Drivers on DriversSeat, @Named("spare")+@Spare on
                        // SpareTire, @Spare on Convertible.spareTire). Registered here
                        // because disableDiscovery() skips the BCE ServiceLoader scan.
                        AtinjectTckExtension.class)
                .initialize()) {

            Car car = container.select(Car.class).get();

            // supportsStatic = false (CDI does not inject static members),
            // supportsPrivate = true (CDI injects private fields/methods).
            junit.framework.Test suite = Tck.testsFor(car, false, true);
            TestResult result = new TestResult();
            suite.run(result);

            Assertions.assertEquals(0, result.errorCount() + result.failureCount(),
                    () -> describe(result));
        }
    }

    private static String describe(TestResult result) {
        var sb = new StringBuilder("atinject TCK failures:");
        result.failures().asIterator().forEachRemaining(f -> sb.append("\n  FAIL ").append(f));
        result.errors().asIterator().forEachRemaining(e -> sb.append("\n  ERROR ").append(e));
        return sb.toString();
    }
}
