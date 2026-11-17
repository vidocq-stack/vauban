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
package io.vidocq.vauban.core.langmodel.types;

import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-60: every annotation query of a type reads the annotations on its use, and none takes part in its
 * equality.
 */
@DisplayName("BUG-20261117-60 - the annotation queries of a type")
class AnnotatedTypeUseTest {

    private static final DotName STRING = DotName.of("java.lang.String");
    private static final VaubanAnnotationInfo DEPRECATED = new VaubanAnnotationInfo(
            new AnnotationInfo(DotName.of(Deprecated.class.getName()), Map.of()), null);

    @Test
    @DisplayName("a type with an annotation answers each query with it")
    void anAnnotatedType() {
        var type = new VaubanClassType(STRING, null, List.of(DEPRECATED));

        assertTrue(type.hasAnnotation(Deprecated.class));
        assertTrue(type.hasAnnotation(annotation -> annotation.name().equals(Deprecated.class.getName())));
        assertEquals(DEPRECATED, type.annotation(Deprecated.class));
        assertEquals(List.of(DEPRECATED), List.copyOf(type.repeatableAnnotation(Deprecated.class)));
        assertEquals(List.of(DEPRECATED), List.copyOf(type.annotations(_ -> true)));
        assertEquals(List.of(), List.copyOf(type.annotations(_ -> false)));
    }

    @Test
    @DisplayName("a type without annotations answers each query with none, and equals its annotated use")
    void aPlainType() {
        var type = new VaubanClassType(STRING, null);

        assertFalse(type.hasAnnotation(Deprecated.class));
        assertFalse(type.hasAnnotation(_ -> true));
        assertNull(type.annotation(Deprecated.class));
        assertEquals(List.of(), List.copyOf(type.repeatableAnnotation(Deprecated.class)));
        assertEquals(new VaubanClassType(STRING, null, List.of(DEPRECATED)), type);
    }
}
