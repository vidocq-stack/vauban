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

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marker qualifier used to disambiguate the atinject {@code SpareTire} bean.
 *
 * <p>The atinject TCK graph classes carry no CDI qualifiers: the bindings
 * {@code @Named("spare") Tire → SpareTire} are external injector configuration.
 * Adding only {@code @Named("spare")} to {@code SpareTire} is not enough to
 * remove the ambiguity with the plain {@code Tire} bean, because CDI 4.1
 * §2.4.2 keeps {@code @Default} on a bean whose only qualifier is
 * {@code @Named}. This extra <em>real</em> qualifier drops {@code @Default}
 * from {@code SpareTire}, mirroring Weld's atinject portable-extension scheme
 * ({@code AtInjectTCKExtension.Spare}).</p>
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, FIELD, METHOD, PARAMETER})
public @interface Spare {
}
