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
package io.vidocq.vauban.maven.enhance;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Opt-in goal (issue #42, Stage 2): enhance a COPY of a third-party dependency jar so a produced
 * type that is not fully public (package-private / protected methods) can still be proxied at build
 * time — the generated proxy lands in the type's own package (in the rewritten jar), and the jar's
 * {@code module-info.class} is rewritten to {@code provides} the generated component provider. The
 * enhanced copies land in {@code target/vauban-enhanced-deps/}; put them on the module path ahead of
 * the originals (they shadow the originals, and their signatures are invalidated — hence opt-in).
 *
 * <p>Off by default: does nothing unless {@code <enhancedTypes>} lists the fully-qualified produced
 * types to enhance.
 */
@Mojo(name = "enhance-dependencies",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public class EnhanceDependenciesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/vauban-enhanced-deps", required = true)
    private File outputDirectory;

    /** Fully-qualified produced types to enhance (opt-in). Empty ⇒ the goal is a no-op. */
    @Parameter
    private List<String> enhancedTypes;

    @Override
    public void execute() throws MojoExecutionException {
        var log = getLog();
        if (enhancedTypes == null || enhancedTypes.isEmpty()) {
            log.info("Vauban enhance-dependencies: no <enhancedTypes> configured — skipping (opt-in).");
            return;
        }
        try {
            var jars = collectDependencyJars();
            var classLoader = buildClassLoader(jars);

            // Group each requested type under the dependency jar that contains it.
            var byJar = new LinkedHashMap<Path, List<String>>();
            for (var fqn : enhancedTypes) {
                var entry = fqn.replace('.', '/') + ".class";
                Path owner = null;
                for (var jar : jars) {
                    if (containsEntry(jar, entry)) {
                        owner = jar;
                        break;
                    }
                }
                if (owner == null) {
                    log.warn("Vauban enhance-dependencies: no dependency jar contains " + fqn);
                    continue;
                }
                byJar.computeIfAbsent(owner, k -> new ArrayList<>()).add(fqn);
            }
            if (byJar.isEmpty()) {
                log.warn("Vauban enhance-dependencies: none of the requested types were found in a "
                        + "dependency jar (a directory dependency cannot be enhanced).");
                return;
            }

            var outDir = outputDirectory.toPath();
            var warnings = new ArrayList<String>();
            for (var e : byJar.entrySet()) {
                var result = DependencyEnhancer.enhance(e.getKey(), coordinatesOf(e.getKey()),
                        outDir, e.getValue(), classLoader, warnings);
                if (result.enhancedJar() != null) {
                    log.info("Vauban: enhanced " + e.getKey().getFileName() + " -> "
                            + result.enhancedJar().getFileName() + " (" + result.enhancedTypes().size()
                            + " type(s); providers: " + result.providerFqns() + ")");
                }
            }
            for (var w : warnings) {
                log.warn(w);
            }
            log.info("Vauban enhance-dependencies: enhanced copies in " + outDir + ". Put them on the "
                    + "module path ahead of the originals — they shadow the originals. Each copy "
                    + "drops the original jar signature and records its origin in the manifest "
                    + "(Vauban-Enhanced-From / -Digest); declare them as such to any SBOM or "
                    + "provenance tooling in your pipeline.");
        } catch (Exception e) {
            throw new MojoExecutionException("Vauban dependency enhancement failed", e);
        }
    }

    /**
     * The Maven coordinates of the dependency that {@code jar} is the artefact of, so the enhanced
     * copy can name its origin. Falls back to the file name when the jar is not a project artefact.
     */
    private String coordinatesOf(Path jar) {
        for (var artifact : project.getArtifacts()) {
            var file = artifact.getFile();
            if (file != null && file.toPath().equals(jar)) {
                return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                        + artifact.getVersion();
            }
        }
        return jar.getFileName().toString();
    }

    private static boolean containsEntry(Path jar, String entry) {
        if (Files.isDirectory(jar)) {
            return Files.exists(jar.resolve(entry));
        }
        try (var jf = new JarFile(jar.toFile())) {
            return jf.getEntry(entry) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Path> collectDependencyJars() {
        var jars = new ArrayList<Path>();
        for (var artifact : project.getArtifacts()) {
            var file = artifact.getFile();
            if (file != null && (file.getName().endsWith(".jar") || file.isDirectory())) {
                jars.add(file.toPath());
            }
        }
        return jars;
    }

    private URLClassLoader buildClassLoader(List<Path> jars) throws MalformedURLException {
        var urls = new ArrayList<URL>();
        for (var jar : jars) {
            urls.add(jar.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
    }
}
