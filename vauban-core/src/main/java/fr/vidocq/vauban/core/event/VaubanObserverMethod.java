package fr.vidocq.vauban.core.event;

import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
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

    public VaubanObserverMethod(ObserverDescriptor descriptor, EventDispatcher dispatcher) {
        this.descriptor = descriptor;
        this.dispatcher = dispatcher;
    }

    @Override
    public Class<?> getBeanClass() {
        try {
            return Class.forName(descriptor.declaringClass().value());
        } catch (ClassNotFoundException e) {
            return Object.class;
        }
    }

    @Override
    public Type getObservedType() {
        if (descriptor.eventType() instanceof TypeInfo.ClassType ct) {
            try {
                return Class.forName(ct.name().value());
            } catch (ClassNotFoundException e) {
                return Object.class;
            }
        }
        return Object.class;
    }

    @Override
    public Set<Annotation> getObservedQualifiers() {
        return Set.of();
    }

    @Override
    public Reception getReception() {
        return Reception.ALWAYS;
    }

    @Override
    public TransactionPhase getTransactionPhase() {
        return TransactionPhase.IN_PROGRESS;
    }

    @Override
    public boolean isAsync() {
        return descriptor.async();
    }

    @Override
    public void notify(T event) {
        dispatcher.fire(event);
    }

    @Override
    public void notify(EventContext<T> eventContext) {
        notify(eventContext.getEvent());
    }
}
