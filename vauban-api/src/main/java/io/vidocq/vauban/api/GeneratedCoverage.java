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
package io.vidocq.vauban.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a generated {@link VaubanComponentProvider} runs in-module, as it declares it: the components it instantiates,
 * the fields it injects, the methods it invokes and the client proxies it creates, each keyed exactly as the
 * container asks for it. For diagnostics only — the container never dispatches on it.
 *
 * @param generator      the code generator that wrote the provider
 * @param instantiated   the class names {@link VaubanComponentProvider#create(String)} and
 *                       {@link VaubanComponentProvider#create(String, Object[])} accept
 * @param injectedFields {@code <declaring class>#<field>} for each field {@link VaubanComponentProvider#injectField}
 *                       assigns
 * @param invokedMethods {@code <declaring class>#<methodId>} for each method {@link VaubanComponentProvider#invoke}
 *                       calls, {@code methodId} being {@code name(paramErasure,…)}
 * @param clientProxies  the keys {@link VaubanComponentProvider#createClientProxy} accepts
 */
public record GeneratedCoverage(Generator generator, Set<String> instantiated, Set<String> injectedFields,
                                Set<String> invokedMethods, Set<String> clientProxies) {

    /** The code generator that wrote a provider. */
    public enum Generator {
        /** The annotation processor, {@code vauban-processor}, as Java source. */
        APT,
        /** The Class-File API, as bytecode: {@code vauban:generate}, {@code vauban:enhance-dependencies}. */
        CLASS_FILE
    }

    public GeneratedCoverage {
        Objects.requireNonNull(generator, "generator");
        instantiated = Set.copyOf(instantiated);
        injectedFields = Set.copyOf(injectedFields);
        invokedMethods = Set.copyOf(invokedMethods);
        clientProxies = Set.copyOf(clientProxies);
    }

    /**
     * The coverage a generated provider declares, from the arrays its generated code builds. A key may repeat: it is
     * kept once.
     */
    public static GeneratedCoverage of(Generator generator, String[] instantiated, String[] injectedFields,
                                       String[] invokedMethods, String[] clientProxies) {
        return new GeneratedCoverage(generator, Set.copyOf(List.of(instantiated)),
                Set.copyOf(List.of(injectedFields)), Set.copyOf(List.of(invokedMethods)),
                Set.copyOf(List.of(clientProxies)));
    }
}
