package io.vidocq.vauban.core.interceptor;

import java.util.List;

/**
 * Neutral shape for a constructor parameter list.
 */
public record CtorShape(List<TypeRef> params) {

    public CtorShape {
        params = List.copyOf(params);
    }
}
