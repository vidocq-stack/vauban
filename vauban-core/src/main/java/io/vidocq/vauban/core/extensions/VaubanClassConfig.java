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

import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
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
    /** How many of the first methods and fields {@code @Enhancement} calls an extension with (BUG-20261118-48). */
    private final int configurableMethodCount;
    private final int configurableFieldCount;
    private final Set<Class<? extends Annotation>> addedAnnotations = new LinkedHashSet<>();
    private final List<AnnotationInfo> addedAnnotationInfos = new ArrayList<>();
    /** The instances given to {@link #addAnnotation(Annotation)}, which carry member values. */
    private final Map<Class<? extends Annotation>, Annotation> addedAnnotationInstances = new LinkedHashMap<>();
    private final List<Predicate<AnnotationInfo>> removePredicates = new ArrayList<>();
    private boolean allAnnotationsRemoved;
    private Class<?> sourceBce;

    // For meta-annotation use (Discovery phase - qualifiers, interceptor bindings, stereotypes)
    private final Class<? extends Annotation> annotationType;

    /**
     * The configuration of an annotation type a {@code MetaAnnotations} call registers. Its {@link #info()} is never
     * {@code null}: the index's class, or the class file described as the language model describes a class outside
     * the index (BUG-20261117-31), so that its members agree with the indexed path (BUG-20261118-03). A type nothing
     * describes gets a {@link IndexLookup#stub stub}: its {@code info().methods()} is empty, while {@link #methods()}
     * lists the members read by reflection.
     */
    public VaubanClassConfig(Class<? extends Annotation> annotationType, IndexLookup lookup) {
        this.annotationType = annotationType;
        var name = io.vidocq.vauban.indexer.model.DotName.of(annotationType.getName());
        var described = lookup.find(name);
        this.classInfo = new VaubanClassInfo(described.orElseGet(() -> IndexLookup.stub(name)), lookup);

        this.methodConfigs = new ArrayList<>();
        this.fieldConfigs = new ArrayList<>();
        if (described.isPresent()) {
            for (var m : ((VaubanClassInfo) classInfo).configurableMethods()) {
                methodConfigs.add(new VaubanMethodConfig(m));
            }
        } else {
            for (var m : annotationType.getDeclaredMethods()) {
                methodConfigs.add(new VaubanMethodConfig(new ReflectionMethodInfo(m, lookup)));
            }
        }
        this.configurableMethodCount = methodConfigs.size();
        this.configurableFieldCount = 0;
    }

    /**
     * The configuration of a class in {@code @Enhancement}. Its {@link #methods()} and {@link #fields()} are those of
     * {@link ClassInfo#methods()} and {@link ClassInfo#fields()}, inherited ones included, as their Javadoc says ("as
     * defined by {@code ClassInfo.methods}"). Each is configured for this class alone: a member a superclass declares,
     * configured here, changes this class's beans, never the superclass's nor its other subclasses'
     * (BUG-20261118-48). {@code @Enhancement} calls an extension with the members a {@link VaubanClassInfo}
     * configures, those the class declares and its direct superinterfaces' methods the index holds
     * ({@link #configurableMethods()}, {@link #configurableFields()}, BUG-20261117-42).
     */
    public VaubanClassConfig(VaubanClassInfo classInfo) {
        this.annotationType = null;
        this.classInfo = classInfo;

        this.methodConfigs = new ArrayList<>();
        var configured = new HashSet<String>();
        for (var m : classInfo.configurableMethods()) {
            methodConfigs.add(new VaubanMethodConfig(m));
            configured.add(memberKey(m));
        }
        this.configurableMethodCount = methodConfigs.size();
        for (var m : classInfo.methods()) {
            if (configured.add(memberKey(m))) {
                methodConfigs.add(new VaubanMethodConfig(m));
            }
        }
        for (var m : classInfo.constructors()) {
            methodConfigs.add(new VaubanMethodConfig(m));
        }

        this.fieldConfigs = new ArrayList<>();
        for (var f : classInfo.configurableFields()) {
            fieldConfigs.add(new VaubanFieldConfig(f));
            configured.add(memberKey(f));
        }
        this.configurableFieldCount = fieldConfigs.size();
        for (var f : classInfo.fields()) {
            if (configured.add(memberKey(f))) {
                fieldConfigs.add(new VaubanFieldConfig(f));
            }
        }
    }

    /** A method by the class that declares it, its name and its parameter types. */
    private static String memberKey(jakarta.enterprise.lang.model.declarations.MethodInfo method) {
        return method.declaringClass().name() + "#" + method.name() + InjectionPointInfo.methodDescriptor(
                method.parameters().stream().map(parameter -> LangModelTypeMapper.toIndexType(parameter.type()))
                        .toList());
    }

    /** A field by the class that declares it and its name. */
    private static String memberKey(jakarta.enterprise.lang.model.declarations.FieldInfo field) {
        return field.declaringClass().name() + "#field#" + field.name();
    }

    /**
     * The methods {@code @Enhancement} calls a {@code MethodConfig} parameter with: those the class declares, and its
     * direct superinterfaces' methods the index holds; constructors left out (BUG-20261117-42).
     */
    public List<MethodConfig> configurableMethods() {
        return methodConfigs.subList(0, configurableMethodCount).stream()
                .filter(mc -> !mc.info().isConstructor())
                .map(MethodConfig.class::cast)
                .toList();
    }

    /** The fields {@code @Enhancement} calls a {@code FieldConfig} parameter with: those the class declares. */
    public List<FieldConfig> configurableFields() {
        return List.copyOf(fieldConfigs.subList(0, configurableFieldCount));
    }

    /**
     * Whether {@code method} is one {@code @Enhancement} configures through this class's own members, a constructor,
     * a method it declares or a direct superinterface's: the frozen record names any other by the class declaring it.
     */
    public boolean isConfigurable(VaubanMethodConfig method) {
        return method.info().isConstructor() || methodConfigs.indexOf(method) < configurableMethodCount;
    }

    /** Whether {@code field} is one this class declares. */
    public boolean isConfigurable(VaubanFieldConfig field) {
        return fieldConfigs.indexOf(field) < configurableFieldCount;
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

    /**
     * Whether this configuration takes annotations away, from the class, a field, a method or a parameter. A build
     * that freezes what the extensions did records the annotations they add; it cannot say what they remove.
     */
    public boolean removesAnnotations() {
        return allAnnotationsRemoved || !removePredicates.isEmpty()
                || fieldConfigs.stream().anyMatch(VaubanFieldConfig::removesAnnotations)
                || methodConfigs.stream().anyMatch(VaubanMethodConfig::removesAnnotations);
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
