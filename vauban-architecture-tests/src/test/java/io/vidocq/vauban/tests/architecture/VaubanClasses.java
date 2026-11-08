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
package io.vidocq.vauban.tests.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** The classes the architecture rules are applied to, imported once per test run. */
final class VaubanClasses {

    /**
     * The root package of each shipped module: vauban-api, vauban-classloader-spi, vauban-weaver,
     * vauban-classloader, vauban-indexer, vauban-core, vauban-processor, vauban-sjar, vauban-webcontexts and
     * vauban-naming.
     */
    private static final String[] SHIPPED_PACKAGES = {"io.vidocq.vauban"};

    private static final ImportOption NO_TESTS = new ImportOption.DoNotIncludeTests();

    /** The classes Vauban ships, test classes left out. */
    static final JavaClasses SHIPPED =
            new ClassFileImporter().withImportOption(NO_TESTS).importPackages(SHIPPED_PACKAGES);

    /**
     * The shipped classes plus the fixtures of this module's {@code archfixture} packages, each of which sits in
     * a shipped package and breaks one rule there.
     */
    static final JavaClasses SHIPPED_AND_FIXTURES = new ClassFileImporter()
            .withImportOption(location -> NO_TESTS.includes(location)
                    || location.contains("/archfixture/")
                    || location.contains("/ArchFixture"))
            .importPackages(SHIPPED_PACKAGES);

    /**
     * The shipped classes without the documented {@code ThreadLocal} holders
     * ({@link ArchitectureRules#THREAD_LOCAL_HOLDERS}), which {@link ArchitectureRules#NO_THREAD_LOCAL} is
     * applied to: the library rule has no exemption list, so the exemption is the classes it is given.
     */
    static final JavaClasses SHIPPED_WITHOUT_THREAD_LOCAL_HOLDERS = new ClassFileImporter()
            .withImportOption(NO_TESTS)
            .withImportOption(location -> ArchitectureRules.THREAD_LOCAL_HOLDERS.keySet().stream()
                    .noneMatch(holder -> isClassOf(location, holder)))
            .importPackages(SHIPPED_PACKAGES);

    /** One shipped top-level class and its nested classes, test classes left out. */
    static JavaClasses only(String topLevelClass) {
        return new ClassFileImporter()
                .withImportOption(NO_TESTS)
                .withImportOption(location -> isClassOf(location, topLevelClass))
                .importPackages(SHIPPED_PACKAGES);
    }

    private static boolean isClassOf(com.tngtech.archunit.core.importer.Location location, String topLevelClass) {
        String path = topLevelClass.replace('.', '/');
        return location.contains(path + ".class") || location.contains(path + "$");
    }

    private VaubanClasses() {
        // Constants only.
    }
}
