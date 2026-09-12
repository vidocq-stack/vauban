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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarModuleClassifierTest {

    @TempDir
    Path tmp;

    @Test
    void jarWithoutDescriptorNorManifestNameIsAutomaticDerived() throws IOException {
        Path classes = tmp.resolve("c1");
        TestJars.compileClass(classes, "com.acme.plain.Hello", "package com.acme.plain; public class Hello {}");
        Path jar = TestJars.jar(tmp.resolve("langchain4j-open-ai-1.17.1.jar"), classes, Map.of(), Map.of());

        JarModuleInfo info = JarModuleClassifier.classify(jar);

        assertEquals(JarModuleInfo.Kind.AUTOMATIC_DERIVED, info.kind());
        assertEquals("langchain4j.open.ai", info.moduleName());
        assertEquals(Set.of("com.acme.plain"), info.packages());
    }

    @Test
    void jarWithAutomaticModuleNameIsAutomaticNamed() throws IOException {
        Path classes = tmp.resolve("c2");
        TestJars.compileClass(classes, "org.acme.tools.Tool", "package org.acme.tools; public class Tool {}");
        Path jar = TestJars.jar(tmp.resolve("opennlp-tools-2.5.9.jar"), classes,
                Map.of("Automatic-Module-Name", "org.apache.opennlp.tools"), Map.of());

        JarModuleInfo info = JarModuleClassifier.classify(jar);

        assertEquals(JarModuleInfo.Kind.AUTOMATIC_NAMED, info.kind());
        assertEquals("org.apache.opennlp.tools", info.moduleName());
    }

    /**
     * {@code ModuleFinder.findAll()} signals an underivable automatic name with an unchecked
     * {@code FindException}. Unwrapped it would surface as an internal error naming no jar, so the
     * classifier has to turn it into the {@code IOException} it declares, jar name included.
     */
    @Test
    void jarWhoseNameYieldsNoValidModuleNameFailsWithTheJarNamed() throws IOException {
        Path classes = tmp.resolve("c4");
        TestJars.compileClass(classes, "com.acme.weird.Weird", "package com.acme.weird; public class Weird {}");
        // "1weird" is not a Java identifier: a module name may not start with a digit.
        Path jar = TestJars.jar(tmp.resolve("1weird-1.0.jar"), classes, Map.of(), Map.of());

        IOException e = assertThrows(IOException.class, () -> JarModuleClassifier.classify(jar));

        assertTrue(e.getMessage().contains("1weird-1.0.jar"), e.getMessage());
        assertTrue(e.getMessage().startsWith("Cannot derive a module descriptor for"), e.getMessage());
    }

    @Test
    void jarWithModuleInfoIsExplicit() throws IOException {
        Path classes = tmp.resolve("c3");
        TestJars.compileClass(classes, "com.acme.mod.Api", "package com.acme.mod; public class Api {}");
        // compile a descriptor into the same output dir
        Path srcDir = tmp.resolve("c3-mod-src");
        java.nio.file.Files.createDirectories(srcDir.resolve("com/acme/mod"));
        java.nio.file.Files.writeString(srcDir.resolve("module-info.java"),
                "module com.acme.mod { exports com.acme.mod; }");
        java.nio.file.Files.writeString(srcDir.resolve("com/acme/mod/Api.java"),
                "package com.acme.mod; public class Api {}");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), "--release", "25",
                srcDir.resolve("module-info.java").toString(), srcDir.resolve("com/acme/mod/Api.java").toString());
        assertEquals(0, rc);
        Path jar = TestJars.jar(tmp.resolve("acme-mod-1.0.jar"), classes, Map.of(), Map.of());

        JarModuleInfo info = JarModuleClassifier.classify(jar);

        assertEquals(JarModuleInfo.Kind.EXPLICIT, info.kind());
        assertEquals("com.acme.mod", info.moduleName());
    }
}
