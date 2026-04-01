package fr.vidocq.vauban.core;

/**
 * Factory for creating bean instances without reflection.
 * Generated at build time.
 */
public interface BeanFactory<T> {
    T create();

    default T create(fr.vidocq.vauban.core.interceptor.VaubanInvocationContext ctx) {
        return create();
    }
}
