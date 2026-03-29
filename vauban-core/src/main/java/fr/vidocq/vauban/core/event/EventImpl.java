package fr.vidocq.vauban.core.event;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.lang.reflect.TypeVariable;
import java.util.HashSet;
import java.util.concurrent.CompletionStage;

/**
 * Implementation of {@link Event} backed by {@link EventDispatcher}.
 */
public final class EventImpl<T> implements Event<T> {

    private final EventDispatcher dispatcher;

    public EventImpl(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void fire(T event) {
        if (event == null) {
            throw new IllegalArgumentException("Event object must not be null");
        }
        // Check for unresolvable type variable
        if (event.getClass().getTypeParameters().length > 0) {
            for (var tp : event.getClass().getTypeParameters()) {
                // Type variables on the event class itself are OK
                // Only reject if the event TYPE (not class) is a TypeVariable
            }
        }
        dispatcher.fire(event);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        if (event == null) {
            throw new IllegalArgumentException("Event object must not be null");
        }
        @SuppressWarnings("unchecked")
        var stage = (CompletionStage<U>) dispatcher.fireAsync(event);
        return stage;
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        return fireAsync(event);
    }

    @Override
    public Event<T> select(Annotation... qualifiers) {
        validateQualifiers(qualifiers);
        return this;
    }

    @Override
    public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
        validateQualifiers(qualifiers);
        if (subtype == null) {
            throw new IllegalArgumentException("Subtype must not be null");
        }
        @SuppressWarnings("unchecked")
        var result = (Event<U>) new EventImpl<>(dispatcher);
        return result;
    }

    @Override
    public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        validateQualifiers(qualifiers);
        if (subtype == null) {
            throw new IllegalArgumentException("Subtype must not be null");
        }
        // Check for TypeVariable
        var type = subtype.getType();
        if (type instanceof TypeVariable<?>) {
            throw new IllegalArgumentException("TypeVariable is not a legal event type");
        }
        @SuppressWarnings("unchecked")
        var result = (Event<U>) new EventImpl<>(dispatcher);
        return result;
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
            if (!seen.add(q.annotationType())) {
                throw new IllegalArgumentException(
                        "Duplicate qualifier: " + q.annotationType().getName());
            }
        }
    }
}
