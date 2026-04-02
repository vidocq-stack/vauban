package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.invoke.InvokerBuilder;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds a {@link VaubanInvoker} from a resolved Method.
 */
public final class VaubanInvokerBuilder implements InvokerBuilder<InvokerInfo> {

    private final Method method;
    private final Class<?> beanClass;
    private boolean instanceLookup;
    private final Set<Integer> argumentLookups = new HashSet<>();

    public VaubanInvokerBuilder(Method method, Class<?> beanClass) {
        this.method = method;
        this.beanClass = beanClass;
    }

    @Override
    public InvokerBuilder<InvokerInfo> withInstanceLookup() {
        this.instanceLookup = true;
        return this;
    }

    @Override
    public InvokerBuilder<InvokerInfo> withArgumentLookup(int position) {
        if (position < 0 || position >= method.getParameterCount()) {
            throw new IllegalArgumentException(
                    "Argument position " + position + " is out of bounds for method "
                            + method.getName() + " with " + method.getParameterCount() + " parameters");
        }
        argumentLookups.add(position);
        return this;
    }

    @Override
    public InvokerInfo build() {
        return new VaubanInvoker(method, beanClass, instanceLookup, argumentLookups);
    }
}
