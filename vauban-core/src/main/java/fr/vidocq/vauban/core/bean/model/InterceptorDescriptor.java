package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.DotName;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

/**
 * Description of a discovered CDI interceptor.
 */
public record InterceptorDescriptor(
        DotName interceptorClass,
        Set<DotName> bindings,        // @InterceptorBinding annotations on the interceptor
        String aroundInvokeMethod,    // method name annotated with @AroundInvoke (null if none)
        int priority,                 // @Priority value
        List<Annotation> bindingAnnotations  // actual annotation instances for member comparison
) {

    public InterceptorDescriptor(DotName interceptorClass, Set<DotName> bindings,
            String aroundInvokeMethod, int priority) {
        this(interceptorClass, bindings, aroundInvokeMethod, priority, List.of());
    }

    public InterceptorDescriptor {
        bindings = Set.copyOf(bindings);
        bindingAnnotations = List.copyOf(bindingAnnotations);
    }
}
