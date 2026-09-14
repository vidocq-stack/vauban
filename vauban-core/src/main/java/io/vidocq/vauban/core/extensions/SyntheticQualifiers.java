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

import io.vidocq.vauban.core.annotation.AnnotationInstances;
import io.vidocq.vauban.core.langmodel.LangModelAnnotations;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * The qualifier instances the synthetic component builders hold. An extension names a qualifier type,
 * or writes one with {@code AnnotationBuilder}; both end up as instances that honour the
 * {@code Annotation} contract and carry their member values through to resolution.
 */
final class SyntheticQualifiers {

    private SyntheticQualifiers() {
    }

    /** An instance of {@code type} with every member at its default. */
    static Annotation marker(Class<? extends Annotation> type) {
        return AnnotationInstances.create(type, new AnnotationInfo(DotName.of(type.getName()), Map.of()),
                type.getClassLoader());
    }

    /**
     * An instance of the annotation an extension wrote, member values included, or {@code null} when
     * its type is on no class loader the container can see.
     */
    static Annotation instanceOf(jakarta.enterprise.lang.model.AnnotationInfo qualifier) {
        var index = LangModelAnnotations.toIndex(qualifier);
        return AnnotationInstances.typeNamed(index.name().value(), Thread.currentThread().getContextClassLoader())
                .map(type -> AnnotationInstances.create(type, index, type.getClassLoader()))
                .orElse(null);
    }
}
