package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.DotName;

import java.util.Set;

/**
 * Description of a discovered CDI interceptor.
 */
public record InterceptorDescriptor(
        DotName interceptorClass,
        Set<DotName> bindings,        // @InterceptorBinding annotations on the interceptor
        String aroundInvokeMethod,    // method name annotated with @AroundInvoke (null if none)
        int priority                  // @Priority value
) {

    public InterceptorDescriptor {
        bindings = Set.copyOf(bindings);
    }
}
