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

import io.vidocq.vauban.core.annotation.AnnotationValues;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.core.types.TypeInfoUtils;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * A member of an annotation type the index does not hold, read by reflection: the fallback of
 * {@link VaubanClassConfig} for an annotation type {@code MetaAnnotations} gives an extension. It answers as the
 * language model does for a member of an indexed annotation type: its return type, its receiver type (the annotation
 * type: a member is an instance method), its declaring class and its annotations (BUG-20261118-02). They were
 * {@code null}, {@code null}, {@code null} and none.
 */
final class ReflectionMethodInfo implements jakarta.enterprise.lang.model.declarations.MethodInfo {

    private final Method method;
    private final IndexLookup lookup;

    /** {@code lookup} describes the classes the types name; {@code null} leaves them stubs. */
    ReflectionMethodInfo(Method method, IndexLookup lookup) {
        this.method = method;
        this.lookup = lookup;
    }

    @Override
    public String name() {
        return method.getName();
    }

    @Override
    public List<ParameterInfo> parameters() {
        return List.of();
    }

    @Override
    public Type returnType() {
        return TypeMapper.map(TypeInfoUtils.fromReflectType(method.getGenericReturnType()), lookup);
    }

    /** The annotation type for an instance member, as {@code VaubanMethodInfo} gives it; {@code null} if static. */
    @Override
    public Type receiverType() {
        return isStatic() ? null : new VaubanClassType(declaringName(), lookup);
    }

    @Override
    public List<Type> throwsTypes() {
        return List.of();
    }

    @Override
    public List<TypeVariable> typeParameters() {
        return List.of();
    }

    @Override
    public boolean isConstructor() {
        return false;
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(method.getModifiers());
    }

    @Override
    public boolean isAbstract() {
        return Modifier.isAbstract(method.getModifiers());
    }

    @Override
    public boolean isFinal() {
        return Modifier.isFinal(method.getModifiers());
    }

    @Override
    public int modifiers() {
        return method.getModifiers();
    }

    @Override
    public ClassInfo declaringClass() {
        return VaubanClassInfo.of(declaringName(), lookup);
    }

    private DotName declaringName() {
        return DotName.of(method.getDeclaringClass().getName());
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return method.isAnnotationPresent(annotationType);
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        var annotation = method.getAnnotation(annotationType);
        return annotation == null ? null : info(annotation);
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        var annotation = annotation(annotationType);
        return annotation != null ? List.of(annotation) : List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return Arrays.stream(method.getDeclaredAnnotations()).map(this::info).toList();
    }

    private AnnotationInfo info(Annotation annotation) {
        return new VaubanAnnotationInfo(AnnotationValues.toAnnotationInfo(annotation), lookup);
    }
}
