package fr.vidocq.vauban.maven.generate;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven goal that scans dependency JARs and project classes for CDI beans,
 * then writes a {@code META-INF/vauban-beans.list} file for runtime discovery.
 *
 * <p>Usage in pom.xml:
 * <pre>{@code
 * <plugin>
 *   <groupId>fr.vidocq.vauban</groupId>
 *   <artifactId>vauban-maven-plugin</artifactId>
 *   <executions>
 *     <execution>
 *       <goals><goal>generate</goal></goals>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "generate",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
      threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        var log = getLog();
        log.info("Vauban: scanning dependencies for CDI beans...");

        try {
            var dependencyJars = collectDependencyJars();
            var projectClasses = outputDirectory.toPath();
            var classLoader = buildClassLoader(dependencyJars, projectClasses);

            var config = new VaubanGenerator.Config(
                    dependencyJars,
                    projectClasses,
                    projectClasses, // write generated files alongside compiled classes
                    classLoader
            );

            var result = VaubanGenerator.generate(config);

            // Log warnings
            for (var warning : result.warnings()) {
                log.warn(warning);
            }

            // Log results
            if (result.discoveredBeanClasses().isEmpty()) {
                log.info("Vauban: no CDI beans discovered");
            } else {
                log.info("Vauban: discovered " + result.discoveredBeanClasses().size() + " CDI beans, "
                        + "generated " + result.generatedProxies().size() + " proxies, "
                        + result.generatedInterceptors().size() + " interceptor subclasses");
                for (var beanClass : result.discoveredBeanClasses()) {
                    log.debug("  " + beanClass);
                }
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Vauban generation failed", e);
        }
    }

    private List<Path> collectDependencyJars() {
        var jars = new ArrayList<Path>();
        for (var artifact : project.getArtifacts()) {
            var file = artifact.getFile();
            if (file != null) {
                if (file.getName().endsWith(".jar") || file.isDirectory()) {
                    jars.add(file.toPath());
                }
            }
        }
        return jars;
    }

    private java.net.URLClassLoader buildClassLoader(List<Path> jars, Path projectClasses)
            throws java.net.MalformedURLException {
        var urls = new ArrayList<java.net.URL>();
        urls.add(projectClasses.toUri().toURL());
        for (var jar : jars) {
            urls.add(jar.toUri().toURL());
        }
        return new java.net.URLClassLoader(
                urls.toArray(java.net.URL[]::new),
                getClass().getClassLoader()
        );
    }
}
