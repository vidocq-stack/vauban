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
package io.vidocq.vauban.core.annotation;

import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AnnotationTypes} describing a type whose first class file is garbage, and keying an annotation whose
 * required member is neither written nor defaulted.
 */
class AnnotationTypesUnreadableSourcesTest {

    private static final ClassLoader LOADER = AnnotationTypesUnreadableSourcesTest.class.getClassLoader();

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Required {
        String value();

        int rank() default 1;
    }

    /** Serves bytes that are no class file for every resource. */
    private static final ClassLoader GARBAGE = new ClassLoader(LOADER) {
        @Override
        public InputStream getResourceAsStream(String name) {
            return new ByteArrayInputStream("not a class file".getBytes(StandardCharsets.UTF_8));
        }
    };

    @Test
    void anUnreadableClassFileFallsBackToTheNextLoader() {
        var types = new AnnotationTypes(null, List.of(GARBAGE, LOADER), Map.of());

        var described = types.type(DotName.of(Required.class.getName())).orElseThrow();

        assertEquals(Set.of("value", "rank"), described.members().stream().map(AnnotationTypeInfo.Member::name).collect(Collectors.toSet()));
    }

    @Test
    void aRequiredMemberLeftUnwrittenIsLeftOutOfTheKey() {
        var types = new AnnotationTypes(null, List.of(LOADER), Map.of());

        var key = types.key(DotName.of(Required.class.getName()), Map.of());

        assertEquals(Set.of("rank"), key.members().keySet());
    }
}
