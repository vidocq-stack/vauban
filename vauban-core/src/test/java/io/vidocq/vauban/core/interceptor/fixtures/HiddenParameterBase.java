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
package io.vidocq.vauban.core.interceptor.fixtures;

/**
 * Class methods whose descriptors name {@link HiddenParameter}, package-private here: a generated
 * subclass in another package may not resolve that class (JVMS 5.4.4), only carry it in a method
 * descriptor (BUG-20261004-09, {@code n3b}).
 */
public class HiddenParameterBase {

    public String take(HiddenParameter parameter) {
        return "took " + parameter;
    }

    public String takeMany(long seed, HiddenParameter[] parameters, int count) {
        return "took " + seed + " " + parameters.length + " " + parameters[0] + " " + count;
    }

    public HiddenParameter give() {
        return new HiddenParameter();
    }

    public static String callTake(HiddenParameterBase base) {
        return base.take(new HiddenParameter());
    }

    public static String callTakeMany(HiddenParameterBase base) {
        return base.takeMany(7L, new HiddenParameter[] {new HiddenParameter()}, 3);
    }

    public static String callGive(HiddenParameterBase base) {
        return String.valueOf(base.give());
    }
}
