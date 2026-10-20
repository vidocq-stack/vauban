/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package experiment.external.beans;

import jakarta.enterprise.context.Dependent;

/** Injected state makes successful calls depend on CDI construction and injection. */
@Dependent
public class FallbackState {
    private final String origin = "external";

    public FallbackState() {
    }

    public String origin() {
        return origin;
    }
}
