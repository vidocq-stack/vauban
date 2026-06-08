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
package io.vidocq.vauban.core.langmodel.declarations;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class VaubanFieldInfo implements jakarta.enterprise.lang.model.declarations.FieldInfo {

    private final io.vidocq.vauban.indexer.model.FieldInfo indexField;
    private final VaubanClassInfo declaringClass;
    private final IndexLookup lookup;

    public VaubanFieldInfo(io.vidocq.vauban.indexer.model.FieldInfo indexField,
                           VaubanClassInfo declaringClass,
                           IndexLookup lookup) {
        this.indexField = indexField;
        this.declaringClass = declaringClass;
        this.lookup = lookup;
    }

    @Override
    public String name() {
        return indexField.name();
    }

    @Override
    public Type type() {
        return TypeMapper.map(indexField.type(), lookup);
    }

    @Override
    public boolean isStatic() {
        return indexField.isStatic();
    }

    @Override
    public boolean isFinal() {
        return indexField.isFinal();
    }

    @Override
    public int modifiers() {
        return indexField.accessFlags();
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo declaringClass() {
        return declaringClass;
    }

    // -- AnnotationTarget --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexField.annotations().stream().anyMatch(a -> a.name().equals(name));
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexField.annotations().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .orElse(null);
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        var ann = annotation(annotationType);
        return ann != null ? List.of(ann) : List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return indexField.annotations().stream()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanFieldInfo other
                && name().equals(other.name())
                && declaringClass.equals(other.declaringClass);
    }

    @Override
    public int hashCode() {
        return name().hashCode() * 31 + declaringClass.hashCode();
    }

    @Override
    public String toString() {
        return declaringClass.name() + "#" + name();
    }
}
