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
package io.vidocq.vauban.maven.enhance;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleProvideInfo;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites a {@code module-info.class} (JDK Class-File API, zero dependencies) to add the Vauban
 * component-provider service (issue #42, Stage 2 — {@code vauban:enhance-dependencies}). Adds
 * {@code provides io.vidocq.vauban.api.VaubanComponentProvider with <providers>;} (merged into any
 * existing directive for that service) and {@code requires io.vidocq.vauban.api;} when absent, so an
 * enhanced third-party module offers its generated proxies to the container on the module path.
 */
public final class ModuleInfoRewriter {

    private static final String SPI = "io.vidocq.vauban.api.VaubanComponentProvider";
    private static final String API_MODULE = "io.vidocq.vauban.api";

    private ModuleInfoRewriter() {}

    /** Return new {@code module-info.class} bytes with the provider service and API requirement. */
    public static byte[] addComponentProvider(byte[] moduleInfo, List<String> providerFqns) {
        return addComponentProvider(moduleInfo, providerFqns, Set.of());
    }

    /**
     * Same as {@link #addComponentProvider(byte[], List)}, also requiring {@code extraRequires}: the Vauban modules
     * the classes added to the module call into, such as {@code io.vidocq.vauban.core} for an intercepted subclass.
     * With no provider, {@code provides} is left as it is and only the requirements are added.
     */
    public static byte[] addComponentProvider(byte[] moduleInfo, List<String> providerFqns,
                                              Set<String> extraRequires) {
        var cf = ClassFile.of();
        var model = cf.parse(moduleInfo);
        var old = model.findAttribute(Attributes.module()).orElseThrow(
                () -> new IllegalArgumentException("not a module-info.class (no ModuleAttribute)"));

        var spiCD = ClassDesc.of(SPI);
        var newProviderCDs = providerFqns.stream().map(ClassDesc::of).toList();
        var wanted = new LinkedHashSet<String>();
        wanted.add(API_MODULE);
        wanted.addAll(extraRequires);

        var newAttr = ModuleAttribute.of(old.moduleName(), mb -> {
            mb.moduleFlags(old.moduleFlagsMask());
            old.moduleVersion().ifPresent(v -> mb.moduleVersion(v.stringValue()));
            for (var e : old.exports()) mb.exports(e);
            for (var o : old.opens()) mb.opens(o);
            for (var u : old.uses()) mb.uses(u);

            // requires: copy all, add every wanted module that is absent.
            for (var r : old.requires()) {
                mb.requires(r);
                wanted.remove(r.requires().name().stringValue());
            }
            for (var name : wanted) {
                mb.requires(ModuleRequireInfo.of(ModuleDesc.of(name), 0, null));
            }

            // provides: copy all, merging our providers into the existing SPI directive (if any).
            boolean spiFound = false;
            for (var p : old.provides()) {
                if (!newProviderCDs.isEmpty() && p.provides().asSymbol().equals(spiCD)) {
                    var impls = new LinkedHashSet<ClassDesc>();
                    for (var w : p.providesWith()) impls.add(w.asSymbol());
                    impls.addAll(newProviderCDs);
                    mb.provides(ModuleProvideInfo.of(spiCD, new ArrayList<>(impls)));
                    spiFound = true;
                } else {
                    mb.provides(p);
                }
            }
            if (!spiFound && !newProviderCDs.isEmpty()) {
                mb.provides(ModuleProvideInfo.of(spiCD, newProviderCDs));
            }
        });

        return cf.transformClass(model,
                ClassTransform.dropping(el -> el instanceof ModuleAttribute)
                        .andThen(ClassTransform.endHandler(clb -> clb.with(newAttr))));
    }
}
