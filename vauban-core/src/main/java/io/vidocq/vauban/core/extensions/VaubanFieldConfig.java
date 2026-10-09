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

import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

public final class VaubanFieldConfig implements FieldConfig {

    private final FieldInfo fieldInfo;
    private final Set<Class<? extends Annotation>> addedAnnotations = new LinkedHashSet<>();
    private final List<AnnotationInfo> addedAnnotationInfos = new ArrayList<>();
    /** The instances given to {@link #addAnnotation(Annotation)}, which carry member values. */
    private final java.util.Map<Class<? extends Annotation>, Annotation> addedAnnotationInstances =
            new java.util.LinkedHashMap<>();
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();
    private boolean allAnnotationsRemoved;

    public VaubanFieldConfig(FieldInfo fieldInfo) {
        this.fieldInfo = fieldInfo;
    }

    @Override
    public FieldInfo info() {
        return fieldInfo;
    }

    @Override
    public FieldConfig addAnnotation(Class<? extends Annotation> annotationType) {
        addedAnnotations.add(annotationType);
        return this;
    }

    @Override
    public FieldConfig addAnnotation(AnnotationInfo annotation) {
        addedAnnotationInfos.add(annotation);
        return this;
    }

    @Override
    public FieldConfig addAnnotation(Annotation annotation) {
        addedAnnotations.add(annotation.annotationType());
        addedAnnotationInstances.put(annotation.annotationType(), annotation);
        return this;
    }

    @Override
    public FieldConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        removePredicates.add(predicate);
        return this;
    }

    @Override
    public FieldConfig removeAllAnnotations() {
        allAnnotationsRemoved = true;
        return this;
    }

    /**
     * The instance given to {@link #addAnnotation(Annotation)} for {@code type}, carrying its member
     * values, or {@code null} when the annotation was added by type only.
     */
    public Annotation getAddedAnnotationInstance(Class<? extends Annotation> type) {
        return addedAnnotationInstances.get(type);
    }

    public Set<Class<? extends Annotation>> getAddedAnnotations() {
        return Set.copyOf(addedAnnotations);
    }

    public List<AnnotationInfo> getAddedAnnotationInfos() {
        return List.copyOf(addedAnnotationInfos);
    }

    public List<Annotation> getAddedAnnotationInstances() {
        return List.copyOf(addedAnnotationInstances.values());
    }

    public boolean isAllAnnotationsRemoved() {
        return allAnnotationsRemoved;
    }

    public List<Predicate<AnnotationInfo>> getRemovePredicates() {
        return List.copyOf(removePredicates);
    }

    public boolean isModified() {
        return !addedAnnotations.isEmpty() || !addedAnnotationInfos.isEmpty()
                || !addedAnnotationInstances.isEmpty() || allAnnotationsRemoved || !removePredicates.isEmpty();
    }
}
