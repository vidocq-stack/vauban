package io.vidocq.vauban.core.interceptor;

import java.util.List;

/**
 * Neutral shape for a method that will be intercepted or bridged.
 * Carries name, return type, and parameter types as {@link TypeRef} — no {@link java.lang.reflect.Method}.
 */
public record MethodShape(String name, TypeRef returnType, List<TypeRef> params) {

    public MethodShape {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(returnType, "returnType");
        params = List.copyOf(params);
    }
}
