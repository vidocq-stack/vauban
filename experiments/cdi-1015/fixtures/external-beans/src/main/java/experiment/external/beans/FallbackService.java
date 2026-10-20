/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package experiment.external.beans;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Fallback;

/** An ordinary external bean library using only standard CDI and FT APIs. */
@ApplicationScoped
public class FallbackService {
    @Inject
    private FallbackState state;

    private int recoveries;

    public FallbackService() {
    }

    @Fallback(fallbackMethod = "privateRecovery")
    public String callWithPrivateFallback(String name) {
        throw new IllegalStateException("boom");
    }

    @Fallback(fallbackMethod = "packageRecovery")
    public String callWithPackagePrivateFallback(String name) {
        throw new IllegalStateException("boom");
    }

    private String privateRecovery(String name) {
        return "private fallback for " + name + ":" + state.origin() + ":" + ++recoveries;
    }

    String packageRecovery(String name) {
        return "package-private fallback for " + name + ":" + state.origin() + ":" + ++recoveries;
    }
}
