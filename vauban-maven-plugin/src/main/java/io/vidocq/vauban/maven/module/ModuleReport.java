package io.vidocq.vauban.maven.module;

import java.util.List;

public final class ModuleReport {

    private ModuleReport() {}

    /**
     * Generate a human-readable report of module analysis results.
     */
    public static String generate(List<ModuleAnalysisResult> results, List<SplitPackage> splitPackages) {
        var sb = new StringBuilder();
        sb.append("=== JPMS Module Analysis Report ===\n\n");

        int explicit = 0, automatic = 0, unnamed = 0;

        for (var r : results) {
            var icon = switch (r.type()) {
                case EXPLICIT -> "[OK]    ";
                case AUTOMATIC -> "[WARN]  ";
                case UNNAMED -> "[ERROR] ";
            };
            sb.append(icon).append(r.fileName());
            if (r.moduleName() != null) {
                sb.append(" (").append(r.moduleName()).append(")");
            }
            sb.append(" - ").append(r.type().name().toLowerCase());
            sb.append(", ").append(r.packages().size()).append(" packages");
            sb.append("\n");

            for (var issue : r.issues()) {
                sb.append("         -> ").append(issue).append("\n");
            }

            switch (r.type()) {
                case EXPLICIT -> explicit++;
                case AUTOMATIC -> automatic++;
                case UNNAMED -> unnamed++;
            }
        }

        sb.append("\nSummary: ").append(results.size()).append(" JARs analyzed");
        sb.append(" (").append(explicit).append(" explicit, ");
        sb.append(automatic).append(" automatic, ");
        sb.append(unnamed).append(" unnamed)\n");

        if (!splitPackages.isEmpty()) {
            sb.append("\n=== Split Packages (ERRORS) ===\n");
            for (var sp : splitPackages) {
                sb.append("  Package '").append(sp.packageName())
                    .append("' found in: ").append(sp.jarFiles()).append("\n");
            }
        }

        return sb.toString();
    }
}
