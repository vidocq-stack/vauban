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

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CompletionStage;

/**
 * Implementation of {@link Event} backed by {@link EventDispatcher}.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class EventImpl<T> implements Event<T> {

    private static final String MSG_EVENT_NULL = "Event object must not be null";

    private final EventDispatcher dispatcher;
    private final Annotation[] qualifiers;
    private final jakarta.enterprise.inject.spi.InjectionPoint injectionPoint;
    private final java.lang.reflect.Type selectedType;
    // The qualifiers of this Event, reduced to their keys once: an injected Event fires many times.
    @SuppressWarnings("java:S3077")
    private volatile java.util.Set<io.vidocq.vauban.core.annotation.AnnotationKey> keys;

    public EventImpl(EventDispatcher dispatcher) {
        this(dispatcher, new Annotation[0], null, null);
    }

    public EventImpl(EventDispatcher dispatcher, Annotation[] qualifiers) {
        this(dispatcher, qualifiers, null, null);
    }

    public EventImpl(EventDispatcher dispatcher, Annotation[] qualifiers,
            jakarta.enterprise.inject.spi.InjectionPoint injectionPoint) {
        this(dispatcher, qualifiers, injectionPoint, null);
    }

    public EventImpl(EventDispatcher dispatcher, Annotation[] qualifiers,
            jakarta.enterprise.inject.spi.InjectionPoint injectionPoint,
            java.lang.reflect.Type selectedType) {
        this.dispatcher = dispatcher;
        this.qualifiers = qualifiers;
        this.injectionPoint = injectionPoint;
        if (selectedType != null) {
            this.selectedType = selectedType;
        } else if (injectionPoint != null) {
            var ipType = injectionPoint.getType();
            if (ipType instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getRawType() == jakarta.enterprise.event.Event.class
                    && pt.getActualTypeArguments().length > 0) {
                this.selectedType = pt.getActualTypeArguments()[0];
            } else {
                this.selectedType = null;
            }
        } else {
            this.selectedType = null;
        }
    }

    public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() {
        return injectionPoint;
    }

    /** This Event's qualifiers as matching keys, converted on first use and kept. */
    private java.util.Set<io.vidocq.vauban.core.annotation.AnnotationKey> keys() {
        var converted = keys;
        if (converted == null) {
            converted = dispatcher.keysOf(qualifiers);
            keys = converted;
        }
        return converted;
    }

    @Override
    public void fire(T event) {
        if (event == null) {
            throw new IllegalArgumentException(MSG_EVENT_NULL);
        }
        // CDI spec: if the selected event type contains unresolvable type variables, throw IAE
        if (selectedType != null && containsTypeVariable(selectedType)) {
            throw new IllegalArgumentException(
                    "Event type contains unresolvable type variable: " + selectedType);
        }
        // CDI spec: if runtime type has unresolvable type variables that aren't resolved by selected type
        if (event.getClass().getTypeParameters().length > 0
                && !(selectedType instanceof java.lang.reflect.ParameterizedType)) {
            throw new IllegalArgumentException(
                    "Event type contains unresolvable type variable: " + event.getClass());
        }
        dispatcher.fire(event, selectedType, injectionPoint, keys(), qualifiers);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        if (event == null) {
            throw new IllegalArgumentException(MSG_EVENT_NULL);
        }
        return dispatcher.fireAsync(event, null, keys(), qualifiers);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        if (event == null) {
            throw new IllegalArgumentException(MSG_EVENT_NULL);
        }
        var executor = options != null ? options.getExecutor() : null;
        return dispatcher.fireAsync(event, executor, keys(), qualifiers);
    }

    @Override
    public Event<T> select(Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        return new EventImpl<>(dispatcher, combineQualifiers(this.qualifiers, newQualifiers), injectionPoint, this.selectedType);
    }

    @Override
    public <U extends T> Event<U> select(Class<U> subtype, Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        if (subtype == null) {
            throw new IllegalArgumentException("Subtype must not be null");
        }
        return new EventImpl<>(dispatcher, combineQualifiers(this.qualifiers, newQualifiers), injectionPoint, subtype);
    }

    @Override
    public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        if (subtype == null) {
            throw new IllegalArgumentException("Subtype must not be null");
        }
        var type = subtype.getType();
        if (containsTypeVariable(type)) {
            throw new IllegalArgumentException("TypeVariable is not a legal event type");
        }
        return new EventImpl<>(dispatcher, combineQualifiers(this.qualifiers, newQualifiers), injectionPoint, type);
    }

    private static boolean containsTypeVariable(java.lang.reflect.Type type) {
        if (type instanceof TypeVariable<?>) return true;
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) return true;
            }
        }
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            return containsTypeVariable(gat.getGenericComponentType());
        }
        if (type instanceof java.lang.reflect.WildcardType wt) {
            for (var bound : wt.getUpperBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
            for (var bound : wt.getLowerBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
        }
        return false;
    }

    private static Annotation[] combineQualifiers(Annotation[] existing, Annotation[] additional) {
        if (additional == null || additional.length == 0) return existing;
        if (existing.length == 0) return additional;
        var combined = Arrays.copyOf(existing, existing.length + additional.length);
        System.arraycopy(additional, 0, combined, existing.length, additional.length);
        return combined;
    }

    private static void validateQualifiers(Annotation... qualifiers) {
        if (qualifiers == null) return;
        var seen = new HashSet<Class<?>>();
        for (var q : qualifiers) {
            if (q == null) {
                throw new IllegalArgumentException("Qualifier must not be null");
            }
            if (!q.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    && q.annotationType() != jakarta.enterprise.inject.Default.class
                    && q.annotationType() != jakarta.enterprise.inject.Any.class
                    && q.annotationType() != jakarta.inject.Named.class
                    && !io.vidocq.vauban.core.container.VaubanBeanManager.isCustomQualifier(q.annotationType())) {
                throw new IllegalArgumentException(
                        q.annotationType().getName() + " is not a qualifier");
            }
            // CDI spec: qualifier must have @Retention(RUNTIME)
            var retention = q.annotationType().getAnnotation(java.lang.annotation.Retention.class);
            if (retention == null || retention.value() != java.lang.annotation.RetentionPolicy.RUNTIME) {
                throw new IllegalArgumentException(
                        q.annotationType().getName() + " does not have @Retention(RUNTIME)");
            }
            // Allow duplicate qualifier types if the annotation is @Repeatable
            if (!seen.add(q.annotationType())
                    && !q.annotationType().isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                throw new IllegalArgumentException(
                        "Duplicate qualifier: " + q.annotationType().getName());
            }
        }
    }
}
