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
import io.vidocq.vauban.core.langmodel.RepeatableAnnotations;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.core.langmodel.types.TypeParameterScope;
import io.vidocq.vauban.core.langmodel.types.TypeUse;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Target;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class VaubanMethodInfo implements jakarta.enterprise.lang.model.declarations.MethodInfo {

    private final io.vidocq.vauban.indexer.model.MethodInfo indexMethod;
    private final VaubanClassInfo declaringClass;
    private final IndexLookup lookup;

    public VaubanMethodInfo(io.vidocq.vauban.indexer.model.MethodInfo indexMethod,
                            VaubanClassInfo declaringClass,
                            IndexLookup lookup) {
        this.indexMethod = indexMethod;
        this.declaringClass = declaringClass;
        this.lookup = lookup;
    }

    /** The name of the method; for a constructor, the binary name of its class, not {@code <init>}. */
    @Override
    public String name() {
        return indexMethod.isConstructor() ? declaringClass.name() : indexMethod.name();
    }

    @Override
    public List<jakarta.enterprise.lang.model.declarations.ParameterInfo> parameters() {
        var params = new ArrayList<jakarta.enterprise.lang.model.declarations.ParameterInfo>();
        for (int i = 0; i < indexMethod.parameters().size(); i++) {
            params.add(new VaubanParameterInfo(indexMethod.parameters().get(i), i, this, lookup));
        }
        return List.copyOf(params);
    }

    /** The return type of the method; for a constructor, the class type of its class, not void (BUG-20261117-54). */
    @Override
    public Type returnType() {
        var returned = indexMethod.isConstructor() ? new TypeInfo.ClassType(DotName.of(declaringClass.name()))
                : indexMethod.returnType();
        return TypeMapper.map(returned, lookup, typeUse(Target.RETURN, 0));
    }

    /**
     * The use of the type the method declares at {@code target} and {@code index}, with the type annotations of the
     * method on it, and the type parameters of the method, then of its class, in scope (BUG-20261117-60).
     */
    public TypeUse typeUse(Target target, int index) {
        return typeParameterScope().use(target, index);
    }

    /** The type parameters of the method, inside those of its class. */
    TypeParameterScope typeParameterScope() {
        return TypeParameterScope.of(indexMethod.typeParameters(), indexMethod.typeAnnotations(),
                declaringClass.typeParameterScope());
    }

    /**
     * The type of the declaring class, the type of the receiver parameter an instance method may declare: for a
     * generic class, parameterized by its own type variables, as reflection gives it. {@code null} for a static method
     * and for a constructor (BUG-20261117-53), but that of an inner member class, whose receiver parameter is of the
     * class that encloses it, as {@code Constructor.getAnnotatedReceiverType()} gives it (BUG-20261118-01).
     */
    @Override
    public Type receiverType() {
        if (indexMethod.isStatic()) {
            return null;
        }
        var receiving = indexMethod.isConstructor() ? declaringClass.enclosingClass().orElse(null) : declaringClass;
        if (receiving == null) {
            return null;
        }
        var name = DotName.of(receiving.name());
        // Each type variable has the bounds its parameter declares, java.lang.Object for none (BUG-20261117-33).
        List<TypeInfo> typeParameters = List.copyOf(receiving.indexTypeParameters());
        var receiver = typeParameters.isEmpty() ? new TypeInfo.ClassType(name)
                : new TypeInfo.ParameterizedType(name, typeParameters);
        return TypeMapper.map(receiver, lookup, typeUse(Target.RECEIVER, 0));
    }

    @Override
    public List<Type> throwsTypes() {
        var thrown = indexMethod.exceptionTypes();
        var scope = typeParameterScope();
        var result = new ArrayList<Type>(thrown.size());
        for (int i = 0; i < thrown.size(); i++) {
            result.add(TypeMapper.map(thrown.get(i), lookup, scope.use(Target.THROWS, i)));
        }
        return List.copyOf(result);
    }

    /**
     * The type parameters the method declares, in declaration order, as the index holds them, each with at least one
     * bound, as {@link VaubanClassInfo#typeParameters()} gives those of a class (BUG-20261117-51).
     */
    @Override
    public List<TypeVariable> typeParameters() {
        return typeParameterScope().typeParameters(lookup);
    }

    @Override
    public boolean isConstructor() {
        return indexMethod.isConstructor();
    }

    @Override
    public boolean isStatic() {
        return indexMethod.isStatic();
    }

    @Override
    public boolean isAbstract() {
        return indexMethod.isAbstract();
    }

    @Override
    public boolean isFinal() {
        return (indexMethod.accessFlags() & 0x0010) != 0;
    }

    @Override
    public int modifiers() {
        return indexMethod.accessFlags();
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo declaringClass() {
        return declaringClass;
    }

    // -- AnnotationTarget --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexMethod.annotations().stream().anyMatch(a -> a.name().equals(name));
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        DotName name = DotName.of(annotationType.getName());
        return indexMethod.annotations().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .orElse(null);
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return RepeatableAnnotations.of(annotations(), annotationType);
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return indexMethod.annotations().stream()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanMethodInfo other
                && name().equals(other.name())
                && declaringClass.equals(other.declaringClass);
    }

    @Override
    public int hashCode() {
        return name().hashCode() * 31 + declaringClass.hashCode();
    }

    @Override
    public String toString() {
        return declaringClass.name() + "#" + indexMethod.name() + "()";
    }
}
