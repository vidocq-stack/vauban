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
        String aroundConstructMethod, // method name annotated with @AroundConstruct (null if none)
        int priority,                 // @Priority value
        boolean enabled,              // true if @Priority annotation is present
        List<Annotation> bindingAnnotations  // actual annotation instances for member comparison
) {

    public InterceptorDescriptor(DotName interceptorClass, Set<DotName> bindings,
            String aroundInvokeMethod, String aroundConstructMethod, int priority) {
        this(interceptorClass, bindings, aroundInvokeMethod, aroundConstructMethod, priority, priority > 0, List.of());
    }

    public InterceptorDescriptor(DotName interceptorClass, Set<DotName> bindings,
            String aroundInvokeMethod, int priority) {
        this(interceptorClass, bindings, aroundInvokeMethod, null, priority, priority > 0, List.of());
    }

    public InterceptorDescriptor {
        bindings = Set.copyOf(bindings);
        bindingAnnotations = List.copyOf(bindingAnnotations);
    }
}
