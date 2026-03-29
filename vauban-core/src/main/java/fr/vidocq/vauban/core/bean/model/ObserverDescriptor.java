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
        int priority
) {
    public ObserverDescriptor {
        qualifiers = List.copyOf(qualifiers);
    }
}
