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
package io.vidocq.vauban.maven.modularize;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Whether a caller reads the module exporting a service, through converging, unknown and automatic requirements. */
class UsesLegalityReadabilityTest {

    private static final String SERVICE = "target.api.Spi";

    @TempDir
    Path tmp;

    /** A jar holding one class entry in {@code pkg}, so that its derived descriptor lists that package. */
    private Path jar(String fileName, String pkg) throws IOException {
        var file = tmp.resolve(fileName);
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry(pkg.replace('.', '/') + "/Type.class"));
            out.write(new byte[] {1});
            out.closeEntry();
        }
        return file;
    }

    private static ModuleDescriptor explicit(String name, Set<String> requires, Set<String> transitive,
            String... exports) {
        var builder = ModuleDescriptor.newModule(name);
        requires.forEach(builder::requires);
        transitive.forEach(module -> builder.requires(Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE), module));
        for (String pkg : exports) {
            builder.exports(pkg);
        }
        return builder.build();
    }

    @Test
    void aCallerThatReachesTheExporterByNoPathCannotUseItsService() throws IOException {
        var app = jar("app.jar", "app");
        var left = jar("left.jar", "left");
        var right = jar("right.jar", "right");
        var shared = jar("shared.jar", "shared");
        var target = jar("target.jar", "target.api");
        var nameless = jar("1.jar", "nameless");
        var synthesized = Map.of(
                app, explicit("app", Set.of("left", "right", "ghost"), Set.of()),
                left, explicit("left", Set.of(), Set.of("shared")),
                right, explicit("right", Set.of(), Set.of("shared")),
                shared, explicit("shared", Set.of(), Set.of()),
                target, explicit("target", Set.of(), Set.of(), "target.api"));
        var closure = List.of(app, left, right, shared, target, nameless);

        var verdict = UsesLegality.of(closure, synthesized.keySet(), synthesized)
                .check(Map.of(app, Set.of(SERVICE), nameless, Set.of(SERVICE)));

        assertEquals(Map.of(app, Set.of()), verdict.legal(), "a jar with no module name is not checked at all");
        assertEquals(List.of(new UsesLegality.Drop(app, SERVICE, UsesLegality.Kind.UNREADABLE,
                "caller app cannot read target (would need requires target — cycle?)")), verdict.drops());
    }

    @Test
    void aCallerReadingAnAutomaticModuleReadsEveryAutomaticModule() throws IOException {
        var app = jar("app.jar", "app");
        var bridge = jar("bridge-1.0.jar", "bridge");
        var target = jar("target-1.0.jar", "target.api");
        var synthesized = Map.of(app, explicit("app", Set.of("bridge"), Set.of()));

        var verdict = UsesLegality.of(List.of(app, bridge, target), Set.of(app), synthesized)
                .check(Map.of(app, Set.of(SERVICE)));

        assertEquals(Map.of(app, Set.of(SERVICE)), verdict.legal());
        assertEquals(List.of(), verdict.drops());
    }
}
