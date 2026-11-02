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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VaubanContainer - annotation names from the index")
class IndexedAnnotationNamesTest {

    @ApplicationScoped
    @Named("indexed")
    public static class IndexedBean {
    }

    @Test
    @DisplayName("the annotations declared on a bean class are read from the index, without reflection")
    void namesTheAnnotationsOfAnIndexedClass() {
        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addBeanClass(IndexedBean.class)
                .build()) {

            Optional<Set<String>> names = container.indexedAnnotationNames(IndexedBean.class);

            assertEquals(Optional.of(Set.of(ApplicationScoped.class.getName(), Named.class.getName())), names);
        }
    }

    @Test
    @DisplayName("a class the index does not know gives no answer, so that the caller reads it by reflection")
    void givesNoAnswerForAClassOutsideTheIndex() {
        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addBeanClass(IndexedBean.class)
                .build()) {

            assertTrue(container.indexedAnnotationNames(String.class).isEmpty());
        }
    }
}
