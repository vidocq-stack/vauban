package fr.vidocq.vauban.core.event;

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
public final class EventImpl<T> implements Event<T> {

    private final EventDispatcher dispatcher;
    private final Annotation[] qualifiers;

    public EventImpl(EventDispatcher dispatcher) {
        this(dispatcher, new Annotation[0]);
    }

    private EventImpl(EventDispatcher dispatcher, Annotation[] qualifiers) {
        this.dispatcher = dispatcher;
        this.qualifiers = qualifiers;
    }

    @Override
    public void fire(T event) {
        if (event == null) {
            throw new IllegalArgumentException("Event object must not be null");
        }
        dispatcher.fire(event, qualifiers);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        if (event == null) {
            throw new IllegalArgumentException("Event object must not be null");
        }
        @SuppressWarnings("unchecked")
        var stage = (CompletionStage<U>) dispatcher.fireAsync(event, qualifiers);
        return stage;
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        return fireAsync(event);
    }

    @Override
    public Event<T> select(Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        return new EventImpl<>(dispatcher, combineQualifiers(this.qualifiers, newQualifiers));
    }

    @Override
    public <U extends T> Event<U> select(Class<U> subtype, Annotation... newQualifiers) {
        validateQualifiers(newQualifiers);
        if (subtype == null) {
            throw new IllegalArgumentException("Subtype must not be null");
        }
        return new EventImpl<>(dispatcher, combineQualifiers(this.qualifiers, newQualifiers));
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
        return new EventImpl<>(dispatcher, combineQualifiers(this.qualifiers, newQualifiers));
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
                    && q.annotationType() != jakarta.inject.Named.class) {
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
            if (!seen.add(q.annotationType())) {
                if (!q.annotationType().isAnnotationPresent(java.lang.annotation.Repeatable.class)) {
                    throw new IllegalArgumentException(
                            "Duplicate qualifier: " + q.annotationType().getName());
                }
            }
        }
    }
}
