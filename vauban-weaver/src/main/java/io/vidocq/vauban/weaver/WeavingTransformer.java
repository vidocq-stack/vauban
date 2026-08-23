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
package io.vidocq.vauban.weaver;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plan-driven load-time weaving: applies {@link ProxyLinkWeaver} to exactly the classes
 * named in a {@link WeavingPlan}, whichever class loader defines them, at first definition.
 *
 * <p>A transformer must never break class loading: any failure is reported on stderr and
 * the class is defined unchanged (the container's deployment validation then produces its
 * regular diagnostic).
 */
final class WeavingTransformer implements ClassFileTransformer {

    /** Internal (slash-separated) name → chain. */
    private final Map<String, ProxyLinkWeaver.SuperChain> beans;
    private final Set<String> proxies;

    WeavingTransformer(WeavingPlan plan) {
        this.beans = plan.beans().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> internal(e.getKey()), Map.Entry::getValue));
        this.proxies = plan.proxies().stream().map(WeavingTransformer::internal)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classBeingRedefined != null) return null;
        try {
            var chain = beans.get(className);
            if (chain != null) {
                // null = marker already present (already-woven build output) — leave unchanged
                return ProxyLinkWeaver.addMarkerConstructor(classfileBuffer, chain);
            }
            if (proxies.contains(className)) {
                return ProxyLinkWeaver.retargetProxyConstructor(classfileBuffer);
            }
        } catch (Throwable t) {
            System.err.println("[vauban-weaver] load-time weaving of " + className
                    + " failed, class left unchanged: " + t);
        }
        return null;
    }

    private static String internal(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
