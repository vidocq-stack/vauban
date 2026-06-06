package io.vidocq.vauban.core.interceptor.fixtures;

/**
 * Superclass for {@link GoldenBean} — provides an inherited method so that the
 * {@code getMethods()} inherited path is exercised in {@link
 * io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator#fromClass(Class)}.
 */
public class GoldenBeanSuper {

    /** Inherited method — must be picked up by the getMethods() pass. */
    public String inheritedMethod() {
        return "from-super";
    }
}
