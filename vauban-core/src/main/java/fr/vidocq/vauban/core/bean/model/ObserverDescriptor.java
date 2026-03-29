package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.util.List;

/**
 * Describes an observer method discovered during bean scanning.
 *
 * @param declaringClass the class that declares the observer method
 * @param methodName     the method name
 * @param eventType      the type of the parameter annotated with @Observes/@ObservesAsync
 * @param qualifiers     qualifier annotations on the observed parameter
 * @param async          true if @ObservesAsync, false if @Observes
 * @param priority       the priority (from @Priority annotation, 0 by default)
 */
public record ObserverDescriptor(
        DotName declaringClass,
        String methodName,
        TypeInfo eventType,
        List<QualifierInstance> qualifiers,
        boolean async,
        int priority,
        String reception,       // "ALWAYS" or "IF_EXISTS"
        String transactionPhase // "IN_PROGRESS", "BEFORE_COMPLETION", "AFTER_COMPLETION", "AFTER_FAILURE", "AFTER_SUCCESS"
) {
    public ObserverDescriptor {
        qualifiers = List.copyOf(qualifiers);
        if (reception == null) reception = "ALWAYS";
        if (transactionPhase == null) transactionPhase = "IN_PROGRESS";
    }

    /**
     * Backward-compatible constructor without reception/transactionPhase.
     */
    public ObserverDescriptor(DotName declaringClass, String methodName, TypeInfo eventType,
            List<QualifierInstance> qualifiers, boolean async, int priority) {
        this(declaringClass, methodName, eventType, qualifiers, async, priority, "ALWAYS", "IN_PROGRESS");
    }
}
