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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Describes a class outside the index from its class file, as the index describes the classes it holds
 * ({@link ClassFileScanner}), for the language model at start and in {@code vauban:generate} (BUG-20261117-31).
 *
 * <p>The class file is a resource of the deployment's loader, {@code name.replace('.', '/') + ".class"}: Java Modules
 * do not encapsulate a {@code .class} resource, so a class of the JDK or of a named module is found too. Nothing is
 * loaded, linked or initialized. Each class is read once, the first time an extension asks for it, and so is the
 * answer that no class file describes it; the descriptions live as long as the resolver, one per container build.
 * Concurrent askers may read the same class file twice, and all of them get the description recorded first.
 */
final class ClassFileDeclarations implements Function<DotName, Optional<ClassInfo>> {

    private final ClassLoader loader;
    private final Map<DotName, Lookup> described = new ConcurrentHashMap<>();

    /** @param loader the deployment's loader; {@code null} for the system class loader */
    ClassFileDeclarations(ClassLoader loader) {
        this.loader = Objects.requireNonNullElseGet(loader, ClassLoader::getSystemClassLoader);
    }

    @Override
    public Optional<ClassInfo> apply(DotName name) {
        var known = described.get(name);
        if (known == null) {
            // Read outside the map: a class file is I/O, which a lock of the map would hold for every other class.
            var read = new Lookup(read(name));
            known = Objects.requireNonNullElse(described.putIfAbsent(name, read), read);
        }
        return known.found();
    }

    /** The class file's description, or {@code null} when no class file describes the class. */
    private ClassInfo read(DotName name) {
        if (!isClassName(name.value())) {
            return null;
        }
        try (var classFile = loader.getResourceAsStream(name.value().replace('.', '/') + ".class")) {
            if (classFile == null) {
                return null;
            }
            var info = ClassFileScanner.scan(classFile.readAllBytes());
            // A file system that ignores case finds Crud.class for crud: it describes another class.
            return info.name().equals(name) ? info : null;
        } catch (IOException | IllegalArgumentException _) {
            // A class file that cannot be read or parsed describes nothing: the language model shows the stub.
            return null;
        }
    }

    /** One class looked up, a miss included: every asker of the class gets the same {@code Optional}. */
    private static final class Lookup {

        private final Optional<ClassInfo> found;

        /** @param info the class file's description, or {@code null} when no class file describes the class */
        Lookup(ClassInfo info) {
            this.found = Optional.ofNullable(info);
        }

        Optional<ClassInfo> found() {
            return found;
        }
    }

    /**
     * A binary class name, as the index and an extension give it, never empty ({@link DotName}): no path, which a
     * resource name would follow.
     */
    private static boolean isClassName(String name) {
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.contains("..");
    }
}
