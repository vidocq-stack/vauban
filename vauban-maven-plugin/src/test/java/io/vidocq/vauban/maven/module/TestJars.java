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

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds real jars from source, in-process: the module tooling reads bytecode and jar layout, so a
 * test that hand-writes bytes would be testing its own fixtures. Sources are compiled with the JDK
 * compiler against the jars given as the class or module path.
 */
final class TestJars {

    private TestJars() {}

    private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?:class|interface|record|enum)\\s+(\\w+)");

    /** A jar with no module descriptor — the shape the modularize goal is meant to fix. */
    static Path plainJar(Path dir, String jarName, List<Path> path, String... sources) throws IOException {
        return jar(dir, jarName, path, Map.of(), null, sources);
    }

    /** A jar carrying its own {@code module-info}: the modularize goal must leave these alone. */
    static Path modularJar(Path dir, String jarName, List<Path> path, String moduleInfo, String... sources)
            throws IOException {
        return jar(dir, jarName, path, Map.of(), moduleInfo, sources);
    }

    /**
     * @param extraEntries additional jar entries, path to UTF-8 content — {@code META-INF/services/…}
     *        files, a manifest, anything the descriptor synthesis has to read back
     */
    static Path jar(Path dir, String jarName, List<Path> path, Map<String, String> extraEntries,
                    String moduleInfo, String... sources) throws IOException {
        Files.createDirectories(dir);
        var srcDir = Files.createDirectories(dir.resolve(jarName + "-src"));
        var classes = Files.createDirectories(dir.resolve(jarName + "-classes"));

        var units = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            units.add(write(srcDir, source));
        }
        if (moduleInfo != null) {
            units.add(writeAt(srcDir.resolve("module-info.java"), moduleInfo));
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        var options = new ArrayList<>(List.of("-d", classes.toString(), "--release", "25", "-proc:none"));
        if (!path.isEmpty()) {
            var joined = path.stream().map(Path::toString).reduce((a, b) -> a + File.pathSeparator + b).orElseThrow();
            // A module-info can only see other modules through the module path.
            options.addAll(List.of(moduleInfo == null ? "-classpath" : "--module-path", joined));
        }
        var task = compiler.getTask(null, null, null, options, null, units);
        if (!Boolean.TRUE.equals(task.call())) {
            throw new IOException("fixture sources did not compile for " + jarName);
        }

        var jar = dir.resolve(jarName + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                put(out, classes.relativize(file).toString(), Files.readAllBytes(file));
            }
            for (var entry : extraEntries.entrySet()) {
                put(out, entry.getKey(), entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name.replace(File.separatorChar, '/')));
        out.write(bytes);
        out.closeEntry();
    }

    private static JavaFileObject write(Path srcDir, String source) throws IOException {
        var pkg = group(PACKAGE, source);
        var name = group(TYPE, source);
        var dir = pkg == null ? srcDir : srcDir.resolve(pkg.replace('.', '/'));
        Files.createDirectories(dir);
        return writeAt(dir.resolve(name + ".java"), source);
    }

    private static JavaFileObject writeAt(Path file, String source) throws IOException {
        Files.writeString(file, source);
        return new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                try {
                    return Files.readString(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    private static String group(Pattern pattern, String source) {
        var matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Convenience for readable call sites. */
    static List<Path> path(Path... paths) {
        return Stream.of(paths).toList();
    }
}
