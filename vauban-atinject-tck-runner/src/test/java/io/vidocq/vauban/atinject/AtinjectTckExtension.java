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

import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.inject.Named;

import org.atinject.tck.auto.Convertible;
import org.atinject.tck.auto.Drivers;
import org.atinject.tck.auto.DriversSeat;
import org.atinject.tck.auto.accessories.SpareTire;

/**
 * Vauban Build Compatible Extension that reproduces, in CDI Lite terms, the
 * external injector configuration the Jakarta Dependency Injection (atinject)
 * TCK expects. The TCK graph classes are plain Java classes with no CDI
 * qualifier annotations; the bindings {@code @Drivers Seat → DriversSeat} and
 * {@code @Named("spare") Tire → SpareTire} live in the injector configuration,
 * not on the classes.
 *
 * <p>This mirrors Weld's portable {@code AtInjectTCKExtension} (which uses
 * {@code ProcessAnnotatedType}, unavailable in CDI Lite) with three
 * {@code @Enhancement} phases:</p>
 * <ol>
 *   <li>{@link DriversSeat} gains {@code @Drivers} (a real qualifier) so it
 *       satisfies {@code @Inject @Drivers Seat} and no longer answers the plain
 *       {@code @Inject Seat} injection point.</li>
 *   <li>{@link SpareTire} gains {@code @Named("spare")} <em>and</em>
 *       {@code @Spare}. {@code @Named} alone would keep {@code @Default}
 *       (CDI 4.1 §2.4.2); the extra {@code @Spare} qualifier drops
 *       {@code @Default}, removing the ambiguity with the plain {@code Tire}
 *       bean while {@code @Named("spare")} answers the {@code @Named("spare")
 *       Tire} injection points.</li>
 *   <li>{@code Convertible.spareTire} (the {@code @Inject SpareTire} field,
 *       which now cannot resolve because {@code SpareTire} lost
 *       {@code @Default}) gains {@code @Spare} so it links to the
 *       {@code SpareTire} bean.</li>
 * </ol>
 */
public class AtinjectTckExtension implements BuildCompatibleExtension {

    @Enhancement(types = DriversSeat.class)
    public void configureDriversSeat(ClassConfig driversSeat) {
        driversSeat.addAnnotation(Drivers.class);
    }

    @Enhancement(types = SpareTire.class)
    public void configureSpareTire(ClassConfig spareTire) {
        spareTire.addAnnotation(
                AnnotationBuilder.of(Named.class).member("value", "spare").build());
        spareTire.addAnnotation(Spare.class);
    }

    @Enhancement(types = Convertible.class)
    public void configureConvertible(ClassConfig convertible) {
        for (FieldConfig field : convertible.fields()) {
            if ("spareTire".equals(field.info().name())) {
                field.addAnnotation(Spare.class);
            }
        }
    }
}
