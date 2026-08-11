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
package io.vidocq.vauban.weaver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The hand-off between the container's load-time weaving detection and
 * {@link WeavingAgent}: a plain-text file listing exactly which classes the
 * {@link WeavingTransformer} must touch, one directive per line:
 *
 * <pre>
 * BEAN &lt;binary-name&gt; NO_ARG|MARKER
 * PROXY &lt;binary-name&gt;
 * </pre>
 *
 * <p>Anything not named in the plan is never transformed — the agent has no
 * classpath-wide heuristics.
 *
 * @param beans   bean binary name → how its woven marker chains to the superclass
 * @param proxies binary names of {@code <Bean>_ClientProxy} classes to retarget
 */
public record WeavingPlan(Map<String, ProxyLinkWeaver.SuperChain> beans, Set<String> proxies) {

    public WeavingPlan {
        beans = Map.copyOf(beans);
        proxies = Set.copyOf(proxies);
    }

    public boolean isEmpty() {
        return beans.isEmpty() && proxies.isEmpty();
    }

    /** Serializes to the one-directive-per-line text form. */
    public String render() {
        var sb = new StringBuilder();
        beans.forEach((name, chain) ->
                sb.append("BEAN ").append(name).append(' ').append(chain).append('\n'));
        proxies.forEach(name -> sb.append("PROXY ").append(name).append('\n'));
        return sb.toString();
    }

    public void writeTo(Path file) throws IOException {
        Files.writeString(file, render(), StandardCharsets.UTF_8);
    }

    /** Parses the text form; blank lines are ignored, malformed lines are rejected. */
    public static WeavingPlan parse(String text) {
        var beans = new LinkedHashMap<String, ProxyLinkWeaver.SuperChain>();
        var proxies = new LinkedHashSet<String>();
        for (var line : text.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.toList())) {
            var parts = line.split(" ");
            switch (parts[0]) {
                case "BEAN" -> {
                    if (parts.length != 3) throw new IllegalArgumentException("Malformed plan line: " + line);
                    beans.put(parts[1], ProxyLinkWeaver.SuperChain.valueOf(parts[2]));
                }
                case "PROXY" -> {
                    if (parts.length != 2) throw new IllegalArgumentException("Malformed plan line: " + line);
                    proxies.add(parts[1]);
                }
                default -> throw new IllegalArgumentException("Malformed plan line: " + line);
            }
        }
        return new WeavingPlan(beans, proxies);
    }

    public static WeavingPlan load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read weaving plan " + file, e);
        }
    }
}
