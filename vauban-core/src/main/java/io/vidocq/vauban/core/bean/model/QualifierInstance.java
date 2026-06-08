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
package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.Map;

/**
 * Represents a qualifier annotation instance with its member values.
 */
public record QualifierInstance(DotName annotationName, Map<String, AnnotationValue> members) {

    public QualifierInstance {
        members = Map.copyOf(members);
    }

    public static final DotName DEFAULT_NAME = DotName.of("jakarta.enterprise.inject.Default");
    public static final DotName ANY_NAME = DotName.of("jakarta.enterprise.inject.Any");
    public static final DotName NAMED_NAME = DotName.of("jakarta.inject.Named");

    public static final QualifierInstance DEFAULT = new QualifierInstance(DEFAULT_NAME, Map.of());
    public static final QualifierInstance ANY = new QualifierInstance(ANY_NAME, Map.of());

    public static QualifierInstance from(AnnotationInfo annotation) {
        return new QualifierInstance(annotation.name(), annotation.members());
    }

    public boolean isDefault() {
        return annotationName.equals(DEFAULT_NAME);
    }

    public boolean isAny() {
        return annotationName.equals(ANY_NAME);
    }
}
