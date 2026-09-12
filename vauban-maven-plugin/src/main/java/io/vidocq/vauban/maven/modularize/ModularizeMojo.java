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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Goal {@code modularize}: patches the non-modular jars of the runtime closure with a generated
 * module descriptor (see {@link Modularizer}) into {@code target/vauban-modularized/}. The dev,
 * jlink and package goals pick those copies up automatically. Nothing is installed or deployed.
 *
 * <p>Default phase: {@code prepare-package} — after the application is compiled and before it is
 * assembled, so every downstream goal sees the patched copies. Skip with
 * {@code -Dvauban.modularize.skip=true}.</p>
 *
 * <p>An optional {@code allowedLicenses} allow-list turns the goal into a licence gate: when it is
 * set, every artifact that ends up patched must declare one of the listed licences, otherwise the
 * build fails. Patching rewrites a third-party jar, so the gate is there to make that rewriting an
 * explicit, licence-aware decision.</p>
 */
@Mojo(name = "modularize",
      defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME,
      threadSafe = true)
public class ModularizeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;

    /** Skip the goal entirely. */
    @Parameter(property = "vauban.modularize.skip", defaultValue = "false")
    private boolean skip;

    /**
     * {@code derived} (default) patches only the jars whose automatic module name is derived from
     * their file name; {@code all-automatic} also patches those declaring an
     * {@code Automatic-Module-Name} — which jlink requires.
     */
    @Parameter(property = "vauban.modularize.mode", defaultValue = "derived")
    private String mode;

    /** Restrict patching to these artifactIds. */
    @Parameter
    private List<String> includes;

    /** Never patch these artifactIds. */
    @Parameter
    private List<String> excludes;

    /** artifactId to module name overrides; the JDK-derived automatic name is used by default. */
    @Parameter
    private Map<String, String> moduleNames;

    /** Generate {@code open module} descriptors. */
    @Parameter(property = "vauban.modularize.open", defaultValue = "true")
    private boolean openModules;

    /**
     * Patch a jar even when its own {@code ServiceLoader} lookups cannot be declared legally.
     * By default such a jar is left automatic, because no legal explicit descriptor exists for it.
     */
    @Parameter(property = "vauban.modularize.forceExplicit", defaultValue = "false")
    private boolean forceExplicit;

    /** Optional licence allow-list; empty (the default) leaves the gate off. */
    @Parameter
    private List<String> allowedLicenses;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("vauban:modularize skipped");
            return;
        }
        List<Path> closure = new ArrayList<>();
        Map<Path, String> artifactIdByJar = new HashMap<>();
        Map<String, Artifact> artifactByJarName = new LinkedHashMap<>();
        for (Artifact a : project.getArtifacts()) {
            File f = a.getFile();
            if (f != null && "jar".equals(a.getType()) && f.getName().endsWith(".jar")) {
                Path p = f.toPath();
                closure.add(p);
                artifactIdByJar.put(p, a.getArtifactId());
                artifactByJarName.put(f.getName(), a);
            }
        }
        var options = new Modularizer.Options(modeOf(mode),
                new HashSet<>(includes == null ? List.of() : includes),
                new HashSet<>(excludes == null ? List.of() : excludes),
                moduleNames == null ? Map.of() : moduleNames,
                openModules,
                forceExplicit);
        Modularizer.Result result;
        try {
            result = Modularizer.run(closure, artifactIdByJar, buildDir.toPath(), options, this::log);
        } catch (IllegalStateException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("vauban:modularize failed", e);
        }
        if (allowedLicenses != null && !allowedLicenses.isEmpty()) {
            // The patched copy keeps the file name of the jar it was made from (ModularizedJars),
            // so the file name is enough to find the artifact each patched jar came from.
            Map<String, List<String>> licenses = new LinkedHashMap<>();
            for (Path patched : result.patched()) {
                Artifact a = artifactByJarName.get(patched.getFileName().toString());
                if (a == null) {
                    throw new MojoExecutionException("No artifact matches patched jar " + patched);
                }
                licenses.put(a.getGroupId() + ":" + a.getArtifactId(), licensesOf(a));
            }
            List<String> violations = LicenseGate.violations(licenses, new HashSet<>(allowedLicenses));
            if (!violations.isEmpty()) {
                throw new MojoFailureException("vauban:modularize licence gate:\n  "
                        + String.join("\n  ", violations));
            }
        }
        getLog().info("vauban:modularize — " + result.patched().size() + " jar(s) patched, "
                + result.skipped().size() + " left as is; report: "
                + ModularizedJars.root(buildDir.toPath()).resolve(Modularizer.REPORT_FILE_NAME));
    }

    /**
     * The {@link Modularizer.Mode} named by {@code value}; {@code derived} when it is unset.
     *
     * <p>A typo used to fall through to {@code DERIVED}, so {@code all-automtic} silently produced a
     * build that left every {@code Automatic-Module-Name} jar unpatched and only failed much later,
     * in jlink, pointing at the jars rather than at the typo. An unknown mode is a configuration
     * error and is reported as one.
     */
    static Modularizer.Mode modeOf(String value) throws MojoFailureException {
        if (value == null || value.isBlank()) {
            return Modularizer.Mode.DERIVED;
        }
        String v = value.trim();
        if ("derived".equalsIgnoreCase(v)) {
            return Modularizer.Mode.DERIVED;
        }
        if ("all-automatic".equalsIgnoreCase(v)) {
            return Modularizer.Mode.ALL_AUTOMATIC;
        }
        throw new MojoFailureException("vauban:modularize: unknown mode '" + value
                + "' (expected derived | all-automatic)");
    }

    /**
     * Routes one Modularizer message to the matching Maven level. A dropped {@code uses} or a jar
     * left automatic is a real warning — it changes what the produced image can do — so it has to
     * reach the user as one, not as another info line in a wall of jdeps output.
     */
    private void log(String message) {
        if (message.startsWith("WARN ")) {
            getLog().warn(message.substring(5));
        } else if (message.startsWith("ERROR ")) {
            getLog().error(message.substring(6));
        } else if (message.startsWith("DEBUG ")) {
            getLog().debug(message.substring(6));
        } else {
            getLog().info(message);
        }
    }

    /** The licence names declared by the POM of {@code a}, in declaration order. */
    private List<String> licensesOf(Artifact a) throws MojoExecutionException {
        try {
            ProjectBuildingRequest req =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            req.setResolveDependencies(false);
            req.setProcessPlugins(false);
            MavenProject p = projectBuilder.build(a, req).getProject();
            List<String> names = new ArrayList<>();
            for (License l : p.getLicenses()) {
                names.add(l.getName() == null ? "" : l.getName());
            }
            return names;
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot resolve licences of " + a, e);
        }
    }
}
