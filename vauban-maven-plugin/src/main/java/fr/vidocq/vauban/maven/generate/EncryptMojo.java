package fr.vidocq.vauban.maven.generate;

import fr.vidocq.vauban.sjar.SjarEncryptor;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Maven goal that encrypts the project JAR into an SJAR (Secure JAR).
 * The encrypted artifact replaces the main project artifact and is additionally
 * attached with classifier "sjar". The original unencrypted JAR is not published.
 *
 * <p>Key resolution (in order):
 * <ol>
 *   <li>Maven property {@code vauban.sjar.key} (hex-encoded 256-bit key)</li>
 *   <li>Environment variable {@code VAUBAN_SJAR_KEY}</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * <plugin>
 *   <groupId>fr.vidocq.vauban</groupId>
 *   <artifactId>vauban-maven-plugin</artifactId>
 *   <executions>
 *     <execution>
 *       <goals><goal>encrypt</goal></goals>
 *       <configuration>
 *         <keyAlias>my-app-key</keyAlias>
 *       </configuration>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "encrypt",
      defaultPhase = LifecyclePhase.PACKAGE,
      threadSafe = true)
public class EncryptMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Key alias stored in the SJAR metadata. Identifies which key was used.
     */
    @Parameter(property = "vauban.sjar.keyAlias", defaultValue = "default")
    private String keyAlias;

    /**
     * Hex-encoded AES-256 key (64 hex characters). Can also be set via
     * environment variable VAUBAN_SJAR_KEY.
     */
    @Parameter(property = "vauban.sjar.key")
    private String sjarKey;

    /**
     * Whether to skip encryption.
     */
    @Parameter(property = "vauban.sjar.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Vauban: SJAR encryption skipped");
            return;
        }

        var artifact = project.getArtifact();
        if (artifact == null || artifact.getFile() == null) {
            throw new MojoExecutionException(
                    "No project artifact found. The encrypt goal must run after the package phase.");
        }

        var jarPath = artifact.getFile().toPath();
        if (!jarPath.toString().endsWith(".jar")) {
            getLog().warn("Vauban: project artifact is not a JAR, skipping encryption: " + jarPath);
            return;
        }

        try {
            var key = resolveKey();
            var sjarName = jarPath.getFileName().toString().replace(".jar", ".sjar");
            var sjarPath = buildDirectory.toPath().resolve(sjarName);

            getLog().info("Vauban: encrypting " + jarPath.getFileName() + " -> " + sjarName);
            SjarEncryptor.encrypt(jarPath, sjarPath, key, keyAlias);

            // Attach SJAR with classifier "sjar" for explicit dependency resolution
            attachArtifact(sjarPath.toFile());

            getLog().info("Vauban: SJAR created and attached — " + sjarName
                    + " (classifier=sjar, key alias: " + keyAlias + ")");

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("SJAR encryption failed", e);
        }
    }

    private SecretKey resolveKey() throws MojoExecutionException {
        // 1. Maven property
        if (sjarKey != null && !sjarKey.isBlank()) {
            return parseHexKey(sjarKey.strip());
        }

        // 2. Environment variable
        var envKey = System.getenv(SjarKeyProvider.ENV_KEY);
        if (envKey != null && !envKey.isBlank()) {
            return parseHexKey(envKey.strip());
        }

        throw new MojoExecutionException(
                "No SJAR encryption key found. Set -Dvauban.sjar.key=<hex> or "
                        + SjarKeyProvider.ENV_KEY + " environment variable. "
                        + "Generate a key with: java -m fr.vidocq.vauban.sjar generate-key");
    }

    private static SecretKey parseHexKey(String hex) throws MojoExecutionException {
        try {
            var bytes = HexFormat.of().parseHex(hex);
            if (bytes.length != 32) {
                throw new MojoExecutionException(
                        "SJAR key must be 256-bit (64 hex chars), got " + hex.length() + " chars");
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Invalid hex key: " + e.getMessage(), e);
        }
    }

    private void attachArtifact(File sjarFile) {
        var attachedArtifact = new org.apache.maven.artifact.DefaultArtifact(
                project.getGroupId(), project.getArtifactId(), project.getVersion(),
                "compile", "sjar", "sjar",
                new org.apache.maven.artifact.handler.DefaultArtifactHandler("sjar"));
        attachedArtifact.setFile(sjarFile);
        project.addAttachedArtifact(attachedArtifact);
    }
}
