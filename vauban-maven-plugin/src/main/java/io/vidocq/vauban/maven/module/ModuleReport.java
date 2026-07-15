/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.maven.module;

import java.util.List;

public final class ModuleReport {

    private ModuleReport() {}

    /**
     * Generate a human-readable report of module analysis results.
     */
    public static String generate(List<ModuleAnalysisResult> results, List<SplitPackage> splitPackages) {
        var sb = new StringBuilder();
        sb.append("=== Java Module Analysis Report ===\n\n");

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
