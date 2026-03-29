package fr.vidocq.vauban.maven.module;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record ModuleAnalysisResult(
    Path jarPath,
    String fileName,
    ModuleType type,
    String moduleName,      // null for UNNAMED
    Set<String> packages,   // all packages in the JAR
    List<String> issues     // warnings/errors
) {}
