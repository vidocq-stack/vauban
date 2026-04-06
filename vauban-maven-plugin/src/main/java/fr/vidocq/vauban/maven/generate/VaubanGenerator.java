package fr.vidocq.vauban.maven.generate;

import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import fr.vidocq.vauban.indexer.scanner.JarScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Core build-time logic for CDI bean discovery across dependency JARs.
 * <p>
 * Scans dependency JARs and project classes, runs CDI bean discovery,
 * and writes a {@code META-INF/vauban-beans.list} file listing all
 * discovered bean class names. This file is read at runtime by
 * {@code VaubanContainer.Builder.scanClasspath()}.
 * <p>
 * This class has no Maven dependency and is independently testable.
 */
public final class VaubanGenerator {

    /** Location of the bean list file within the classpath. */
    public static final String BEANS_LIST_PATH = "META-INF/vauban-beans.list";

    /**
     * Configuration for the generator.
     *
     * @param dependencyJars   JAR files to scan for CDI beans
     * @param projectClassesDir project's compiled classes directory (may be null)
     * @param outputDir         where to write META-INF/vauban-beans.list
     */
    public record Config(
            List<Path> dependencyJars,
            Path projectClassesDir,
            Path outputDir
    ) {
        public Config {
            dependencyJars = List.copyOf(dependencyJars);
        }
    }

    private VaubanGenerator() {}

    /**
     * Scan dependencies and project classes, discover CDI beans,
     * and write the bean list file.
     *
     * @param config generation configuration
     * @return result with discovered beans and any warnings
     * @throws IOException if scanning or writing fails
     */
    public static GenerationResult generate(Config config) throws IOException {
        var indexBuilder = new IndexBuilder();
        var warnings = new ArrayList<String>();

        // 1. Scan dependency JARs
        for (var jar : config.dependencyJars()) {
            if (!Files.isRegularFile(jar)) {
                warnings.add("Skipping non-existent JAR: " + jar);
                continue;
            }
            try {
                var classInfos = JarScanner.scan(jar);
                indexBuilder.addAll(classInfos);
            } catch (IOException e) {
                warnings.add("Failed to scan JAR " + jar.getFileName() + ": " + e.getMessage());
            }
        }

        // 2. Scan project classes directory
        if (config.projectClassesDir() != null && Files.isDirectory(config.projectClassesDir())) {
            scanClassesDirectory(config.projectClassesDir(), indexBuilder, warnings);
        }

        // 3. Build merged index
        var index = indexBuilder.build();
        if (index.size() == 0) {
            return new GenerationResult(List.of(), warnings);
        }

        // 4. Run CDI bean discovery
        var discovery = new BeanDiscovery(index);
        var beans = discovery.discoverBeans();

        // 5. Collect discovered bean class names
        var beanClassNames = beans.stream()
                .map(BeanDescriptor::beanClass)
                .map(dotName -> dotName.value())
                .distinct()
                .sorted()
                .toList();

        // 6. Write META-INF/vauban-beans.list
        if (!beanClassNames.isEmpty()) {
            writeBeansList(config.outputDir(), beanClassNames);
        }

        return new GenerationResult(beanClassNames, warnings);
    }

    private static void scanClassesDirectory(Path classesDir, IndexBuilder indexBuilder,
                                              List<String> warnings) throws IOException {
        Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".class")) {
                    try {
                        var bytes = Files.readAllBytes(file);
                        indexBuilder.add(ClassFileScanner.scan(bytes));
                    } catch (Exception e) {
                        warnings.add("Failed to scan " + file.getFileName() + ": " + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeBeansList(Path outputDir, List<String> beanClassNames) throws IOException {
        var beansListFile = outputDir.resolve(BEANS_LIST_PATH);
        Files.createDirectories(beansListFile.getParent());

        var lines = new ArrayList<String>();
        lines.add("# Vauban discovered beans — generated at build time");
        lines.addAll(beanClassNames);

        Files.write(beansListFile, lines, StandardCharsets.UTF_8);
    }
}
