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
package io.vidocq.vauban.processor;

import io.vidocq.vauban.indexer.codegen.ProvidedClass;
import io.vidocq.vauban.processor.codegen.provider.ComponentProviderGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the generation of the beans' artefacts hands to the providers and the indexes. */
final class Generation {
    /** The managed classes the per-package {@code _VaubanComponents} providers cover. */
    final List<ProvidedClass> providedClasses = new ArrayList<>();
    /**
     * {@code <Bean>_ClientProxy} names emitted as SOURCE (top-level normal-scoped beans with an
     * accessible no-arg ctor): the per-package provider instantiates them in-module via
     * createClientProxy, so the bean package needs no opens/exports for proxy creation.
     */
    final Set<String> clientProxyFqns = new LinkedHashSet<>();
    /**
     * Produced-type proxies for normal-scoped producers of fully-public external classes
     * (issue #42), grouped by the producer's package — registered in that package's provider,
     * keyed by the produced type, so the runtime's provider-first lookup wins with zero opens.
     */
    final Map<String, List<ComponentProviderGenerator.ProducerProxy>> producerProxiesByPackage =
            new LinkedHashMap<>();
    /**
     * Produced class types that fall to runtime proxy generation (Stage 3b): the boot-time
     * OpensApplier opens their package to io.vidocq.vauban.core via the agent, but only if the
     * type turns out to be in a NAMED module at runtime (classpath types need nothing).
     */
    final Set<String> requiredOpens = new LinkedHashSet<>();

    void addProducerProxy(String producerPackage, String key, String proxyBinaryName) {
        producerProxiesByPackage.computeIfAbsent(producerPackage, k -> new ArrayList<>())
                .add(new ComponentProviderGenerator.ProducerProxy(key, proxyBinaryName));
    }
}
