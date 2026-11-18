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
package io.vidocq.vauban.tck.langmodel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The checks of the language model TCK that fail on Vauban for a known bug: each is reported as skipped, naming its
 * bug, as long as it fails at the line of the TCK pinned here. It turns red once it passes, so that its line here is
 * removed with the fix, and when it fails at another line, which the bug may no longer explain.
 */
final class KnownLangModelGaps {

    /** A check that fails for {@code bug} in {@code places}, first at {@code line} of the TCK, as File.java:N. */
    record Gap(String check, String bug, String line, Set<Place> places) {
    }

    /**
     * One line per known failing check, none today: BUG-20261117-42 and 43 were the last. To add one, name the check,
     * its bug, the TCK line it fails at first, the first one its failure message names, and the places, as
     * {@code new Gap("InheritedMethods", "BUG-…", "InheritedMethods.java:104", EnumSet.allOf(Place.class))}.
     */
    private static final List<Gap> GAPS = List.of();

    private KnownLangModelGaps() {
    }

    /** The known gap of {@code check} in {@code place}, if it is one there. */
    static Optional<Gap> gap(Place place, String check) {
        return GAPS.stream()
                .filter(gap -> gap.check().equals(check) && gap.places().contains(place))
                .findFirst();
    }
}
