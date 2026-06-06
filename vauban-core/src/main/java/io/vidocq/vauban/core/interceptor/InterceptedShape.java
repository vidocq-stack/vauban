package io.vidocq.vauban.core.interceptor;

import java.util.List;

/**
 * Neutral shape for an intercepted bean subclass.
 *
 * <p>{@code beanBinaryName} is the binary name of the bean (e.g. {@code "com.example.MyService"}).
 * The generated subclass will be named {@code beanBinaryName + "$$Intercepted"}.
 */
public record InterceptedShape(
        String beanBinaryName,
        List<CtorShape> constructors,
        List<MethodShape> methods) {

    public InterceptedShape {
        java.util.Objects.requireNonNull(beanBinaryName, "beanBinaryName");
        constructors = List.copyOf(constructors);
        methods = List.copyOf(methods);
    }
}
