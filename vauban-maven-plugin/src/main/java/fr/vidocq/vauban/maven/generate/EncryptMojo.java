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
import java.util.HexFormat;

/**
 * Maven goal that encrypts internal classes of a modular JAR in-place.
 * Classes in {@code exports} and {@code opens} packages stay in clear text;
 * all other classes are encrypted with AES-256-GCM.
 *
 * <p>The result is a standard JAR that:
 * <ul>
 *   <li>Can be compiled against (exported types visible)</li>
 *   <li>Can be distributed via Maven (single artifact)</li>
 *   <li>Has internal implementation classes protected</li>
 * </ul>
 *
 * <p>Requires a modular JAR (with {@code module-info.class}).
 *
 * <p>Key resolution (in order):
 * <ol>
 *   <li>Maven property {@code vauban.sjar.key} (hex-encoded 256-bit key)</li>
 *   <li>Environment variable {@code VAUBAN_SJAR_KEY}</li>
 * </ol>
 */
@Mojo(name = "encrypt",
      defaultPhase = LifecyclePhase.PACKAGE,
      threadSafe = true)
public class EncryptMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "vauban.sjar.keyAlias", defaultValue = "default")
    private String keyAlias;

    @Parameter(property = "vauban.sjar.key")
    private String sjarKey;

    @Parameter(property = "vauban.sjar.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Vauban: encryption skipped");
            return;
        }

        var artifact = project.getArtifact();
        if (artifact == null || artifact.getFile() == null) {
            throw new MojoExecutionException(
                    "No project artifact found. The encrypt goal must run after the package phase.");
        }

        var jarPath = artifact.getFile().toPath();
        if (!jarPath.toString().endsWith(".jar")) {
            getLog().warn("Vauban: artifact is not a JAR, skipping: " + jarPath);
            return;
        }

        try {
            var key = resolveKey();
            getLog().info("Vauban: encrypting internal classes in " + jarPath.getFileName());
            SjarEncryptor.encryptJar(jarPath, key, keyAlias);
            getLog().info("Vauban: JAR encrypted in-place (key alias: " + keyAlias + ")");

        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Encryption failed", e);
        }
    }

    private SecretKey resolveKey() throws MojoExecutionException {
        if (sjarKey != null && !sjarKey.isBlank()) {
            return parseHexKey(sjarKey.strip());
        }
        var envKey = System.getenv(SjarKeyProvider.ENV_KEY);
        if (envKey != null && !envKey.isBlank()) {
            return parseHexKey(envKey.strip());
        }
        throw new MojoExecutionException(
                "No encryption key found. Set -Dvauban.sjar.key=<hex> or "
                        + SjarKeyProvider.ENV_KEY + " env variable.");
    }

    private static SecretKey parseHexKey(String hex) throws MojoExecutionException {
        try {
            var bytes = HexFormat.of().parseHex(hex);
            if (bytes.length != 32) {
                throw new MojoExecutionException(
                        "Key must be 256-bit (64 hex chars), got " + hex.length() + " chars");
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Invalid hex key: " + e.getMessage(), e);
        }
    }
}
