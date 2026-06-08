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

import java.util.List;

/**
 * The complete descriptor for a single {@code <pkg>/_VaubanComponents} provider: one record
 * per package that contains at least one component, field injection, or method invocation.
 *
 * <p>Both the APT path and the Maven-plugin path produce one {@code PackageProvider} per package
 * and then hand it to their respective emitters (source generator vs. Class-File API generator).
 *
 * @param packageName the Java package name (empty string for the default/unnamed package)
 * @param components  components the provider can instantiate in-module (sorted by fqn)
 * @param fields      non-private {@code @Inject} instance fields the provider can assign in-module
 * @param methods     CDI lifecycle/producer/observer methods the provider can invoke in-module
 */
public record PackageProvider(
        String packageName,
        List<Component> components,
        List<FieldInject> fields,
        List<MethodInvoke> methods) {

    public PackageProvider {
        components = List.copyOf(components);
        fields     = List.copyOf(fields);
        methods    = List.copyOf(methods);
    }

    /** {@code true} when this provider has nothing to emit — all three lists are empty. */
    public boolean isEmpty() {
        return components.isEmpty() && fields.isEmpty() && methods.isEmpty();
    }

    /** Fully-qualified name of the generated provider class. */
    public String providerFqn() {
        return packageName.isEmpty() ? "_VaubanComponents" : packageName + "._VaubanComponents";
    }
}
