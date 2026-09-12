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
package io.vidocq.vauban.maven.modularize;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Optional redistribution-safety check: every patched artifact must declare at least one
 * licence in the allow-list. Off by default (nothing is redistributed by the goal), kept
 * for teams that ship the patched jars in an image.
 */
public final class LicenseGate {

    private static final Pattern MIT = Pattern.compile("\\bmit\\b");
    private static final Pattern EDL = Pattern.compile("\\bedl\\b");

    private LicenseGate() {}

    /**
     * Returns one human-readable violation line per artifact whose declared licences are
     * either missing or none of which normalize to an entry of {@code allowed}; empty when
     * every artifact clears the gate.
     */
    public static List<String> violations(Map<String, List<String>> licensesByArtifact, Set<String> allowed) {
        Set<String> ok = allowed.stream().map(LicenseGate::normalize).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (var e : licensesByArtifact.entrySet()) {
            if (e.getValue().isEmpty()) {
                out.add(e.getKey() + ": no licence declared");
                continue;
            }
            boolean any = e.getValue().stream().map(LicenseGate::normalize).anyMatch(ok::contains);
            if (!any) out.add(e.getKey() + ": licence(s) " + e.getValue() + " not in allow-list " + allowed);
        }
        return out;
    }

    static String normalize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("apache") && s.contains("2")) return "apache-2.0";
        if (MIT.matcher(s).find()) return "mit";
        if ((s.contains("epl") || s.contains("eclipse public")) && s.contains("2")) return "epl-2.0";
        if ((s.contains("epl") || s.contains("eclipse public")) && s.contains("1")) return "epl-1.0";
        if (s.contains("bsd") && s.contains("3")) return "bsd-3-clause";
        if (s.contains("bsd") && s.contains("2")) return "bsd-2-clause";
        if (EDL.matcher(s).find()) return "edl-1.0";
        return s.replaceAll("[^a-z0-9.]", "");
    }
}
