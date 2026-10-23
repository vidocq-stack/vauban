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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.api.ExtensionPhase;
import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The observed type of a synthetic observer added with a lang model type naming a class no class loader can load yet,
 * as when the processor runs the extension and the class is compiled in the same build (VAU-APT-003).
 */
@DisplayName("Observed type of a synthetic observer at build time and at container start (VAU-APT-003)")
class SyntheticObserverBuildTimeTypeTest {

    private static final String UNCOMPILED = "com.example.StillCompiling";

    private static VaubanClassType uncompiled() {
        return new VaubanClassType(DotName.of(UNCOMPILED), null);
    }

    @Test
    @DisplayName("at build time, a class not loadable yet is kept by name, alone or as a type argument")
    void buildTimeKeepsTheObservedTypeByName() {
        var types = new VaubanTypes(null);
        var parameterized = types.parameterized(types.of(List.class).asClass(), uncompiled());

        var observer = ExtensionPhase.atBuildTime(() -> new VaubanSyntheticObserverBuilder<>(uncompiled()));
        var listObserver = ExtensionPhase.atBuildTime(() -> new VaubanSyntheticObserverBuilder<>(parameterized));

        assertEquals(new TypeInfo.ClassType(DotName.of(UNCOMPILED)), observer.getEventTypeInfo());
        assertEquals(UNCOMPILED, observer.getEventType().getTypeName());
        assertEquals("java.util.List<" + UNCOMPILED + ">", listObserver.getEventType().getTypeName());
    }

    @Test
    @DisplayName("at container start, a class the container cannot load still fails the @Synthesis method")
    void containerStartStillLoadsTheObservedType() {
        var observedType = uncompiled();

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new VaubanSyntheticObserverBuilder<>(observedType));

        assertEquals("Cannot resolve type: " + UNCOMPILED, failure.getMessage());
    }
}
