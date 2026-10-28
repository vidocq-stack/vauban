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

import io.vidocq.vauban.api.ExtensionPhase;
import io.vidocq.vauban.core.loading.DeploymentClasses;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import jakarta.enterprise.lang.model.types.WildcardType;

import java.lang.annotation.Annotation;
import java.util.*;

public final class VaubanSyntheticObserverBuilder<T> implements SyntheticObserverBuilder<T> {

    private final java.lang.reflect.Type eventType;
    private final TypeInfo eventTypeInfo;
    private final Set<Annotation> qualifiers = new LinkedHashSet<>();
    private int priority = jakarta.interceptor.Interceptor.Priority.APPLICATION;
    private boolean async;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private Class<? extends SyntheticObserver<T>> observerClass;

    public VaubanSyntheticObserverBuilder(Class<T> eventType) {
        this.eventType = eventType;
        this.eventTypeInfo = new TypeInfo.ClassType(DotName.of(eventType.getName()));
    }

    /**
     * An observer of any type the lang model can express: the index form keeps every shape the observer
     * resolution needs, wildcard arguments and arrays included (VAU-OBS-003), as the bean types of a
     * synthetic bean do (VAU-BCE-001).
     *
     * <p>At build time the reflective form only names the type, as the boot does with the metadata (VAU-OBS-005): the
     * processor records the index form, and the observed class may be one the compilation is still producing, which
     * no class loader can load yet (VAU-APT-003). When the container runs the extension, the observed type is loaded
     * here, and a type it cannot load fails the {@code @Synthesis} method.
     */
    public VaubanSyntheticObserverBuilder(Type eventType) {
        this.eventTypeInfo = LangModelTypeMapper.toIndexType(eventType);
        this.eventType = ExtensionPhase.isBuildTime()
                ? new NamedType(ObservedTypeNotation.write(eventTypeInfo))
                : toReflectType(eventType);
    }

    /**
     * An observer the boot rebuilds from the synthetic metadata the processor wrote, for a generic or an array type
     * (VAU-OBS-005): the observer resolution uses the index form, and resolves its classes by name; the reflective form
     * only names the type.
     */
    public VaubanSyntheticObserverBuilder(TypeInfo eventTypeInfo) {
        this.eventType = new NamedType(ObservedTypeNotation.write(eventTypeInfo));
        this.eventTypeInfo = eventTypeInfo;
    }

    private static java.lang.reflect.Type toReflectType(Type type) {
        return switch (type) {
            case ClassType ct -> loadClass(extractClassName(ct));
            case ParameterizedType pt -> new SimpleParameterizedType(loadClass(extractClassName(pt.genericClass())),
                    pt.typeArguments().stream()
                            .map(VaubanSyntheticObserverBuilder::toReflectType)
                            .toArray(java.lang.reflect.Type[]::new));
            case ArrayType at -> arrayOf(toReflectType(at.componentType()));
            case WildcardType wt -> new SimpleWildcardType(
                    wt.upperBound() != null ? toReflectType(wt.upperBound()) : Object.class,
                    wt.lowerBound() != null ? toReflectType(wt.lowerBound()) : null);
            case PrimitiveType pt -> Class.forPrimitiveName(pt.primitiveKind().name().toLowerCase(Locale.ROOT));
            case TypeVariable tv -> new NamedType(tv.name());
            default -> throw new IllegalArgumentException("Unsupported type for synthetic observer: " + type);
        };
    }

    private static java.lang.reflect.Type arrayOf(java.lang.reflect.Type componentType) {
        return componentType instanceof Class<?> componentClass
                ? componentClass.arrayType()
                : new SimpleGenericArrayType(componentType);
    }

