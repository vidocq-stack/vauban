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
package io.vidocq.vauban.maven.generate;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.extensions.FrozenExtensions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

/**
 * Freezes the build compatible extensions of an application once its classes are compiled (VAU-PERF-005): runs them
 * on the deployment the application will start with and writes what they did in the application's output, so that
 * the container start loads it instead of running them. See
 * {@link io.vidocq.vauban.core.container.VaubanContainerBuilder#freezeExtensions}.
 *
 * <p>The deployment is the path the application starts with, its own classes first. The record holds a fingerprint
 * of the bean classes that path declares, and a start uses the record only for the same fingerprint: so the scan
 * must see that path and nothing else. The classes still come from the build tool's own loader first, as the
 * extensions must share the CDI API and Vauban with the container that runs them; only the resources, where the
 * bean indexes and the class files are read, come from the path alone.
 *
 * <p>This class has no Maven dependency and is independently testable.
 */
public final class ExtensionFreezer {

    private ExtensionFreezer() {}

    /**
     * @param deploymentPath  the directories and jars the application starts with, its output directory included
     * @param shared          the loader the classes come from first: the build tool's, which holds Vauban
     * @param output          the application's output directory, where the record goes
     * @param startExtensions class names of extensions to run at each start whatever they asked
     */
    public static FrozenExtensions.Outcome freeze(List<Path> deploymentPath, ClassLoader shared, Path output,
            Collection<String> startExtensions) {
        try (var deployment = new DeploymentLoader(urls(deploymentPath), shared)) {
            return VaubanContainer.builder().classLoader(deployment).scanClasspath()
                    .freezeExtensions(output, startExtensions);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close the loader of the deployment", e);
        }
    }

    /** What a build says about {@code outcome}, one line per item. */
    public static List<String> report(FrozenExtensions.Outcome outcome) {
        if (!outcome.frozen()) {
            return List.of("Vauban: the build compatible extensions are not frozen, the container start runs them: "
                    + outcome.reason() + ".");
        }
        int frozen = outcome.extensions().size() - outcome.startExtensions().size();
        String line = "Vauban: froze " + frozen + " build compatible extension(s); the container start loads what"
                + " they did.";
        if (outcome.startExtensions().isEmpty()) {
            return List.of(line);
        }
        return List.of(line, "Vauban: " + outcome.startExtensions().size() + " extension(s) run again at each start,"
                + " having left work for it: " + String.join(", ", outcome.startExtensions()) + ".");
    }

    private static URL[] urls(List<Path> paths) {
        var urls = new URL[paths.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = paths.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Not a path of the deployment: " + paths.get(i), e);
            }
        }
        return urls;
    }

    /** Classes from the parent first, as any loader; resources from the deployment's path only. */
    private static final class DeploymentLoader extends URLClassLoader {

        DeploymentLoader(URL[] path, ClassLoader shared) {
            super(path, shared);
        }

        @Override
        public URL getResource(String name) {
            return findResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return findResources(name);
        }
    }
}
