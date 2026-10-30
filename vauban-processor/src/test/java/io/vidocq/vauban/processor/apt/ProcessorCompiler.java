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
package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Compiles sources, relative path to content, with the Vauban processor, as a module's build does. */
final class ProcessorCompiler {

    /** What a compilation produced: its success, its class and source outputs, and its diagnostics. */
    record Result(boolean success, Path classes, Path generated, List<String> messages) {

        boolean mentions(String text) {
            return messages.stream().anyMatch(message -> message.contains(text));
        }
    }

    private ProcessorCompiler() {
    }

    static Result compile(Path workDir, Map<String, String> sources) throws IOException {
        return compile(workDir, sources, List.of());
    }

    /** As {@link #compile(Path, Map)}, with {@code extraOptions} after the usual ones, such as {@code -A} options. */
    static Result compile(Path workDir, Map<String, String> sources, List<String> extraOptions) throws IOException {
        return compile(workDir, sources, extraOptions, new VaubanProcessor());
    }

    /** As {@link #compile(Path, Map, List)}, with {@code processor} in place of a plain Vauban processor. */
    static Result compile(Path workDir, Map<String, String> sources, List<String> extraOptions, Processor processor)
            throws IOException {
        return compile(workDir, sources, extraOptions, List.of(processor));
    }

    /** As {@link #compile(Path, Map, List)}, with {@code processors}, run in this order, in place of Vauban alone. */
    static Result compile(Path workDir, Map<String, String> sources, List<String> extraOptions,
            List<Processor> processors) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var sourceDir = Files.createDirectories(workDir.resolve("src"));
        var files = new ArrayList<File>();
        for (var source : sources.entrySet()) {
            var file = sourceDir.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toFile());
        }
        var classes = Files.createDirectories(workDir.resolve("classes"));
        var generated = Files.createDirectories(workDir.resolve("generated"));
        var options = new ArrayList<>(List.of("-d", classes.toString(), "-s", generated.toString(), "--release", "25",
                "-classpath", classPath(), "-proc:full"));
        options.addAll(extraOptions);
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, fileManager, diagnostics, options, null,
                    fileManager.getJavaFileObjectsFromFiles(files));
            task.setProcessors(processors);
            boolean success = task.call();
            var messages = new ArrayList<String>();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                messages.add(diagnostic.getKind() + ": " + diagnostic.getMessage(null));
            }
            return new Result(success, classes, generated, messages);
        }
    }

    private static String classPath() {
        var paths = new LinkedHashSet<String>();
        var classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            paths.addAll(List.of(classPath.split(File.pathSeparator)));
        }
        for (var type : List.of(ApplicationScoped.class, VaubanComponentProvider.class, jakarta.inject.Inject.class,
                VaubanProcessor.class)) {
            try {
                paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(module -> module.reference().location()
                .filter(uri -> "file".equals(uri.getScheme()))
                .ifPresent(uri -> paths.add(Path.of(uri).toString())));
        return String.join(File.pathSeparator, paths);
    }
}
