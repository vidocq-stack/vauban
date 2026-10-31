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
package io.vidocq.vauban.core.loading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("DeploymentClasses: loading a class the index names")
class DeploymentClassesTest {

    @Test
    @DisplayName("a class only the thread context class loader defines is found through it")
    void throughTheContextClassLoader() throws ClassNotFoundException {
        var loader = IsolatedLoader.ofClass("io.vidocq.vauban.core.loading.isolated.OnlyHere");
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            assertSame(loader.loadClass(loader.name()), DeploymentClasses.load(loader.name()));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("without a thread context class loader, deployment classes load through the caller's loader")
    void noContextClassLoader() throws ClassNotFoundException {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertEquals(String.class, DeploymentClasses.load("java.lang.String"));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    static boolean initialised;

    static final class WithStaticInitialiser {
        static {
            initialised = true;
        }
    }

    @Test
    @DisplayName("without a thread context class loader, the class is loaded but not initialised")
    void noContextClassLoaderDoesNotInitialise() throws ClassNotFoundException {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertEquals(WithStaticInitialiser.class, DeploymentClasses.load(WithStaticInitialiser.class.getName()));
            assertFalse(initialised, "loading a deployment class ran its static initialiser");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Under the annotation processor, a dependency's class whose supertypes are not on the processor path cannot be
     * linked: discovery sees it as a class it cannot load, as for a missing one, instead of failing the compilation on
     * a {@code NoClassDefFoundError} (VAU-APT-008).
     */
    @Test
    @DisplayName("a class that cannot be linked is reported as not found, its linkage error as the cause")
    void aClassThatCannotBeLinkedIsNotFound(@TempDir Path dir) throws IOException {
        byte[] sub = ClassFile.of().build(ClassDesc.of("gen.Sub"),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ClassDesc.of("gen.Missing")));
        Files.createDirectories(dir.resolve("gen"));
        Files.write(dir.resolve("gen/Sub.class"), sub);
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            thread.setContextClassLoader(loader);
            var thrown = assertThrows(ClassNotFoundException.class, () -> DeploymentClasses.load("gen.Sub"));
            assertInstanceOf(LinkageError.class, thrown.getCause());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
