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
package io.vidocq.vauban.moduleit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;

/**
 * The fixture bean: normal-scoped (build-time {@code _ClientProxy}) and {@code @Audited}
 * (build-time, source-rendered {@code $$Intercepted}). Each method targets one historical
 * Java Modules bug — see the test for the mapping.
 */
@ApplicationScoped
@Audited
public class AuditedService {

    /** Package-private so the generated provider field-injects it in-module (no opens). */
    @Inject
    Collaborator collaborator;

    // ---- VAU-INT-001: overloads collide on the erased $$ti$<name> glue ----

    public String work() {
        return "w0";
    }

    public String work(int n) {
        return "w" + n;
    }

    public String work(String s) {
        return "w" + s;
    }

    // ---- VAU-INT-002: primitive arrays are references (aload, one slot), and long/double
    // scalars are category-2 (two slots) — the glue and slot arithmetic must not confuse them ----

    public int[] doubled(int[] data) {
        int[] result = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] * 2;
        }
        return result;
    }

    public long weightedSum(long seed, int[] data, double factor) {
        long total = seed;
        for (int d : data) {
            total += Math.round(d * factor);
        }
        return total;
    }

    // ---- VAU-INT-003: a checked throws clause must compile in the source-rendered
    // $$Intercepted and the original exception must propagate unwrapped ----

    public String risky(boolean fail) throws IOException {
        if (fail) {
            throw new IOException("boom");
        }
        return "ok";
    }

    // ---- VAU-PRX-003: nested class as return/parameter type in generated descriptors ----

    public Outer.Inner nested(Outer.Inner in) {
        return new Outer.Inner(in.value() + "!");
    }

    // ---- in-module field injection (no opens) ----

    public String viaCollaborator() {
        return collaborator.hello();
    }
}
