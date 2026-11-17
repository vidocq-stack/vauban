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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;

import java.io.IOException;
import java.util.Optional;

/**
 * The {@code package-info} class of a package, which carries the annotations of the package, {@code @Vetoed} among
 * them. CDI 4.1 ignores every type of a package annotated {@code @Vetoed}: the index holds the {@code package-info} of
 * such a package so that discovery skips its types ({@link BeanDiscovery#isVetoed}), whatever way they reach the
 * deployment (BUG-20261117-01). A {@code package-info} is no type of the deployment: no extension sees it as one.
 */
public final class PackageInfos {

    private static final String SIMPLE_NAME = "package-info";
    private static final DotName VETOED = DotName.of("jakarta.enterprise.inject.Vetoed");

    private PackageInfos() {
    }

    /** Whether {@code className} names the {@code package-info} class of a package. */
    public static boolean isPackageInfo(String className) {
        return className.endsWith("." + SIMPLE_NAME);
    }

    /** The name of the {@code package-info} class of {@code packageName}. */
    public static DotName of(String packageName) {
        return DotName.of(packageName + "." + SIMPLE_NAME);
    }

    /**
     * The {@code package-info} of the package of {@code className}, as the index holds it, when {@code loader} has it
     * among its resources and it carries {@code @Vetoed}. Empty for a class of the unnamed package, for a package with
     * no {@code package-info} class, and for one whose class file cannot be read.
     */
    public static Optional<ClassInfo> vetoed(String className, ClassLoader loader) {
        var packageName = DotName.of(className).packageName();
        if (packageName.isEmpty() || loader == null) {
            return Optional.empty();
        }
        try (var in = loader.getResourceAsStream(of(packageName).toInternal() + ".class")) {
            return in == null ? Optional.empty()
                    : Optional.of(ClassFileScanner.scan(in.readAllBytes())).filter(model -> model.hasAnnotation(VETOED));
        } catch (IOException | IllegalArgumentException _) {
            // A package-info that cannot be read vetoes nothing: the types of its package stay as discovery finds them
            return Optional.empty();
        }
    }
}
