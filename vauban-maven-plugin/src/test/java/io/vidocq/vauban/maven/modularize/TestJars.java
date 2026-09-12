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

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Builds real (compilable) fixture jars for the modularize tests. */
final class TestJars {
    private TestJars() {}

    /** Compiles {@code source} (a full compilation unit) into {@code classesDir}. */
    static void compileClass(Path classesDir, String fqcn, String source) throws IOException {
        compileClasses(classesDir, Map.of(fqcn, source));
    }

    /**
     * Compiles several compilation units ({@code fqcn} → source) into {@code classesDir} in a
     * single javac invocation, so they may reference each other.
     */
    static void compileClasses(Path classesDir, Map<String, String> sources) throws IOException {
        compileClasses(classesDir, sources, null);
    }

    /** Same, compiled against {@code classpath} (a jar or directory, {@code null} for none). */
    static void compileClasses(Path classesDir, Map<String, String> sources, Path classpath) throws IOException {
        Path srcRoot = classesDir.resolveSibling(classesDir.getFileName() + "-src");
        String[] args = new String[(classpath == null ? 4 : 6) + sources.size()];
        args[0] = "-d";
        args[1] = classesDir.toString();
        args[2] = "--release";
        args[3] = "25";
        int i = 4;
        if (classpath != null) {
            args[i++] = "-classpath";
            args[i++] = classpath.toString();
        }
        for (var e : sources.entrySet()) {
            Path src = srcRoot.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, e.getValue());
            args[i++] = src.toString();
        }
        Files.createDirectories(classesDir);
        var javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, args);
        if (rc != 0) throw new IllegalStateException("javac failed for " + sources.keySet());
    }

    /** Packs {@code classesDir} (+ optional text entries) into a jar with the given manifest attributes. */
    static Path jar(Path out, Path classesDir, Map<String, String> manifestAttrs,
                    Map<String, String> extraTextEntries) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifestAttrs.forEach((k, v) -> mf.getMainAttributes().putValue(k, v));
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out); JarOutputStream jos = new JarOutputStream(os, mf)) {
            if (classesDir != null && Files.isDirectory(classesDir)) {
                try (Stream<Path> walk = Files.walk(classesDir)) {
                    for (Path p : walk.filter(Files::isRegularFile).toList()) {
                        jos.putNextEntry(new JarEntry(classesDir.relativize(p).toString().replace('\\', '/')));
                        jos.write(Files.readAllBytes(p));
                        jos.closeEntry();
                    }
                }
            }
            for (var e : extraTextEntries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes());
                jos.closeEntry();
            }
        }
        return out;
    }
}
