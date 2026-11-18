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
package io.vidocq.vauban.core.event;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.container.QualifierUtils;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.spi.ObserverMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * CDI {@link ObserverMethod} implementation backed by an {@link ObserverDescriptor}.
 */
public final class VaubanObserverMethod<T> implements ObserverMethod<T> {

    private final ObserverDescriptor descriptor;
    private final EventDispatcher dispatcher;
    private final Bean<?> declaringBean;
    private final ClassLoader classLoader;

    public VaubanObserverMethod(ObserverDescriptor descriptor, EventDispatcher dispatcher,
            Bean<?> declaringBean, ClassLoader classLoader) {
        this.descriptor = descriptor;
        this.dispatcher = dispatcher;
        this.declaringBean = declaringBean;
        this.classLoader = classLoader;
    }

    @Override
    public Class<?> getBeanClass() {
        try {
            return Class.forName(descriptor.declaringClass().value(), true, classLoader);
        } catch (ClassNotFoundException _) {
            return Object.class;
        }
    }

    @Override
    public Bean<?> getDeclaringBean() {
        return declaringBean;
    }

    @Override
    public Type getObservedType() {
        return resolveTypeInfo(descriptor.eventType());
    }

    private Type resolveTypeInfo(TypeInfo typeInfo) {
        return reflectTypeOf(typeInfo, classLoader);
    }

    /**
     * {@code typeInfo} as a {@link Type}, its classes loaded through {@code classLoader}, as the build recorded it: the
     * observed type of a synthetic observer, the looked-up parameter of an invoker the build generated
     * (BUG-20261118-37). A class that cannot be loaded is {@code Object}.
     */
    public static Type reflectTypeOf(TypeInfo typeInfo, ClassLoader classLoader) {
        return switch (typeInfo) {
            case TypeInfo.ClassType(DotName(var name)) -> {
                try {
                    yield Class.forName(name, true, classLoader);
                } catch (ClassNotFoundException _) {
                    yield Object.class;
                }
            }
            case TypeInfo.ParameterizedType(DotName(var rawName), var typeArguments, var owner) -> {
                try {
                    var rawClass = Class.forName(rawName, true, classLoader);
                    var typeArgs = typeArguments.stream()
                            .map(type -> reflectTypeOf(type, classLoader))
                            .toArray(Type[]::new);
                    // The owner as the JDK gives it: the parameterized enclosing type, else the declaring class
                    // (BUG-20261118-46)
                    yield new ResolvedParameterizedType(rawClass, typeArgs,
                            owner != null ? reflectTypeOf(owner, classLoader) : rawClass.getDeclaringClass());
                } catch (ClassNotFoundException _) {
                    yield Object.class;
                }
            }
            case TypeInfo.TypeVariable(var name, var typeBounds) -> {
                var bounds = typeBounds.stream()
                        .map(type -> reflectTypeOf(type, classLoader))
                        .filter(t -> t != Object.class)
                        .toArray(Type[]::new);
                yield new ResolvedTypeVariable(name, bounds.length > 0 ? bounds : new Type[]{Object.class});
            }
            case TypeInfo.WildcardType(var upperBound, var lowerBound) -> {
                var upper = upperBound != null ? reflectTypeOf(upperBound, classLoader) : Object.class;
                var lower = lowerBound != null ? reflectTypeOf(lowerBound, classLoader) : null;
                yield new ResolvedWildcardType(
                        new Type[]{upper},
                        lower != null ? new Type[]{lower} : new Type[0]);
            }
            case TypeInfo.ArrayType(var componentType, _) -> {
                var component = reflectTypeOf(componentType, classLoader);
                if (component instanceof Class<?> cc) {
                    yield java.lang.reflect.Array.newInstance(cc, 0).getClass();
                }
                yield new ResolvedGenericArrayType(component);
            }
            default -> Object.class;
        };
    }

