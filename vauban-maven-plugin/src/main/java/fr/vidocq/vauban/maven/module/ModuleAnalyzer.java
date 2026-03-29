package fr.vidocq.vauban.maven.module;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

public final class ModuleAnalyzer {

    private ModuleAnalyzer() {}

    /**
     * Analyze a single JAR for JPMS compatibility.
     */
    public static ModuleAnalysisResult analyze(Path jarPath) throws IOException {
        var fileName = jarPath.getFileName().toString();
        var issues = new ArrayList<String>();
        var packages = new LinkedHashSet<String>();
        String moduleName = null;
        ModuleType type = ModuleType.UNNAMED;

        try (var jar = new JarFile(jarPath.toFile())) {
            // Collect all packages
            var entries = jar.entries();
            boolean hasModuleInfo = false;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals("module-info.class")) {
                    hasModuleInfo = true;
                } else if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    var pkg = entry.getName().replace('/', '.');
                    int lastDot = pkg.lastIndexOf('.');
                    if (lastDot > 0) {
                        pkg = pkg.substring(0, lastDot); // remove .class
                        lastDot = pkg.lastIndexOf('.');
                        if (lastDot > 0) {
                            packages.add(pkg.substring(0, lastDot)); // remove ClassName
                        }
                    }
                }
            }

            if (hasModuleInfo) {
                // Explicit module - read module name from module-info.class using Class-File API
                type = ModuleType.EXPLICIT;
                moduleName = readModuleName(jar);
            } else {
                // Check MANIFEST.MF for Automatic-Module-Name
                var manifest = jar.getManifest();
                if (manifest != null) {
                    var autoName = manifest.getMainAttributes().getValue("Automatic-Module-Name");
                    if (autoName != null && !autoName.isBlank()) {
                        type = ModuleType.AUTOMATIC;
                        moduleName = autoName.trim();
                    }
                }
            }

            if (type == ModuleType.UNNAMED) {
                issues.add("No module-info.class and no Automatic-Module-Name in MANIFEST.MF");
                // Derive automatic module name from JAR filename (as JDK would)
                moduleName = deriveAutomaticModuleName(fileName);
                issues.add("Derived module name from filename: " + moduleName);
            }
        }

        return new ModuleAnalysisResult(jarPath, fileName, type, moduleName, packages, issues);
    }

    /**
     * Read the module name from module-info.class using the Class-File API.
     */
    private static String readModuleName(JarFile jar) throws IOException {
        var entry = jar.getEntry("module-info.class");
        if (entry == null) return null;

        try (var is = jar.getInputStream(entry)) {
            var bytes = is.readAllBytes();
            var classModel = java.lang.classfile.ClassFile.of().parse(bytes);
            // The module name is in the Module attribute
            for (var attr : classModel.attributes()) {
                if (attr instanceof java.lang.classfile.attribute.ModuleAttribute ma) {
                    return ma.moduleName().name().stringValue();
                }
            }
        }
        return null;
    }

    /**
     * Derive automatic module name from JAR filename, following JDK rules:
     * - Remove .jar extension
     * - Remove version suffix (digits, dots, dashes at end)
     * - Replace non-alphanumeric chars with dots
     * - Collapse consecutive dots
     * - Remove leading/trailing dots
     */
    static String deriveAutomaticModuleName(String fileName) {
        var name = fileName;
        // Remove .jar
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        // Remove version suffix: -1.2.3, -1.0.0-SNAPSHOT etc.
        name = name.replaceAll("-\\d+([.\\-].*)?$", "");
        // Replace non-alphanumeric with dots
        name = name.replaceAll("[^A-Za-z0-9]", ".");
        // Collapse consecutive dots
        name = name.replaceAll("\\.{2,}", ".");
        // Remove leading/trailing dots
        name = name.replaceAll("^\\.|\\.$", "");
        return name;
    }

    /**
     * Analyze multiple JARs and detect split packages.
     */
    public static List<SplitPackage> detectSplitPackages(List<ModuleAnalysisResult> results) {
        var packageToJars = new LinkedHashMap<String, List<String>>();
        for (var result : results) {
            for (var pkg : result.packages()) {
                packageToJars.computeIfAbsent(pkg, k -> new ArrayList<>()).add(result.fileName());
            }
        }
        return packageToJars.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> new SplitPackage(e.getKey(), List.copyOf(e.getValue())))
            .toList();
    }
}
