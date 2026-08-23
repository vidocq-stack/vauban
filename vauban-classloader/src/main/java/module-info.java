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
 * The universal Vauban class loader engine: resolves application archives through
 * {@code ByteSourcePlugin}s (plain jar and exploded directory built in, sjar via
 * vauban-sjar), pipes every class through the {@code ClassTransformerPlugin} chain
 * (cdi-proxifier built in), then defines it. Deliberately container-free: the loader
 * precedes the container.
 */
module io.vidocq.vauban.classloader {
    requires transitive io.vidocq.vauban.classloader.spi;
    requires io.vidocq.vauban.weaver;

    uses io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
    uses io.vidocq.vauban.classloader.spi.ClassTransformerPlugin;

    exports io.vidocq.vauban.classloader;

    provides io.vidocq.vauban.classloader.spi.ClassTransformerPlugin
            with io.vidocq.vauban.classloader.CdiProxifierTransformer;
}