    private static final class ResolvedParameterizedType implements java.lang.reflect.ParameterizedType {
        private final Class<?> rawType;
        private final Type[] typeArguments;
        private final Type ownerType;
        ResolvedParameterizedType(Class<?> rawType, Type[] typeArguments, Type ownerType) {
            this.rawType = rawType;
            this.typeArguments = typeArguments;
            this.ownerType = ownerType;
        }
        @Override public Type[] getActualTypeArguments() { return typeArguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return ownerType; }
        @Override public boolean equals(Object o) {
            return o instanceof java.lang.reflect.ParameterizedType other
                    && rawType.equals(other.getRawType())
                    && java.util.Arrays.equals(typeArguments, other.getActualTypeArguments())
                    && java.util.Objects.equals(ownerType, other.getOwnerType());
        }
        @Override public int hashCode() {
            // The JDK's ParameterizedTypeImpl formula, so that a type equal to the JDK's hashes alike
            return java.util.Arrays.hashCode(typeArguments) ^ java.util.Objects.hashCode(ownerType)
                    ^ rawType.hashCode();
        }
        @Override public String toString() {
            var sb = new StringBuilder(rawType.getName());
            if (typeArguments.length > 0) {
                sb.append('<');
                for (int i = 0; i < typeArguments.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeArguments[i].getTypeName());
                }
                sb.append('>');
            }
            return sb.toString();
        }
    }

    private record ResolvedWildcardType(Type[] upperBounds, Type[] lowerBounds)
            implements java.lang.reflect.WildcardType {
        @Override public Type[] getUpperBounds() { return upperBounds.clone(); }
        @Override public Type[] getLowerBounds() { return lowerBounds.clone(); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof java.lang.reflect.WildcardType other)) return false;
            return java.util.Arrays.equals(upperBounds, other.getUpperBounds())
                    && java.util.Arrays.equals(lowerBounds, other.getLowerBounds());
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(upperBounds) ^ java.util.Arrays.hashCode(lowerBounds);
        }
        @Override public String toString() {
            if (lowerBounds.length > 0) {
                return "? super " + lowerBounds[0].getTypeName();
            }
            if (upperBounds.length == 1 && upperBounds[0] == Object.class) return "?";
            return "? extends " + upperBounds[0].getTypeName();
        }
    }

    @SuppressWarnings("unchecked")
    private static final class ResolvedTypeVariable implements java.lang.reflect.TypeVariable<java.lang.reflect.GenericDeclaration> {
        private final String tvName;
        private final Type[] tvBounds;
        ResolvedTypeVariable(String name, Type[] bounds) { this.tvName = name; this.tvBounds = bounds; }
        @Override public Type[] getBounds() { return tvBounds.clone(); }
        @Override public java.lang.reflect.GenericDeclaration getGenericDeclaration() { return Object.class; }
        @Override public String getName() { return tvName; }
        @Override public java.lang.reflect.AnnotatedType[] getAnnotatedBounds() { return new java.lang.reflect.AnnotatedType[0]; }
        @Override public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> c) { return null; }
        @Override public java.lang.annotation.Annotation[] getAnnotations() { return new java.lang.annotation.Annotation[0]; }
        @Override public java.lang.annotation.Annotation[] getDeclaredAnnotations() { return new java.lang.annotation.Annotation[0]; }
    }

    private record ResolvedGenericArrayType(Type componentType)
            implements java.lang.reflect.GenericArrayType {
        @Override public Type getGenericComponentType() { return componentType; }
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        return QualifierUtils.toAnnotations(new java.util.LinkedHashSet<>(descriptor.qualifiers()), null, classLoader,
                dispatcher.annotationTypes());
    }

    @Override
    public Reception getReception() {
        return switch (descriptor.reception()) {
            case "IF_EXISTS" -> Reception.IF_EXISTS;
            default -> Reception.ALWAYS;
        };
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        return switch (descriptor.transactionPhase()) {
            case "BEFORE_COMPLETION" -> TransactionPhase.BEFORE_COMPLETION;
            case "AFTER_COMPLETION" -> TransactionPhase.AFTER_COMPLETION;
            case "AFTER_FAILURE" -> TransactionPhase.AFTER_FAILURE;
            case "AFTER_SUCCESS" -> TransactionPhase.AFTER_SUCCESS;
            default -> TransactionPhase.IN_PROGRESS;
        };
    }

    @Override
    public int getPriority() {
        return descriptor.priority();
    }

    @Override
    public boolean isAsync() {
        return descriptor.async();
    }

    @Override
    public void notify(T event) {
        // CDI spec: notify() directly invokes THIS observer, not all matching observers
        dispatcher.invokeObserverDirect(descriptor, event);
    }

    @Override
    public void notify(EventContext<T> eventContext) {
        notify(eventContext.getEvent());
    }
}
