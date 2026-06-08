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

import jakarta.enterprise.inject.build.compatible.spi.ParameterConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

public final class VaubanParameterConfig implements ParameterConfig {

    private final ParameterInfo parameterInfo;
    private final List<AnnotationInfo> addedAnnotations = new ArrayList<>();
    private final Set<Class<? extends Annotation>> addedAnnotationClasses = new LinkedHashSet<>();
    private boolean allAnnotationsRemoved;
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();

    public VaubanParameterConfig(ParameterInfo parameterInfo) {
        this.parameterInfo = parameterInfo;
    }

    @Override
    public ParameterInfo info() {
        return parameterInfo;
    }

    @Override
    public ParameterConfig addAnnotation(Class<? extends Annotation> annotationType) {
        addedAnnotationClasses.add(annotationType);
        return this;
    }

    @Override
    public ParameterConfig addAnnotation(AnnotationInfo annotation) {
        addedAnnotations.add(annotation);
        return this;
    }

    @Override
    public ParameterConfig addAnnotation(Annotation annotation) {
        addedAnnotationClasses.add(annotation.annotationType());
        return this;
    }

    @Override
    public ParameterConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        removePredicates.add(predicate);
        return this;
    }

    @Override
    public ParameterConfig removeAllAnnotations() {
        allAnnotationsRemoved = true;
        return this;
    }

    public Set<Class<? extends Annotation>> getAddedAnnotationClasses() {
        return Set.copyOf(addedAnnotationClasses);
    }

    public List<AnnotationInfo> getAddedAnnotations() {
        return List.copyOf(addedAnnotations);
    }

    public boolean isAllAnnotationsRemoved() {
        return allAnnotationsRemoved;
    }

    public List<Predicate<AnnotationInfo>> getRemovePredicates() {
        return List.copyOf(removePredicates);
    }

    public boolean isModified() {
        return !addedAnnotationClasses.isEmpty() || !addedAnnotations.isEmpty()
                || allAnnotationsRemoved || !removePredicates.isEmpty();
    }
}