    private static Class<?> loadClass(String name) {
        try {
            return DeploymentClasses.load(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot resolve type: " + name, e);
        }
    }

    private static String extractClassName(ClassType ct) {
        if (ct instanceof io.vidocq.vauban.core.langmodel.types.VaubanClassType vct) {
            return vct.dotName().value();
        }
        return ct.declaration().name();
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(Class<?> declaringClass) {
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> declaringClass(ClassInfo declaringClass) {
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(Class<? extends Annotation> qualifierAnnotation) {
        qualifiers.add(SyntheticQualifiers.marker(qualifierAnnotation));
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(AnnotationInfo qualifierAnnotation) {
        // With its member values: a synthetic observer qualified @Channel("alpha") observes that event
        // and no other (BUG-20260914-15).
        var instance = SyntheticQualifiers.instanceOf(qualifierAnnotation);
        if (instance != null) {
            qualifiers.add(instance);
        }
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> qualifier(Annotation qualifierAnnotation) {
        qualifiers.add(qualifierAnnotation);
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> priority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> async(boolean isAsync) {
        this.async = isAsync;
        return this;
    }

    @Override
    public SyntheticObserverBuilder<T> transactionPhase(jakarta.enterprise.event.TransactionPhase transactionPhase) {
        return this;
    }

    // --- withParam overloads ---
    @Override public SyntheticObserverBuilder<T> withParam(String key, boolean value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, boolean[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, int value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, int[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, long value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, long[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, double value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, double[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, String value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, String[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Enum<?> value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Enum<?>[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Class<?> value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, ClassInfo value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Class<?>[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, ClassInfo[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Annotation value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, AnnotationInfo[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, Annotation[] value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, InvokerInfo value) { params.put(key, value); return this; }
    @Override public SyntheticObserverBuilder<T> withParam(String key, InvokerInfo[] value) { params.put(key, value); return this; }

    @Override
    public SyntheticObserverBuilder<T> observeWith(Class<? extends SyntheticObserver<T>> observerClass) {
        this.observerClass = observerClass;
        return this;
    }

    // --- Accessors ---
    public java.lang.reflect.Type getEventType() { return eventType; }
    /** The observed event type in the form of the index, the one the observer resolution matches events against. */
    public TypeInfo getEventTypeInfo() { return eventTypeInfo; }
    public Set<Annotation> getQualifiers() { return qualifiers; }
    public int getPriority() { return priority; }
    public boolean isAsync() { return async; }
    public Map<String, Object> getParams() { return params; }
    public Class<? extends SyntheticObserver<T>> getObserverClass() { return observerClass; }

    private record SimpleParameterizedType(
            Class<?> rawType,
            java.lang.reflect.Type[] typeArguments
    ) implements java.lang.reflect.ParameterizedType {
        @Override public java.lang.reflect.Type[] getActualTypeArguments() { return typeArguments; }
        @Override public java.lang.reflect.Type getRawType() { return rawType; }
        @Override public java.lang.reflect.Type getOwnerType() { return null; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof java.lang.reflect.ParameterizedType other)) return false;
            return java.util.Objects.equals(rawType, other.getRawType())
                    && java.util.Arrays.equals(typeArguments, other.getActualTypeArguments())
                    && other.getOwnerType() == null;
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(typeArguments) ^ java.util.Objects.hashCode(rawType);
        }
        @Override public String toString() {
            if (typeArguments.length == 0) return rawType.getTypeName();
            var sb = new StringBuilder(rawType.getTypeName()).append('<');
            for (int i = 0; i < typeArguments.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArguments[i].getTypeName());
            }
            return sb.append('>').toString();
        }
    }

    /** A generic array type, {@code List<String>[]}: an array whose component is no class. */
    private record SimpleGenericArrayType(java.lang.reflect.Type componentType)
            implements java.lang.reflect.GenericArrayType {
        @Override public java.lang.reflect.Type getGenericComponentType() { return componentType; }
        @Override public String toString() { return componentType.getTypeName() + "[]"; }
    }

    /** A wildcard type argument; {@code lowerBound} is {@code null} for a wildcard with no lower bound. */
    private record SimpleWildcardType(java.lang.reflect.Type upperBound, java.lang.reflect.Type lowerBound)
            implements java.lang.reflect.WildcardType {
        @Override public java.lang.reflect.Type[] getUpperBounds() { return new java.lang.reflect.Type[]{upperBound}; }
        @Override public java.lang.reflect.Type[] getLowerBounds() {
            return lowerBound == null ? new java.lang.reflect.Type[0] : new java.lang.reflect.Type[]{lowerBound};
        }
        @Override public String toString() {
            if (lowerBound != null) return "? super " + lowerBound.getTypeName();
            return upperBound == Object.class ? "?" : "? extends " + upperBound.getTypeName();
        }
    }

    /**
     * A type the reflective form knows by name only: a type variable, whose bounds are in the index form and whose
     * declaration the lang model type does not give, a type the boot read from the synthetic metadata, or any observed
     * type at build time.
     */
    private record NamedType(String name) implements java.lang.reflect.Type {
        @Override public String getTypeName() { return name; }
    }
}
