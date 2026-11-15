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

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The beans of the bean archives on the processor path, read as class files (BUG-20261115-04).
 *
 * <p>javac shows a processor the modules of its module graph only: a bean module on the module path that no module
 * of the compilation requires is out of sight of {@code Elements}, and javac gives a processor no way to list the
 * module path. The processor path is the processor's own class loader, which it can read: Vidocq's {@code -codegen}
 * bundles put each brick's CDI jar there. Each jar compiled with Vauban lists its beans in
 * {@code META-INF/vauban-beans.list}, and their class files are resources of that loader: a resource that ends with
 * {@code .class} is never encapsulated, in a named module either. Nothing is loaded or run.</p>
 */
final class ProcessorPathArchives {

    /** The beans a jar compiled with Vauban declares, one binary name per line. */
    static final String BEANS_LIST_PATH = "META-INF/vauban-beans.list";

    private ProcessorPathArchives() {
    }

    /**
     * The beans every {@code META-INF/vauban-beans.list} of {@code loader} lists, read from their class files, in the
     * order of the path, the first of a name only. A bean whose class file is missing or unreadable is left out: the
     * container reads the archive again at start.
     *
     * @param skip the names not to read: those the compilation already holds
     */
    static List<ClassInfo> beanClasses(ClassLoader loader, Predicate<String> skip) {
        var beans = new ArrayList<ClassInfo>();
        List<URL> lists;
        try {
            lists = Collections.list(loader.getResources(BEANS_LIST_PATH));
        } catch (IOException _) {
            return beans;
        }
        var seen = new HashSet<String>();
        for (var list : lists) {
            for (var name : listedClasses(list)) {
                if (seen.add(name) && !skip.test(name)) {
                    parse(classFile(loader, name)).ifPresent(beans::add);
                }
            }
        }
        return beans;
    }

    /** The class names {@code list} holds, comments and blank lines left out; empty when it cannot be read. */
    static List<String> listedClasses(URL list) {
        try (var in = open(list)) {
            return listedNames(in);
        } catch (IOException _) {
            // An unreadable list declares nothing the build can use; the container reads it again at start.
            return List.of();
        }
    }

    /**
     * The names a list in the format of {@code META-INF/services} holds, one per line, {@code #} comments and blank
     * lines left out: a {@code vauban-beans.list} and a service file alike. {@code in} is left open.
     */
    static List<String> listedNames(InputStream in) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        var names = new ArrayList<String>();
        for (var line = reader.readLine(); line != null; line = reader.readLine()) {
            var name = withoutComment(line);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** Opens {@code url} uncached: a cached jar connection would keep the jar open after javac closed its loader. */
    private static InputStream open(URL url) throws IOException {
        var connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    /** {@code line} without its {@code #} comment and surrounding blanks. */
    private static String withoutComment(String line) {
        int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).strip();
    }

    /** The class file of {@code className} on {@code loader}; empty when it cannot be read there. */
    static byte[] classFile(ClassLoader loader, String className) {
        try (var in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            return in != null ? in.readAllBytes() : new byte[0];
        } catch (IOException _) {
            return new byte[0];
        }
    }

    /** What {@code classFile} declares; empty when it is no class file this JDK reads. */
    private static Optional<ClassInfo> parse(byte[] classFile) {
        if (classFile.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(ClassFileScanner.scan(classFile));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
