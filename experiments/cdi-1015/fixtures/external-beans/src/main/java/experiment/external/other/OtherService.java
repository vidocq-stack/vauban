/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package experiment.external.other;

import experiment.external.beans.FallbackState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Fallback;

/** A second bean package distinguishes a scoped grant from a library-wide grant. */
@ApplicationScoped
public class OtherService {
    @Inject
    private FallbackState state;

    private int recoveries;

    public OtherService() {
    }

    @Fallback(fallbackMethod = "privateRecovery")
    public String call(String name) {
        throw new IllegalStateException("boom");
    }

    private String privateRecovery(String name) {
        return "other fallback for " + name + ":" + state.origin() + ":" + ++recoveries;
    }
}
