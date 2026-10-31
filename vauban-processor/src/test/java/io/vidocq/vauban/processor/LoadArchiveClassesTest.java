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
package io.vidocq.vauban.processor;

import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The classes the processor hands to the Build Compatible Extensions: one of a dependency whose supertypes are not on
 * the processor path cannot be linked under javac, and is left out instead of failing the compilation on a
 * {@code NoClassDefFoundError} (VAU-APT-008).
 */
class LoadArchiveClassesTest {

    @Test
    void aClassThatCannotBeLinkedIsLeftOut(@TempDir Path dir) throws Exception {
        byte[] sub = ClassFile.of().build(ClassDesc.of("gen.Sub"),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ClassDesc.of("gen.Missing")));
        byte[] ok = ClassFile.of().build(ClassDesc.of("gen.Ok"), cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
        Files.createDirectories(dir.resolve("gen"));
        Files.write(dir.resolve("gen/Sub.class"), sub);
        Files.write(dir.resolve("gen/Ok.class"), ok);
        var index = new IndexBuilder();
        index.add(ClassFileScanner.scan(sub));
        index.add(ClassFileScanner.scan(ok));

        try (var loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            List<Class<?>> loaded = VaubanProcessor.loadArchiveClasses(index.build(), loader);

            assertEquals(List.of("gen.Ok"), loaded.stream().map(Class::getName).toList());
        }
    }
}
