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
import io.vidocq.vauban.core.container.ExperimentalSeBootstrap;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import java.util.Set;

/** Standard SE provider selection with experimental access preparation at startup. */
public final class SeExample {
    public static void main(String[] args) {
        try (var container = initializer().initialize()) {
            var service = container.select(FallbackService.class).get();
            System.out.println(service.callWithPrivateFallback("duke"));
        }
    }

    static SeContainerInitializer initializer() {
        Module ft = ModuleLayer.boot().findModule("io.vidocq.heisenberg.core").orElseThrow();
        var initializer = SeContainerInitializer.newInstance();

        // The application authorizes FT; the adapter opens eligible packages at initialize().
        return ExperimentalSeBootstrap.configure(initializer, Set.of(ft))
                .disableDiscovery()
                .addBeanClasses(
                        FallbackService.class, FallbackState.class,
                        HeisenbergExtension.class, FaultToleranceInterceptor.class,
                        StateRegistryBean.class, BulkheadStateRegistryBean.class);
    }
}
