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
package io.vidocq.vauban.maven.generate;

import java.util.List;

/**
 * Result of the build-time CDI bean discovery and code generation.
 *
 * @param discoveredBeanClasses fully-qualified names of discovered CDI beans
 * @param generatedProxies      class names of generated client proxies (normal-scoped beans)
 * @param generatedInterceptors class names of generated interceptor subclasses
 * @param warnings              non-fatal issues encountered during generation
 */
public record GenerationResult(
        List<String> discoveredBeanClasses,
        List<String> generatedProxies,
        List<String> generatedInterceptors,
        List<String> warnings
) {
    public GenerationResult {
        discoveredBeanClasses = List.copyOf(discoveredBeanClasses);
        generatedProxies = List.copyOf(generatedProxies);
        generatedInterceptors = List.copyOf(generatedInterceptors);
        warnings = List.copyOf(warnings);
    }
}
