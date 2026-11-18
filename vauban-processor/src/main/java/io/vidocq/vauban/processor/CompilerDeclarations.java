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

import io.vidocq.vauban.core.bean.discovery.PackageInfos;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.processor.apt.ElementScanner;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Describes a class the index does not hold as the compiler sees it, through {@link ElementScanner}, as the index
 * describes the classes of the compilation: a class of the compilation that no annotation brings into the index, one
 * of the class path, or one of the JDK. Lazy, once per class, and read-only: it never makes the class a bean.
 *
 * <ul>
 *   <li>{@link #declaration} serves the language model the extensions read, every class included: at start and frozen,
 *       the container describes the same classes from their class files (VAU-BCE-008, BUG-20261117-31).</li>
 *   <li>{@link #unindexed} serves the bean discovery and the extensions' type matching too, which read the classes
 *       and interfaces above a class: a superclass the compilation compiles, which no class loader of the processor
 *       loads (BUG-20261117-03). It leaves the JDK's classes out, which they load, as the container does at start.</li>
 * </ul>
 */
final class CompilerDeclarations {

    private final Elements elements;
    private final ElementScanner scanner;
    private final Map<DotName, Lookup> described = new ConcurrentHashMap<>();

    CompilerDeclarations(ProcessingEnvironment environment) {
        this.elements = environment.getElementUtils();
        this.scanner = new ElementScanner(environment);
    }

    /** The class as the compiler sees it, for the language model alone; empty when the compiler sees no such class. */
    Optional<ClassInfo> declaration(DotName name) {
        var known = described.get(name);
        if (known == null) {
            var scanned = new Lookup(scan(name));
            known = Objects.requireNonNullElse(described.putIfAbsent(name, scanned), scanned);
        }
        return known.found();
    }

    /**
     * What the compiler sees of the class: of a package's {@code package-info}, the package, which carries its
     * annotations, as the class file does (BUG-20261117-57); {@code null} when it sees no such class.
     */
    private ClassInfo scan(DotName name) {
        if (PackageInfos.isPackageInfo(name.value())) {
            var pkg = elements.getPackageElement(name.packageName());
            return pkg == null ? null : scanner.scan(pkg);
        }
        var type = typeElement(name);
        return type == null ? null : scanner.scan(type);
    }

    /** One class looked up, a miss included: every asker of the class gets the same {@code Optional}. */
    private static final class Lookup {

        private final Optional<ClassInfo> found;

        /** @param info what the compiler sees of the class, or {@code null} when it sees no such class */
        Lookup(ClassInfo info) {
            this.found = Optional.ofNullable(info);
        }

        Optional<ClassInfo> found() {
            return found;
        }
    }

    /**
     * The class as the compiler sees it, unless it belongs to the JDK, which is not scanned for it: for the bean
     * discovery and the type matching.
     */
    Optional<ClassInfo> unindexed(DotName name) {
        var type = typeElement(name);
        return type == null || isPlatform(type) ? Optional.empty() : declaration(name);
    }

    private TypeElement typeElement(DotName name) {
        return elements.getTypeElement(name.value().replace('$', '.'));
    }

    private boolean isPlatform(TypeElement type) {
        var module = elements.getModuleOf(type);
        var moduleName = module == null ? "" : module.getQualifiedName().toString();
        return moduleName.startsWith("java.") || moduleName.startsWith("jdk.");
    }
}
