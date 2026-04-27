package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;
import java.util.List;

public final class VaubanBceObserverInfo implements ObserverInfo {

    private final ObserverDescriptor descriptor;
    private final IndexLookup lookup;

    public VaubanBceObserverInfo(ObserverDescriptor descriptor, IndexLookup lookup) {
        this.descriptor = descriptor;
        this.lookup = lookup;
    }

    @Override
    public Type eventType() {
        return TypeMapper.map(descriptor.eventType(), lookup);
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        return List.of();
    }

    @Override
    public ClassInfo declaringClass() {
        var indexClass = lookup.getClass(descriptor.declaringClass()).orElse(null);
        if (indexClass != null) {
            return new VaubanClassInfo(indexClass, lookup);
        }
        return null;
    }

    @Override
    public MethodInfo observerMethod() {
        return null;
    }

    @Override
    public ParameterInfo eventParameter() {
        return null;
    }

    @Override
    public BeanInfo bean() {
        return null;
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }

    @Override
    public int priority() {
        return descriptor.priority();
    }

    @Override
    public boolean isAsync() {
        return descriptor.async();
    }

    @Override
    public Reception reception() {
        return Reception.valueOf(descriptor.reception());
    }

    @Override
    public TransactionPhase transactionPhase() {
        return TransactionPhase.valueOf(descriptor.transactionPhase());
    }
}
