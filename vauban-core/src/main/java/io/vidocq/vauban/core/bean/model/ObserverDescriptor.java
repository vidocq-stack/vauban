package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Describes an observer method discovered during bean scanning.
 *
 * @param declaringClass the class that declares the observer method
 * @param methodName     the method name
 * @param eventType      the type of the parameter annotated with @Observes/@ObservesAsync
 * @param qualifiers     qualifier annotations on the observed parameter
 * @param async          true if @ObservesAsync, false if @Observes
 * @param priority       the priority (from @Priority annotation, 0 by default)
 * @param syntheticInvoker if non-null, this observer is synthetic and invoked via this callback (event, qualifiers)
 */
public record ObserverDescriptor(
        DotName declaringClass,
        String methodName,
        TypeInfo eventType,
        List<QualifierInstance> qualifiers,
        boolean async,
        int priority,
        String reception,
        String transactionPhase,
        BiConsumer<Object, java.lang.annotation.Annotation[]> syntheticInvoker
) {
    public ObserverDescriptor {
        qualifiers = List.copyOf(qualifiers);
        if (reception == null) reception = "ALWAYS";
        if (transactionPhase == null) transactionPhase = "IN_PROGRESS";
    }

    public ObserverDescriptor(DotName declaringClass, String methodName, TypeInfo eventType,
            List<QualifierInstance> qualifiers, boolean async, int priority,
            String reception, String transactionPhase) {
        this(declaringClass, methodName, eventType, qualifiers, async, priority,
                reception, transactionPhase, null);
    }

    public ObserverDescriptor(DotName declaringClass, String methodName, TypeInfo eventType,
            List<QualifierInstance> qualifiers, boolean async, int priority) {
        this(declaringClass, methodName, eventType, qualifiers, async, priority, "ALWAYS", "IN_PROGRESS");
    }

    public boolean isSynthetic() {
        return syntheticInvoker != null;
    }
}
