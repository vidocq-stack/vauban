package fr.vidocq.vauban.maven.generate;

import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor.BeanKind;
import fr.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator;
import fr.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;
import fr.vidocq.vauban.indexer.scanner.JarScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

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
            return new GenerationResult(List.of(), List.of(), List.of(), warnings);
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
}
