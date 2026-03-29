package fr.vidocq.vauban.core.event;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
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
        dispatcher.fire(event);
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event) {
        @SuppressWarnings("unchecked")
        var stage = (CompletionStage<U>) dispatcher.fireAsync(event);
        return stage;
    }

    @Override
    public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
        return fireAsync(event); // ignore options for now
    }

    @Override
    public Event<T> select(Annotation... qualifiers) {
        return this; // simplified — ignoring qualifier filtering for now
    }

    @Override
    public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
        @SuppressWarnings("unchecked")
        var result = (Event<U>) this;
        return result;
    }

    @Override
    public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        @SuppressWarnings("unchecked")
        var result = (Event<U>) this;
        return result;
    }
}
