package io.vidocq.vauban.maven.generate;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Maven goal that packages the application as a distribution ZIP containing
 * launch scripts and all dependency JARs.
 *
 * <p>Produces:
 * <pre>
 * target/myapp-1.0-dist.zip
 *   myapp-1.0/
 *     bin/run.sh    — Unix launch script
 *     bin/run.cmd   — Windows launch script
 *     lib/*.jar     — application JAR + all dependencies
 * </pre>
 *
 * <p>The ZIP is attached as a Maven artifact (classifier=dist, type=zip).
 */
@Mojo(name = "dist",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
      threadSafe = true)
public class DistMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Fully qualified main class name.
     */
    @Parameter(property = "vauban.dist.mainClass", required = true)
    private String mainClass;

    /**
     * Root directory name inside the ZIP (default: artifactId-version).
     */
    @Parameter(property = "vauban.dist.name")
    private String distName;

    /**
     * Name of the launch scripts (default: "run").
     */
    @Parameter(property = "vauban.dist.scriptName", defaultValue = "run")
    private String scriptName;

    /**
     * Additional JVM arguments for the launch scripts.
     */
    @Parameter(property = "vauban.dist.jvmArgs", defaultValue = "")
    private String jvmArgs;

    /**
     * Whether to skip distribution packaging.
     */
    @Parameter(property = "vauban.dist.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Vauban: distribution packaging skipped");
            return;
        }

        var artifact = project.getArtifact();
        if (artifact == null || artifact.getFile() == null) {
            throw new MojoExecutionException(
                    "No project artifact. The dist goal must run after the package phase.");
        }

        if (distName == null || distName.isBlank()) {
            distName = project.getArtifactId() + "-" + project.getVersion();
        }

        try {
            var distDir = buildDirectory.toPath().resolve(distName);
            var libDir = distDir.resolve("lib");
            var binDir = distDir.resolve("bin");
            Files.createDirectories(libDir);
            Files.createDirectories(binDir);

            // 1. Copy project JAR
            var projectJar = artifact.getFile().toPath();
            Files.copy(projectJar, libDir.resolve(projectJar.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 2. Copy all dependency JARs
            int depCount = 0;
            for (var dep : project.getArtifacts()) {
                var depFile = dep.getFile();
                if (depFile != null && depFile.exists()) {
                    var targetName = depFile.getName();
                    if (depFile.isDirectory()) {
                        // Reactor dependency (target/classes) — skip, it's already in the project JAR
                        continue;
                    }
                    Files.copy(depFile.toPath(), libDir.resolve(targetName),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    depCount++;
                }
            }

            // 3. Generate launch scripts
            var jvmArgsStr = (jvmArgs != null && !jvmArgs.isBlank()) ? jvmArgs + " " : "";
            writeShScript(binDir.resolve(scriptName + ".sh"), jvmArgsStr);
            writeCmdScript(binDir.resolve(scriptName + ".cmd"), jvmArgsStr);

            // 4. Create ZIP
            var zipName = project.getArtifactId() + "-" + project.getVersion() + "-dist.zip";
            var zipPath = buildDirectory.toPath().resolve(zipName);
            createZip(distDir, zipPath);

            // 5. Attach as Maven artifact
            var attachedArtifact = new org.apache.maven.artifact.DefaultArtifact(
                    project.getGroupId(), project.getArtifactId(), project.getVersion(),
                    "compile", "zip", "dist",
                    new org.apache.maven.artifact.handler.DefaultArtifactHandler("zip"));
            attachedArtifact.setFile(zipPath.toFile());
            project.addAttachedArtifact(attachedArtifact);

            getLog().info("Vauban: distribution created — " + zipName
                    + " (" + (depCount + 1) + " JARs, scripts: " + scriptName + ".sh/.cmd)");

        } catch (IOException e) {
            throw new MojoExecutionException("Distribution packaging failed", e);
        }
    }

    private void writeShScript(Path path, String jvmArgsStr) throws IOException {
        var script = """
                #!/bin/bash
                SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
                APP_HOME="$(dirname "$SCRIPT_DIR")"
                exec java $JAVA_OPTS %s-cp "$APP_HOME/lib/*" %s "$@"
                """.formatted(jvmArgsStr, mainClass);
        Files.writeString(path, script, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException e) {
            // Windows — no POSIX permissions
        }
    }

    private void writeCmdScript(Path path, String jvmArgsStr) throws IOException {
        var script = """
                @echo off
                set APP_HOME=%%~dp0..
                java %%JAVA_OPTS%% %s-cp "%%APP_HOME%%\\lib\\*" %s %%*
                """.formatted(jvmArgsStr, mainClass);
        Files.writeString(path, script, StandardCharsets.UTF_8);
    }

    private void createZip(Path sourceDir, Path zipPath) throws IOException {
        var baseName = sourceDir.getFileName().toString();
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            Files.walkFileTree(sourceDir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    var entryName = baseName + "/" + sourceDir.relativize(file).toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
