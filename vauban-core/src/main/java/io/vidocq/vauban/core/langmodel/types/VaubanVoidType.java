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

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.VoidType;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class VaubanVoidType implements VoidType {

    public static final VaubanVoidType INSTANCE = new VaubanVoidType();

    private VaubanVoidType() {
    }

    @Override
    public String name() {
        return "void";
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return false;
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return false;
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return null;
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return List.of();
    }

    @Override
    public String toString() {
        return "void";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanVoidType;
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
