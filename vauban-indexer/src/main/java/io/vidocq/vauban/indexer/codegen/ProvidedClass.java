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
package io.vidocq.vauban.indexer.codegen;

import io.vidocq.vauban.indexer.model.ClassInfo;

/**
 * A caller-supplied descriptor for one managed class that may receive an in-module provider entry.
 *
 * <p>The caller (APT processor or Maven-plugin generator) decides which classes are eligible
 * (managed, in-module, top-level) and whether the class is instantiable in-module.  The
 * {@link ComponentCollector} only extracts per-class descriptors and groups them by package.
 *
 * @param fqn           fully-qualified class name (no {@code $} — callers must filter nested types)
 * @param classInfo     the indexer's class descriptor for this class
 * @param instantiable  {@code true} when the collector should attempt to determine constructor
 *                      parameters via {@link ComponentCollector#instantiableCtorParams}; the caller
 *                      sets this based on its own eligibility rules (e.g. top-level type check in
 *                      APT, {@code classFileExists} in the plugin)
 * @param intercepted   {@code true} when the caller pre-generated a {@code <fqn>$$Intercepted}
 *                      subclass for this class; the collector then emits a second in-module
 *                      {@link Component} so the container can instantiate the subclass via the
 *                      provider ({@code new <fqn>$$Intercepted(args…)}) instead of reflectively.
 *                      Only honoured by the bytecode provider path — a generated source provider
 *                      cannot reference a Filer-emitted {@code $$Intercepted} symbol
 */
public record ProvidedClass(String fqn, String sourceFqn, ClassInfo classInfo,
                           boolean instantiable, boolean intercepted) {

    /** Back-compatible constructor for callers that pre-generate no interceptor subclass. */
    public ProvidedClass(String fqn, ClassInfo classInfo, boolean instantiable) {
        this(fqn, fqn, classInfo, instantiable, false);
    }

    /** For callers that have no separate source name — every top-level type. */
    public ProvidedClass(String fqn, ClassInfo classInfo, boolean instantiable, boolean intercepted) {
        this(fqn, fqn, classInfo, instantiable, intercepted);
    }
}
