package fr.vidocq.vauban.maven.generate;

import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor.BeanKind;
import fr.vidocq.vauban.core.enrichment.EnrichmentConfig;
import fr.vidocq.vauban.core.enrichment.IndexEnricher;
import fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator;
import fr.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import fr.vidocq.vauban.indexer.scanner.JarScanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Core build-time logic for CDI bean discovery and code generation.
 * <p>
 * Scans dependency JARs and project classes, runs CDI bean discovery,
 * pre-generates client proxies and interceptor subclasses, and writes
 * a {@code META-INF/vauban-beans.list} file for runtime discovery.
 * <p>
 * This class has no Maven dependency and is independently testable.
 */
public final class VaubanGenerator {

    /** Location of the bean list file within the classpath. */
    public static final String BEANS_LIST_PATH = "META-INF/vauban-beans.list";

    /**
     * Configuration for the generator.
     *
     * @param dependencyJars    JAR files to scan for CDI beans
     * @param projectClassesDir project's compiled classes directory (may be null)
     * @param outputDir         where to write generated files
     * @param classLoader       ClassLoader with all deps + project classes for proxy generation (may be null to skip generation)
     */
    public record Config(
            List<Path> dependencyJars,
            Path projectClassesDir,
            Path outputDir,
            ClassLoader classLoader
    ) {
        public Config {
            dependencyJars = List.copyOf(dependencyJars);
        }

        /** Config without ClassLoader — discovery only, no proxy generation. */
        public Config(List<Path> dependencyJars, Path projectClassesDir, Path outputDir) {
            this(dependencyJars, projectClassesDir, outputDir, null);
        }
    }

    private VaubanGenerator() {}

    /**
     * Scan dependencies and project classes, discover CDI beans,
     * generate proxies/interceptor subclasses, and write the bean list file.
     *
     * @param config generation configuration
     * @return result with discovered beans, generated classes, and warnings
     * @throws IOException if scanning or writing fails
     */
    public static GenerationResult generate(Config config) throws IOException {
        var indexBuilder = new IndexBuilder();
        var warnings = new ArrayList<String>();

        // 1. Scan dependency JARs and class directories
        for (var dep : config.dependencyJars()) {
            if (Files.isDirectory(dep)) {
                // Reactor dependency — scan classes directory
                scanClassesDirectory(dep, indexBuilder, warnings);
            } else if (Files.isRegularFile(dep)) {
                try {
                    var classInfos = JarScanner.scan(dep);
                    indexBuilder.addAll(classInfos);
                } catch (IOException e) {
                    warnings.add("Failed to scan JAR " + dep.getFileName() + ": " + e.getMessage());
                }
            } else {
                warnings.add("Skipping non-existent dependency: " + dep);
            }
        }

        // 2. Scan project classes directory
        if (config.projectClassesDir() != null && Files.isDirectory(config.projectClassesDir())) {
            scanClassesDirectory(config.projectClassesDir(), indexBuilder, warnings);
        }

        // 3. Build merged index
        var index = indexBuilder.build();
        if (index.size() == 0) {
            return new GenerationResult(List.of(), List.of(), List.of(), warnings);
        }

        // 3b. Load enrichment config and enrich the index
        var enrichmentConfig = loadEnrichmentConfig(config, warnings);
        index = IndexEnricher.enrich(index, enrichmentConfig);

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

        // 7. Pre-generate proxies and interceptor subclasses (if ClassLoader provided)
        var generatedProxies = new ArrayList<String>();
        var generatedInterceptors = new ArrayList<String>();

        if (config.classLoader() != null) {
            for (var bean : beans) {
                var className = bean.beanClass().value();
                Class<?> clazz;
                try {
                    clazz = Class.forName(className, false, config.classLoader());
                } catch (ClassNotFoundException e) {
                    warnings.add("Cannot load class for generation: " + className);
                    continue;
                }

                // Client proxy for normal-scoped beans
                if (bean.scope().isNormal()) {
                    try {
                        var generated = RuntimeClientProxyGenerator.generate(clazz);
                        writeClassFile(config.outputDir(), generated.className(), generated.bytecode());
                        generatedProxies.add(generated.className());
                    } catch (Exception e) {
                        warnings.add("Failed to generate proxy for " + className + ": " + e.getMessage());
                    }
                }

                // Interceptor subclass for managed beans with interceptor bindings
                if (bean.kind() == BeanKind.MANAGED
                        && !bean.interceptorBindings().isEmpty()
                        && !java.lang.reflect.Modifier.isFinal(clazz.getModifiers())) {
                    try {
                        var generated = InterceptorSubclassGenerator.generate(clazz);
                        writeClassFile(config.outputDir(), generated.className(), generated.bytecode());
                        generatedInterceptors.add(generated.className());
                    } catch (Exception e) {
                        warnings.add("Failed to generate interceptor subclass for " + className + ": " + e.getMessage());
                    }
                }
            }
        }

        return new GenerationResult(beanClassNames, generatedProxies, generatedInterceptors, warnings);
    }

    private static void scanClassesDirectory(Path classesDir, IndexBuilder indexBuilder,
                                              List<String> warnings) throws IOException {
        Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                var fileName = file.getFileName().toString();
                if (fileName.endsWith(".class") && !fileName.equals("module-info.class")) {
                    try {
                        var bytes = Files.readAllBytes(file);
                        indexBuilder.add(ClassFileScanner.scan(bytes));
                    } catch (Exception | Error e) {
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

    private static void writeClassFile(Path outputDir, String className, byte[] bytecode) throws IOException {
        var classFilePath = outputDir.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(classFilePath.getParent());
        Files.write(classFilePath, bytecode);
    }

    private static final String PROPERTIES_FILE = "vauban-apt.properties";

    private static EnrichmentConfig loadEnrichmentConfig(Config config, List<String> warnings) {
        var merged = EnrichmentConfig.empty();

        // Load from project classes directory
        if (config.projectClassesDir() != null) {
            var propsFile = config.projectClassesDir().resolve(PROPERTIES_FILE);
            if (Files.isRegularFile(propsFile)) {
                try (var is = Files.newInputStream(propsFile)) {
                    merged = merged.merge(EnrichmentConfig.load(is));
                } catch (IOException e) {
                    warnings.add("Failed to read " + PROPERTIES_FILE + " from project: " + e.getMessage());
                }
            }
        }

        // Load from dependency JARs
        for (var dep : config.dependencyJars()) {
            if (!Files.isRegularFile(dep) || !dep.toString().endsWith(".jar")) continue;
            try (var jar = new JarFile(dep.toFile())) {
                var entry = jar.getEntry(PROPERTIES_FILE);
                if (entry != null) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        merged = merged.merge(EnrichmentConfig.load(is));
                    }
                }
            } catch (IOException e) {
                warnings.add("Failed to read " + PROPERTIES_FILE + " from " + dep.getFileName() + ": " + e.getMessage());
            }
        }

        return merged;
    }
}
