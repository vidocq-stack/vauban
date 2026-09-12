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
package io.vidocq.vauban.processor.codegen.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vidocq.vauban.processor.codegen.proxy.ProducerProxyEligibility.Reason;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The verdict enum exists twice: this one, built from {@code javax.lang.model} elements during
 * annotation processing, and its twin in {@code vauban-core}, built by reflection for the Maven
 * plugin and the runtime. Both decide the same two questions — can this type be proxied from the
 * producer's package, and failing that, can a proxy placed inside the type's own package rescue it.
 * A drift between them would mean the build ships placed bytes the runtime never looks for, or the
 * reverse, so the two answers are pinned together here.
 */
@DisplayName("Eligibility verdicts — the element and reflection front-ends must agree")
class ProducerProxyEligibilityParityTest {

    @Test
    @DisplayName("both enums declare the same verdicts, in the same order")
    void sameVerdicts() {
        assertEquals(names(Reason.values()), names(io.vidocq.vauban.core.proxy.ProducerProxyEligibility.Reason.values()),
                "a verdict added on one side only would be unreachable from the other front-end");
    }

    @Test
    @DisplayName("a verdict is placeable on both sides, or on neither")
    void samePlaceableSet() {
        var here = Arrays.stream(Reason.values()).filter(Reason::placeable).map(Enum::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        var there = Arrays.stream(io.vidocq.vauban.core.proxy.ProducerProxyEligibility.Reason.values())
                .filter(io.vidocq.vauban.core.proxy.ProducerProxyEligibility.Reason::placeable)
                .map(Enum::name).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        assertEquals(here, there, "the two front-ends disagree on what an in-package proxy can rescue");
        assertEquals(Set.of("NO_ACCESSIBLE_CTOR", "PROTECTED_VIRTUALS", "PACKAGE_PRIVATE_VIRTUALS"), here,
                "only cross-package obstacles are placeable; final, sealed and abstract are unproxyable anywhere");
    }

    @Test
    @DisplayName("eligible is never placeable: it already has a proxy in the producer's package")
    void eligibleIsNotPlaceable() {
        assertEquals(EnumSet.of(Reason.ELIGIBLE),
                EnumSet.copyOf(Arrays.stream(Reason.values()).filter(Reason::eligible).toList()));
        org.junit.jupiter.api.Assertions.assertFalse(Reason.ELIGIBLE.placeable());
    }

    private static java.util.List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
