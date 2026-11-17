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
package io.vidocq.vauban.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessorAccess} gives the processor's module the packages vauban-core exports to it alone, where the
 * qualified exports do not reach it: in a layer above vauban-core's (BUG-20261117-23).
 */
@DisplayName("ProcessorAccess: the processor's packages, in a layer above vauban-core's (BUG-20261117-23)")
class ProcessorAccessTest {

    private static final String PROCESSOR = "io.vidocq.vauban.processor";
    private static final String CODEGEN = "io.vidocq.vauban.core.codegen";
    private static final String PROXY = "io.vidocq.vauban.core.proxy";
    private static final String ACCESS = "io.vidocq.vauban.core.access";

    @Test
    @DisplayName("exports the packages the descriptor exports to the processor, to its module of a layer above")
    void exportsThePackagesTheDescriptorExportsToTheProcessorToItsModuleOfALayerAbove() {
        var core = ProcessorAccess.class.getModule();
        assertTrue(core.isNamed(), "vauban-core is a module of the test's module path");
        var processor = moduleAboveCore(PROCESSOR);
        assertEquals(Set.of(), exportedToItAlone(core, processor), "a qualified export does not reach a layer above");

        ProcessorAccess.grantTo(processor);

        var granted = exportedToItAlone(core, processor);
        assertAll(() -> assertEquals(qualifiedExportsToTheProcessor(core), granted),
                () -> assertEquals(Set.of(CODEGEN, PROXY), granted),
                () -> assertFalse(granted.contains(ACCESS), ACCESS + " is exported to Heisenberg alone"),
                () -> assertFalse(core.isExported(CODEGEN), "still a qualified export"));
    }

    @Test
    @DisplayName("refuses a named module of another name")
    void refusesANamedModuleOfAnotherName() {
        var other = moduleAboveCore("io.vidocq.vauban.other");

        assertThrows(IllegalArgumentException.class, () -> ProcessorAccess.grantTo(other));
        assertEquals(Set.of(), exportedToItAlone(ProcessorAccess.class.getModule(), other));
    }

    /**
     * An unnamed module is accepted and given nothing. The equality of before and after means something only when
     * this test runs alone: Surefire opens the packages of the tests it runs to every unnamed module, those of this
     * module's {@code codegen} and {@code proxy} tests among them in a full run, and both sides are then true.
     */
    @Test
    @DisplayName("changes nothing for a processor on a class path")
    void changesNothingForAProcessorOnAClassPath() {
        var core = ProcessorAccess.class.getModule();
        var unnamed = ClassLoader.getSystemClassLoader().getUnnamedModule();
        var codegenBefore = core.isExported(CODEGEN, unnamed);
        var proxyBefore = core.isExported(PROXY, unnamed);

        assertDoesNotThrow(() -> ProcessorAccess.grantTo(unnamed));

        assertAll(() -> assertEquals(codegenBefore, core.isExported(CODEGEN, unnamed), CODEGEN),
                () -> assertEquals(proxyBefore, core.isExported(PROXY, unnamed), PROXY));
    }

    /** The packages of {@code core} it exports to {@code module} and not to every module. */
    private static Set<String> exportedToItAlone(Module core, Module module) {
        return core.getPackages().stream()
                .filter(pkg -> core.isExported(pkg, module) && !core.isExported(pkg))
                .collect(Collectors.toSet());
    }

    /** The packages {@code core}'s module descriptor exports to {@code io.vidocq.vauban.processor} by name. */
    private static Set<String> qualifiedExportsToTheProcessor(Module core) {
        return core.getDescriptor().exports().stream()
                .filter(export -> export.targets().contains(PROCESSOR))
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toSet());
    }

    /** A module named {@code name}, which requires vauban-core, in a layer of its own above the boot layer. */
    private static Module moduleAboveCore(String name) {
        var descriptor = ModuleDescriptor.newModule(name).requires("io.vidocq.vauban.core").build();
        var reference = new ModuleReference(descriptor, URI.create("test:" + name)) {
            @Override
            public ModuleReader open() {
                return new EmptyReader();
            }
        };
        var finder = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String moduleName) {
                return name.equals(moduleName) ? Optional.of(reference) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.of(reference);
            }
        };
        var boot = ModuleLayer.boot();
        var configuration = boot.configuration().resolve(finder, ModuleFinder.of(), Set.of(name));
        return boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader())
                .findModule(name).orElseThrow();
    }

    /** The contents of a module with no class and no resource. */
    private static final class EmptyReader implements ModuleReader {

        @Override
        public Optional<URI> find(String resource) {
            return Optional.empty();
        }

        @Override
        public Stream<String> list() {
            return Stream.empty();
        }

        @Override
        public void close() {
            // Nothing to release
        }
    }
}
