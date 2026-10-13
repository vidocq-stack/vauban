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
package io.vidocq.vauban.classloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The re-layer policy with kept roots (vauban#154): a runtime names its root modules, and their whole
 * {@code requires} closure stays in the parent layer whatever the names; every other explicit module moves to the
 * application layer.
 */
@DisplayName("Re-layer policy — kept roots")
class KeptRootsPolicyTest {

    @Test
    @DisplayName("the kept roots' closure stays, two hops included; the application and its own library move")
    void keptRootsClosureStays(@TempDir Path dir) throws Exception {
        module(dir, "rt.core", "rt.lib");
        module(dir, "rt.lib", "rt.deep");
        module(dir, "rt.deep");
        module(dir, "app.main", "rt.core", "third.lib");
        module(dir, "third.lib");
        var configuration = resolve(dir, "app.main");

        var moved = names(VaubanLayerFactory.applicationPaths(configuration, List.of(),
                Set.of("rt.core"), Set.of("app.main")));

        assertEquals(Set.of("app.main", "third.lib"), moved);
    }

    @Test
    @DisplayName("a library that a kept root reads stays in the parent layer, even if the application reads it too")
    void sharedLibraryStays(@TempDir Path dir) throws Exception {
        module(dir, "rt.core", "shared.lib");
        module(dir, "shared.lib");
        module(dir, "app.main", "rt.core", "shared.lib");
        var configuration = resolve(dir, "app.main");

        var moved = names(VaubanLayerFactory.applicationPaths(configuration, List.of(),
                Set.of("rt.core"), Set.of("app.main")));

        assertEquals(Set.of("app.main"), moved);
    }

    @Test
    @DisplayName("without kept roots, the three-argument form keeps its behaviour")
    void threeArgumentFormUnchanged(@TempDir Path dir) throws Exception {
        module(dir, "rt.core", "rt.lib");
        module(dir, "rt.lib");
        module(dir, "app.main", "rt.core");
        var configuration = resolve(dir, "app.main");

        var moved = names(VaubanLayerFactory.applicationPaths(configuration, List.of(), Set.of("app.main")));

        assertEquals(Set.of("app.main", "rt.core", "rt.lib"), moved);
    }

    private static void module(Path dir, String name, String... requires) throws IOException {
        var root = dir.resolve(name);
        Files.createDirectories(root);
        Files.write(root.resolve("module-info.class"), ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(name), mb -> {
                    mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
                    for (var r : requires) {
                        mb.requires(ModuleDesc.of(r), 0, null);
                    }
                })));
    }

    private static Configuration resolve(Path dir, String root) throws IOException {
        try (var dirs = Files.list(dir)) {
            var finder = ModuleFinder.of(dirs.toArray(Path[]::new));
            return Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                    ModuleFinder.of(), Set.of(root));
        }
    }

    private static Set<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
