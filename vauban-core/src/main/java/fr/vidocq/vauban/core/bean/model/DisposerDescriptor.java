package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;

import java.util.Set;

/**
 * Describes a disposer method discovered during bean scanning.
 * A disposer method has exactly one parameter annotated with {@code @Disposes}.
 *
 * @param declaringClass the class that declares the disposer method
 * @param methodName     the method name
 * @param disposedType   the type of the parameter annotated with @Disposes
 * @param qualifiers     qualifier annotations on the disposed parameter
 * @param parameterIndex index of the @Disposes parameter in the method signature
 */
public record DisposerDescriptor(
        DotName declaringClass,
        String methodName,
        TypeInfo disposedType,
        Set<QualifierInstance> qualifiers,
        int parameterIndex
) {
    public DisposerDescriptor {
        qualifiers = Set.copyOf(qualifiers);
    }
}
