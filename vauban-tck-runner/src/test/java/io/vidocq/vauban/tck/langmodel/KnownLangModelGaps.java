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

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The checks of the language model TCK that fail on Vauban for a known bug: each is reported as skipped, naming its
 * bug, as long as it fails at the line of the TCK pinned here. It turns red once it passes, so that its line here is
 * removed with the fix, and when it fails at another line, which the bug may no longer explain.
 */
final class KnownLangModelGaps {

    private static final String INHERITED = "BUG-20261117-42 (no inherited members)";
    private static final String SYNTHETIC = "BUG-20261117-43 (synthetic members listed)";
    private static final String BOTH = INHERITED + ", " + SYNTHETIC;
    private static final Set<Place> EVERYWHERE = EnumSet.allOf(Place.class);
    private static final Set<Place> PROCESSOR = EnumSet.of(Place.PROCESSOR);
    private static final Set<Place> CLASS_FILES = EnumSet.of(Place.START, Place.FROZEN);

    /** A check that fails for {@code bug} in {@code places}, first at {@code line} of the TCK, as File.java:N. */
    record Gap(String check, String bug, String line, Set<Place> places) {
    }

    /** One line per known failing check: remove it when its bug is fixed. */
    private static final List<Gap> GAPS = List.of(
            new Gap(VerifierSteps.VERIFY, BOTH, "PlainClassMembers.java:211", EVERYWHERE),
            new Gap("PlainClassMembers", INHERITED, "PlainClassMembers.java:211", EVERYWHERE),
            new Gap("EnumMembers", INHERITED, "EnumMembers.java:294", PROCESSOR),
            new Gap("InheritedMethods", INHERITED, "InheritedMethods.java:104", EVERYWHERE),
            new Gap("InheritedFields", INHERITED, "InheritedFields.java:43", EVERYWHERE),
            new Gap("InheritedAnnotations", INHERITED, "InheritedAnnotations.java:140", EVERYWHERE),
            new Gap("BridgeMethods", BOTH, "BridgeMethods.java:74", CLASS_FILES),
            new Gap("BridgeMethods", BOTH, "BridgeMethods.java:56", PROCESSOR),
            new Gap("RepeatableAnnotations", INHERITED, "RepeatableAnnotations.java:108", EVERYWHERE));

    private KnownLangModelGaps() {
    }

    /** The known gap of {@code check} in {@code place}, if it is one there. */
    static Optional<Gap> gap(Place place, String check) {
        return GAPS.stream()
                .filter(gap -> gap.check().equals(check) && gap.places().contains(place))
                .findFirst();
    }
}
