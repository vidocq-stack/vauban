/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package it.beanb;

import io.vidocq.vauban.classloader.Launch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * #42 Stage 4, end to end on real named modules: the launcher re-layers {@code it.liba} and
 * {@code it.beanb} under a Vauban class loader and re-enters the application from inside the
 * layer. There, every produced type whose proxy must live in {@code it.liba} gets it
 * <em>placed</em> — defined into the package by the loader that owns it — with the module left
 * exactly as its author shipped it: no {@code opens}, no agent, no rewritten jar.
 *
 * <p>The assertions read a report produced inside the layer ({@link LayerProbe}); this test never
 * touches a layer class, because it could not — the layer's {@code it.liba.Pooled} is not this
 * layer's {@code it.liba.Pooled}. That identity gap is the reason the launcher exists.
 */
@DisplayName("#42 Stage 4 — placed proxies through the launcher, on the module path")
class VaubanLayerPlacementTest {

    @Test
    @DisplayName("in a Vauban layer, in-package proxies are placed with zero opens and no double construction")
    void placedProxiesInsideAVaubanLayer() throws Throwable {
        // Surefire puts JUnit on the module path too: keep it in the boot layer, it is not the app.
        System.setProperty(Launch.KEEP_PROPERTY, "org.junit,org.apiguardian,org.opentest4j");
        System.clearProperty(LaunchedMain.RESULT_PROPERTY);
        assertNull(System.getProperty("vauban.opens.auto"), "the boot-time open must stay off: nothing to open");
        try {
            Launch.run("it.beanb/it.beanb.LaunchedMain", new String[0]);
        } finally {
            System.clearProperty(Launch.KEEP_PROPERTY);
        }
        var report = System.getProperty(LaunchedMain.RESULT_PROPERTY);
        assertNotNull(report, "the launched main must have run inside the layer and reported");
        var r = parse(report);

        assertEquals("false", r.get("probeInBootLayer"), "the application ran inside the layer");

        // Gadget: package-private member → proxy placed into it.liba by the layer loader.
        assertEquals("it.liba.Gadget_ClientProxy", r.get("gadgetProxy"),
                "the co-located proxy, not a cross-package one and not a reflective fallback");
        assertEquals("it.liba", r.get("gadgetModule"), "the placed class is a member of the library module");
        assertEquals("VaubanClassLoader", r.get("gadgetLoader"), "defined by the loader that owns the package");
        assertEquals("gadget", r.get("gadgetRun"));

        // Pooled: the sibling really invokes the package-private member on the proxy — forwarded.
        assertEquals("pooled:real", r.get("pooledRun"));
        assertEquals("internal:real", r.get("forward"),
                "PooledPool.forward() calls Pooled.internalOnly() on the proxy; an un-forwarded "
                        + "call would read the proxy's own null token and answer internal:null");
        assertEquals("false", r.get("libOpenedToCore"), "it.liba was never opened to the container");

        // Handle: no accessible constructor — the placed proxy chains the woven entry constructor.
        assertEquals("handle", r.get("handleId"));

        // vauban#24 for a third-party type: exactly one construction, and none for the proxy.
        assertEquals("0", r.get("constructionsAfterSelect"),
                "creating the client proxy must not run the third-party constructor");
        assertEquals("1", r.get("constructionsAfterFirstUse"));
        assertEquals("1", r.get("constructionsAfterSecondUse"), "@ApplicationScoped: exactly one instance");
    }

    private static Map<String, String> parse(String report) {
        var map = new LinkedHashMap<String, String>();
        for (var pair : report.split(";")) {
            var eq = pair.indexOf('=');
            map.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return map;
    }
}
