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
package io.vidocq.vauban.maven.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Entries a synthesized descriptor leaves out, a requirement on the module itself, and the result's value semantics. */
class ModuleDescriptorSynthesizerEntriesTest {

    private static final String MODULE = "com.acme.lib";

    @TempDir
    Path tmp;

    private static byte[] classReferencing(String fqn, ClassDesc referenced) {
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> clb.withMethodBody("use",
                MethodTypeDesc.of(ConstantDescs.CD_void, referenced), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                CodeBuilder::return_));
    }

    private static Path jar(Path file, Manifest manifest, Map<String, byte[]> entries) throws IOException {
        try (var out = manifest == null ? new JarOutputStream(Files.newOutputStream(file))
                : new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private ModuleDescriptorSynthesizer.Result synthesize() throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", MODULE);
        var twin = jar(tmp.resolve("twin.jar"), manifest,
                Map.of("com/twin/Thing.class", classReferencing("com.twin.Thing", ConstantDescs.CD_Object)));
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("com/acme/lib/", new byte[0]);
        entries.put("com/acme/lib/Service.class", classReferencing("com.acme.lib.Service", ClassDesc.of("com.twin.Thing")));
        entries.put("com/acme/lib/data/values.txt", "x".getBytes(StandardCharsets.UTF_8));
        entries.put("root.txt", "x".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/notes.txt", "x".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/", new byte[0]);
        entries.put("META-INF/services/nested/com.acme.lib.Spi", "com.acme.lib.Service".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/com.acme.lib.Spi", "com.other.Impl\n".getBytes(StandardCharsets.UTF_8));
        var lib = jar(tmp.resolve("lib.jar"), null, entries);
        return ModuleDescriptorSynthesizer.synthesize(
                new ModuleDescriptorSynthesizer.Request(lib, MODULE, true, List.of(lib, twin), Set.of()));
    }

    @Test
    void onlyClassAndResourcePackagesAndContainedProvidersAreDescribed() throws IOException {
        var descriptor = synthesize().descriptor();

        assertEquals(Set.of("com.acme.lib", "com.acme.lib.data"), descriptor.packages());
        assertEquals(Set.of(), descriptor.provides(), "a service whose providers the jar does not hold is left out");
        assertEquals(Set.of("java.base"), descriptor.requires().stream().map(ModuleDescriptor.Requires::name)
                .collect(java.util.stream.Collectors.toSet()), "a package of a module of the same name is no requirement");
    }

    @Test
    void aProviderTheJarDoesNotHoldIsNoted() throws IOException {
        assertTrue(synthesize().notes().contains("WARN META-INF/services/com.acme.lib.Spi lists com.other.Impl,"
                + " which this jar does not contain: left out of the module descriptor"));
    }

    @Test
    void aResultIsComparedByItsContent() throws IOException {
        var result = synthesize();
        var same = new ModuleDescriptorSynthesizer.Result(result.moduleInfo().clone(), result.descriptor(),
                result.notes());

        assertEquals(result, same);
        assertEquals(result.hashCode(), same.hashCode());
        assertTrue(result.toString().startsWith("Result[moduleInfo=["), result.toString());
        assertTrue(result.toString().endsWith(", notes=" + result.notes() + "]"), result.toString());
        assertNotEquals(result, new ModuleDescriptorSynthesizer.Result(new byte[] {1}, result.descriptor(),
                result.notes()));
        assertNotEquals(result, new ModuleDescriptorSynthesizer.Result(result.moduleInfo(),
                ModuleDescriptor.newModule("other").build(), result.notes()));
        assertNotEquals(result, new ModuleDescriptorSynthesizer.Result(result.moduleInfo(), result.descriptor(),
                List.of("another note")));
        assertNotEquals(result, (Object) "Result");
    }
}
