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
module io.vidocq.vauban.core {
    requires transitive io.vidocq.vauban.api;
    requires io.vidocq.vauban.indexer;
    requires io.vidocq.vauban.classloader.spi;
    // Canonical vauban#24 weaving transforms, shared with the build-time weavers; the
    // load-time tier (io.vidocq.vauban.core.weaving) detects unwoven build output and
    // attaches the same artifact as an instrumentation agent.
    requires io.vidocq.vauban.weaver;
    requires java.instrument;
    // Universal loader engine — the scanner defines sjar classes through it (source
    // plugins chained with the cdi-proxifier transformer before definition).
    requires io.vidocq.vauban.classloader;
    requires transitive jakarta.cdi.lang.model;
    requires jakarta.el;
    requires jdk.unsupported;

    uses io.vidocq.vauban.classloader.spi.ByteSourcePlugin;

    // Instantiate Build Compatible Extensions through their `provides ... with` declaration,
    // so application modules need not `opens <pkg> to io.vidocq.vauban.core` on the module path.
    uses jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

    // Instantiate application components (beans, contexts, synthetic creators) via APT-generated
    // providers, so application modules need not open their packages to the container.
    uses io.vidocq.vauban.api.VaubanComponentProvider;

    exports io.vidocq.vauban.core;
    exports io.vidocq.vauban.core.container;
    exports io.vidocq.vauban.core.langmodel;
    exports io.vidocq.vauban.core.langmodel.declarations;
    exports io.vidocq.vauban.core.langmodel.types;
    exports io.vidocq.vauban.core.types;
    exports io.vidocq.vauban.core.bean.model;
    exports io.vidocq.vauban.core.bean.discovery;
    exports io.vidocq.vauban.core.bean.resolution;
    exports io.vidocq.vauban.core.bean.validation;
    exports io.vidocq.vauban.core.context;
    exports io.vidocq.vauban.core.event;
    exports io.vidocq.vauban.core.interceptor;
    exports io.vidocq.vauban.core.enrichment;
    exports io.vidocq.vauban.core.extensions;
    // vauban#24 load-time weaving entry point: the Vidocq bootstrap (and any custom
    // launcher) must be able to run it before application classes get loaded.
    exports io.vidocq.vauban.core.weaving;
    // Shared client-proxy IR + emitter, consumed by the APT front-ends only — qualified
    // export to keep the package out of the public API (non-modular consumers like the
    // Maven plugin read the jar from the classpath and are unaffected).
    exports io.vidocq.vauban.core.proxy to io.vidocq.vauban.processor;

    provides jakarta.enterprise.inject.spi.CDIProvider
            with io.vidocq.vauban.core.container.VaubanCDIProvider;

    provides jakarta.enterprise.inject.se.SeContainerInitializer
            with io.vidocq.vauban.core.container.VaubanSeContainerInitializer;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildServices
            with io.vidocq.vauban.core.extensions.VaubanBuildServices;
}
