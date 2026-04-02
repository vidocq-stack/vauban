package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.invoke.Invoker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * Reflection-based {@link Invoker} that also implements {@link InvokerInfo}.
 * The TCK stores these as InvokerInfo[] but retrieves them as Invoker[] —
 * so one class must implement both interfaces.
 */
@SuppressWarnings("unchecked")
public final class VaubanInvoker implements Invoker<Object, Object>, InvokerInfo {

    private final Method method;
    private final Class<?> beanClass;
    private final boolean isStatic;
    private final boolean instanceLookup;
    private final Set<Integer> argumentLookups;

    public VaubanInvoker(Method method, Class<?> beanClass,
                         boolean instanceLookup, Set<Integer> argumentLookups) {
        this.method = method;
        this.beanClass = beanClass;
        this.isStatic = Modifier.isStatic(method.getModifiers());
        this.instanceLookup = instanceLookup;
        this.argumentLookups = Set.copyOf(argumentLookups);
        method.setAccessible(true);
    }

    @Override
    public Object invoke(Object instance, Object[] arguments) throws Exception {
        // Handle instance lookup
        if (instanceLookup) {
            instance = CDI.current().select(beanClass).get();
        }

        // Handle argument lookups
        if (!argumentLookups.isEmpty() && arguments != null) {
            var paramTypes = method.getParameterTypes();
            var genParamTypes = method.getGenericParameterTypes();
            arguments = arguments.clone(); // don't mutate caller's array
            for (int idx : argumentLookups) {
                var paramType = paramTypes[idx];
                // Check if param is Instance<T>
                if (jakarta.enterprise.inject.Instance.class.isAssignableFrom(paramType)) {
                    // Inject an Instance<X> where X is the type argument
                    var genType = genParamTypes[idx];
                    if (genType instanceof java.lang.reflect.ParameterizedType pt) {
                        var actualType = pt.getActualTypeArguments()[0];
                        if (actualType instanceof Class<?> cls) {
                            arguments[idx] = CDI.current().select(cls);
                        } else {
                            arguments[idx] = CDI.current().select(Object.class);
                        }
                    } else {
                        arguments[idx] = CDI.current().select(Object.class);
                    }
                } else {
                    arguments[idx] = CDI.current().select(paramType).get();
                }
            }
        }

        // CDI spec: excess arguments are silently ignored
        var paramCount = method.getParameterCount();
        if (arguments != null && arguments.length > paramCount) {
            var trimmed = new Object[paramCount];
            System.arraycopy(arguments, 0, trimmed, 0, paramCount);
            arguments = trimmed;
        }

        try {
            if (isStatic) {
                return method.invoke(null, arguments);
            }
            if (instance == null) {
                throw new RuntimeException("Cannot invoke instance method " + method.getName()
                        + " with null instance");
            }
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }
}
