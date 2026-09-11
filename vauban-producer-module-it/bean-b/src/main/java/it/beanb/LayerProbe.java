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

import io.vidocq.vauban.core.container.VaubanContainer;
import it.liba.Counted;
import it.liba.Gadget;
import it.liba.Handle;
import it.liba.Pooled;
import it.liba.PooledPool;

/**
 * Runs <em>inside</em> a Vauban layer and reports what it observes as plain text, so a test left
 * in the boot layer can assert on values without ever touching a layer class — the two layers'
 * classes are distinct, and that identity gap is precisely why the entry point must run in the
 * layer (see {@code io.vidocq.vauban.classloader.Launch}).
 */
public final class LayerProbe {

    private LayerProbe() {}

    /** {@code key=value} pairs separated by {@code ;}. */
    public static String probe() {
        var out = new StringBuilder();
        try (VaubanContainer container =
                     VaubanContainer.builder().addBeanClass(FooProducer.class).build()) {
            var core = VaubanContainer.class.getModule();

            // A package-private member: the proxy must live in it.liba. Placed there?
            Gadget gadget = container.select(Gadget.class);
            put(out, "gadgetProxy", gadget.getClass().getName());
            put(out, "gadgetModule", String.valueOf(gadget.getClass().getModule().getName()));
            put(out, "gadgetLoader", gadget.getClass().getClassLoader().getClass().getSimpleName());
            put(out, "gadgetRun", gadget.run());

            // The dangerous shape: a sibling invokes the package-private member on the proxy.
            Pooled pooled = container.select(Pooled.class);
            put(out, "pooledRun", pooled.run());
            put(out, "forward", new PooledPool().forward(pooled));
            put(out, "libOpenedToCore",
                    String.valueOf(pooled.getClass().getModule().isOpen("it.liba", core)));

            // No accessible constructor: the placed proxy chains the woven entry constructor.
            Handle handle = container.select(Handle.class);
            put(out, "handleId", handle.id());

            // An INHERITED package-private member: the placed proxy must override it too. BaseHooked
            // has only a business constructor, so the loader must weave it as well for the proxy
            // to have a side-effect-free chain.
            it.liba.Hooked hooked = container.select(it.liba.Hooked.class);
            put(out, "hookedConstructionsAfterSelect", String.valueOf(it.liba.BaseHooked.constructions()));
            put(out, "inheritedForward", new it.liba.HookCaller().call(hooked));
            put(out, "hookedConstructionsAfterUse", String.valueOf(it.liba.BaseHooked.constructions()));

            // vauban#24 for a third-party type: the proxy must not run the business constructor.
            Counted counted = container.select(Counted.class);
            put(out, "constructionsAfterSelect", String.valueOf(Counted.constructions()));
            counted.value();
            put(out, "constructionsAfterFirstUse", String.valueOf(Counted.constructions()));
            counted.value();
            put(out, "constructionsAfterSecondUse", String.valueOf(Counted.constructions()));

            put(out, "probeInBootLayer",
                    String.valueOf(LayerProbe.class.getModule().getLayer() == ModuleLayer.boot()));
        }
        return out.toString();
    }

    private static void put(StringBuilder out, String key, String value) {
        if (!out.isEmpty()) out.append(';');
        out.append(key).append('=').append(value);
    }
}
