/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package experiment.external.app;

import experiment.external.beans.FallbackService;
import experiment.external.beans.FallbackState;
import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import jakarta.enterprise.inject.se.SeContainerInitializer;

/** Proposed SE authorization API; the container prepares access at startup. */
public final class SeExample {
    public static void main(String[] args) {
        try (var container = initializer().initialize()) {
            var service = container.select(FallbackService.class).get();
            System.out.println(service.callWithPrivateFallback("duke"));
        }
    }

    static SeContainerInitializer initializer() {
        Module ft = ModuleLayer.boot().findModule("io.vidocq.heisenberg.core").orElseThrow();
        return SeContainerInitializer.newInstance()
                .allowAccessFor(ft, FallbackService.class.getModule(), FallbackService.class.getPackageName())
                .disableDiscovery()
                .addBeanClasses(
                        FallbackService.class, FallbackState.class,
                        HeisenbergExtension.class, FaultToleranceInterceptor.class,
                        StateRegistryBean.class, BulkheadStateRegistryBean.class);
    }
}
