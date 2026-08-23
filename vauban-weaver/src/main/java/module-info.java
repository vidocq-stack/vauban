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

/**
 * Client-proxy constructor weaving (Vidocq/vauban#24): the canonical Class-File API
 * transformations, shared as a library by the javac plugin, the Maven plugin and the
 * container, and packaged as a {@code java.lang.instrument} agent for load-time weaving
 * when the build did not weave (IDE builds).
 *
 * <p>{@code java.instrument} is a hard requirement on purpose: when the application runs
 * on the module path, this module is resolved as a named module (vauban-core requires it)
 * and the dynamically attached agent classes resolve to it — a {@code static} requires
 * would leave {@code java.instrument} out of the graph and fail transformer registration
 * with {@code IllegalAccessError}. {@code jdk.attach} stays {@code static}:
 * {@code AttachBack} only ever runs as an unnamed-module child process
 * ({@code java -cp vauban-weaver.jar}), where {@code jdk.attach} is a default root module.
 */
module io.vidocq.vauban.weaver {
    requires static jdk.attach;
    requires java.instrument;

    exports io.vidocq.vauban.weaver;
}
