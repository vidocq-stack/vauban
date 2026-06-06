package io.vidocq.vauban.core.container;

import io.vidocq.vauban.api.VaubanComponentProvider;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test {@link VaubanComponentProvider} registered as a service. It owns only
 * {@link ProvidedBean}: it records the call and returns a fresh instance for it, and
 * {@code null} for everything else (so other tests fall back to reflection unaffected).
 */
public class CountingComponentProvider implements VaubanComponentProvider {

    /** Class names this provider was asked to (and did) instantiate. */
    public static final Set<String> CREATED = ConcurrentHashMap.newKeySet();

    @Override
    public Object create(String className) {
        if (ProvidedBean.class.getName().equals(className)) {
            CREATED.add(className);
            return new ProvidedBean();
        }
        return null;
    }

    @Override
    public Object create(String className, Object[] args) {
        if (ProvidedConstructorBean.class.getName().equals(className)) {
            CREATED.add(className);
            return new ProvidedConstructorBean((ProvidedDependency) args[0]);
        }
        return create(className);
    }
}
