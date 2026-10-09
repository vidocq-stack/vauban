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

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

public final class VaubanClassConfig implements ClassConfig {

    private final ClassInfo classInfo;
    private final List<VaubanMethodConfig> methodConfigs;
    private final List<VaubanFieldConfig> fieldConfigs;
    private final Set<Class<? extends Annotation>> addedAnnotations = new LinkedHashSet<>();
    private final List<AnnotationInfo> addedAnnotationInfos = new ArrayList<>();
    /** The instances given to {@link #addAnnotation(Annotation)}, which carry member values. */
    private final Map<Class<? extends Annotation>, Annotation> addedAnnotationInstances = new LinkedHashMap<>();
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();
    private boolean allAnnotationsRemoved;
    private Class<?> sourceBce;

    // For meta-annotation use (Discovery phase - qualifiers, interceptor bindings, stereotypes)
    private final Class<? extends Annotation> annotationType;

    public VaubanClassConfig(Class<? extends Annotation> annotationType, IndexLookup lookup) {
        this.annotationType = annotationType;

        var indexClass = lookup.getClass(
                io.vidocq.vauban.indexer.model.DotName.of(annotationType.getName())).orElse(null);

        this.classInfo = indexClass != null ? new VaubanClassInfo(indexClass, lookup) : null;

        this.methodConfigs = new ArrayList<>();
        this.fieldConfigs = new ArrayList<>();
        if (classInfo != null) {
            for (var m : classInfo.methods()) {
                methodConfigs.add(new VaubanMethodConfig(m));
            }
        } else {
            for (var m : annotationType.getDeclaredMethods()) {
                methodConfigs.add(new VaubanMethodConfig(new ReflectionMethodInfo(m, annotationType)));
            }
        }
    }

    // For bean enhancement (Enhancement phase)
    public VaubanClassConfig(ClassInfo classInfo) {
        this.annotationType = null;
        this.classInfo = classInfo;

        this.methodConfigs = new ArrayList<>();
        for (var m : classInfo.methods()) {
            methodConfigs.add(new VaubanMethodConfig(m));
        }
        for (var m : classInfo.constructors()) {
            methodConfigs.add(new VaubanMethodConfig(m));
        }

        this.fieldConfigs = new ArrayList<>();
        for (var f : classInfo.fields()) {
            fieldConfigs.add(new VaubanFieldConfig(f));
        }
    }

    @Override
    public ClassInfo info() {
        return classInfo;
    }

    @Override
    public ClassConfig addAnnotation(Class<? extends Annotation> annotationType) {
        addedAnnotations.add(annotationType);
        return this;
    }

    @Override
    public ClassConfig addAnnotation(AnnotationInfo annotation) {
        addedAnnotationInfos.add(annotation);
        return this;
    }

    @Override
    public ClassConfig addAnnotation(Annotation annotation) {
        addedAnnotations.add(annotation.annotationType());
        addedAnnotationInstances.put(annotation.annotationType(), annotation);
        return this;
    }

    @Override
    public ClassConfig removeAnnotation(Predicate<AnnotationInfo> predicate) {
        removePredicates.add(predicate);
        return this;
    }

    @Override
    public ClassConfig removeAllAnnotations() {
        allAnnotationsRemoved = true;
        return this;
    }

    @Override
    public Collection<MethodConfig> constructors() {
        return methodConfigs.stream()
                .filter(mc -> mc.info().isConstructor())
                .map(mc -> (MethodConfig) mc)
                .toList();
    }

    @Override
    public Collection<MethodConfig> methods() {
        return methodConfigs.stream()
                .filter(mc -> !mc.info().isConstructor())
                .map(mc -> (MethodConfig) mc)
                .toList();
    }

    @Override
    public Collection<FieldConfig> fields() {
        return List.copyOf(fieldConfigs);
    }

    public Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }

    public Set<Class<? extends Annotation>> getAddedAnnotations() {
        return Set.copyOf(addedAnnotations);
    }

    public List<AnnotationInfo> getAddedAnnotationInfos() {
        return List.copyOf(addedAnnotationInfos);
    }

    /**
     * The instance given to {@link #addAnnotation(Annotation)} for {@code type}, carrying its member
     * values, or {@code null} when the annotation was added by type only.
     */
    public Annotation getAddedAnnotationInstance(Class<? extends Annotation> type) {
        return addedAnnotationInstances.get(type);
    }

    /**
     * Every annotation added to the class, in index form and with its member values, whichever
     * {@code addAnnotation} added it: one added by type has no member (BUG-20261008-05).
     */
    public List<io.vidocq.vauban.indexer.model.AnnotationInfo> getAddedAnnotationsIndexed() {
        var result = new ArrayList<io.vidocq.vauban.indexer.model.AnnotationInfo>();
        for (var type : addedAnnotations) {
            var instance = addedAnnotationInstances.get(type);
            result.add(instance != null
                    ? io.vidocq.vauban.core.annotation.AnnotationValues.infoOf(instance)
                    : new io.vidocq.vauban.indexer.model.AnnotationInfo(
                            io.vidocq.vauban.indexer.model.DotName.of(type.getName()), Map.of()));
        }
        for (var info : addedAnnotationInfos) {
            result.add(io.vidocq.vauban.core.langmodel.LangModelAnnotations.toIndex(info));
        }
        return result;
    }

    public boolean isAllAnnotationsRemoved() {
        return allAnnotationsRemoved;
    }

    public List<Predicate<AnnotationInfo>> getRemovePredicates() {
        return List.copyOf(removePredicates);
    }

    public Set<String> getNonbindingMembers() {
        var result = new HashSet<String>();
        for (var mc : methodConfigs) {
            if (mc.hasAddedAnnotation(jakarta.enterprise.util.Nonbinding.class)) {
                result.add(mc.info().name());
            }
        }
        return result;
    }

    public List<VaubanMethodConfig> getMethodConfigs() {
        return List.copyOf(methodConfigs);
    }

    public List<VaubanFieldConfig> getFieldConfigs() {
        return List.copyOf(fieldConfigs);
    }

    public Class<?> getSourceBce() {
        return sourceBce;
    }

    public void setSourceBce(Class<?> sourceBce) {
        this.sourceBce = sourceBce;
    }

    public boolean isModified() {
        if (!addedAnnotations.isEmpty() || !addedAnnotationInfos.isEmpty()
                || allAnnotationsRemoved || !removePredicates.isEmpty()) {
            return true;
        }
        if (methodConfigs.stream().anyMatch(VaubanMethodConfig::isModified)) return true;
        return fieldConfigs.stream().anyMatch(VaubanFieldConfig::isModified);
    }
}
