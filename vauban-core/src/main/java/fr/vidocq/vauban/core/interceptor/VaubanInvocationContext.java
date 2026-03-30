package fr.vidocq.vauban.core.interceptor;

import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vauban implementation of {@link InvocationContext}.
 * Chains interceptors and ultimately invokes the target method.
 */
public final class VaubanInvocationContext implements InvocationContext {

    private final Object target;
    private final Method method;
    private Object[] parameters;
    private final List<InterceptorInvocation> chain;
    private int currentIndex = -1;
    private final Map<String, Object> contextData = new HashMap<>();
    private final TargetInvoker targetInvoker;

    /**
     * Functional interface for the final target invocation (to support super calls).
     */
    @FunctionalInterface
    public interface TargetInvoker {
        Object invoke(Object target, Object[] params) throws Exception;
    }

    public VaubanInvocationContext(Object target, Method method, Object[] parameters,
                                   List<InterceptorInvocation> chain) {
        this(target, method, parameters, chain, null);
    }

    public VaubanInvocationContext(Object target, Method method, Object[] parameters,
                                   List<InterceptorInvocation> chain, TargetInvoker targetInvoker) {
        this.target = target;
        this.method = method;
        this.parameters = parameters != null ? parameters.clone() : new Object[0];
        this.chain = chain;
        this.targetInvoker = targetInvoker;
    }

    @Override
    public Object getTarget() {
        return target;
    }

    @Override
    public Object getTimer() {
        return null;
    }

    @Override
    public Method getMethod() {
        return method;
    }

    @Override
    public Constructor<?> getConstructor() {
        return null;
    }

    @Override
    public Object[] getParameters() {
        return parameters.clone();
    }

    @Override
    public void setParameters(Object[] params) {
        this.parameters = params.clone();
    }

    @Override
    public Map<String, Object> getContextData() {
        return contextData;
    }

    @Override
    public Object proceed() throws Exception {
        currentIndex++;
        if (currentIndex < chain.size()) {
            // Call next interceptor
            var invocation = chain.get(currentIndex);
            return invocation.invoke(this);
        } else {
            // End of chain — call the actual method
            if (targetInvoker != null) {
                return targetInvoker.invoke(target, parameters);
            }
            return method.invoke(target, parameters);
        }
    }

    /**
     * Represents one interceptor in the chain.
     */
    public record InterceptorInvocation(Object interceptorInstance, Method aroundInvokeMethod) {
        public Object invoke(InvocationContext ctx) throws Exception {
            return aroundInvokeMethod.invoke(interceptorInstance, ctx);
        }
    }
}
