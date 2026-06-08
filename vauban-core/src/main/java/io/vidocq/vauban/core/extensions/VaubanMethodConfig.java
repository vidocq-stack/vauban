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

import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.inject.build.compatible.spi.ParameterConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

public final class VaubanMethodConfig implements MethodConfig {

    private final MethodInfo methodInfo;
    private final Set<Class<? extends Annotation>> addedAnnotations = new LinkedHashSet<>();
    private final List<Annotation> addedAnnotationInstances = new ArrayList<>();
    private final List<AnnotationInfo> addedAnnotationInfos = new ArrayList<>();
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();
    private boolean allAnnotationsRemoved;
    private final List<VaubanParameterConfig> parameterConfigs;

    public VaubanMethodConfig(MethodInfo methodInfo) {
        this.methodInfo = methodInfo;
        this.parameterConfigs = new ArrayList<>();
        for (var p : methodInfo.parameters()) {
            parameterConfigs.add(new VaubanParameterConfig(p));
        }
    }

    @Override
    public MethodInfo info() {
        return methodInfo;
    }

    @Override
    public MethodConfig addAnnotation(Class<? extends Annotation> annotationType) {
        addedAnnotations.add(annotationType);
        return this;
    }

    @Override
    public MethodConfig addAnnotation(AnnotationInfo annotation) {
        addedAnnotationInfos.add(annotation);
        return this;
    }

    @Override
    public MethodConfig addAnnotation(Annotation annotation) {
        try {
            addedAnnotations.add(annotation.annotationType());
            addedAnnotationInstances.add(annotation);
        } catch (Exception ignored) {
            // intentionally empty
        }
        return this;
    }

    @Override
    public MethodConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        removePredicates.add(predicate);
        return this;
    }

    @Override
    public MethodConfig removeAllAnnotations() {
        allAnnotationsRemoved = true;
        return this;
    }

    @Override
    public List<ParameterConfig> parameters() {
        return List.copyOf(parameterConfigs);
    }

    public Set<Class<? extends Annotation>> getAddedAnnotations() {
        return Set.copyOf(addedAnnotations);
    }

    public List<Annotation> getAddedAnnotationInstances() {
        return List.copyOf(addedAnnotationInstances);
    }

    public List<AnnotationInfo> getAddedAnnotationInfos() {
        return List.copyOf(addedAnnotationInfos);
    }

    public boolean hasAddedAnnotation(Class<? extends Annotation> annotationType) {
        return addedAnnotations.contains(annotationType);
    }

    public boolean isAllAnnotationsRemoved() {
        return allAnnotationsRemoved;
    }

    public List<Predicate<AnnotationInfo>> getRemovePredicates() {
        return List.copyOf(removePredicates);
    }

    public List<VaubanParameterConfig> getParameterConfigs() {
        return List.copyOf(parameterConfigs);
    }

    public boolean isModified() {
        if (!addedAnnotations.isEmpty() || !addedAnnotationInfos.isEmpty()
                || allAnnotationsRemoved || !removePredicates.isEmpty()) {
            return true;
        }
        return parameterConfigs.stream().anyMatch(VaubanParameterConfig::isModified);
    }
}
