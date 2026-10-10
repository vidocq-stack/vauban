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
package io.vidocq.vauban.core.extensions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The contexts build-compatible extensions registered at build time
 * ({@code MetaAnnotations.addContext}), as the processor writes them for the container:
 * {@value #PATH}, one {@code <scope>;<context class>;<normal>} line per context. An application
 * compiled with the processor does not run its extensions again at boot, and the container reads
 * these lists instead, from every archive of the deployment.
 */
public final class FrozenContexts {

    public static final String PATH = "META-INF/vauban-contexts.list";

    /** One registration: the binary names of the scope annotation and of the context class. */
    public record Entry(String scope, String contextClass, boolean normal) {

        /** The line written for this registration. */
        public String line() {
            return scope + ";" + contextClass + ";" + normal;
        }
    }

    private FrozenContexts() {
    }

    /** The registrations of {@code registrations}, as entries to write. */
    public static List<Entry> of(List<VaubanMetaAnnotations.ContextRegistration> registrations) {
        var entries = new ArrayList<Entry>();
        for (var registration : registrations) {
            entries.add(new Entry(registration.scopeAnnotation().getName(),
                    registration.contextClass().getName(), registration.isNormal()));
        }
        return entries;
    }

    /** Every entry of every {@value #PATH} {@code loader} sees, in order, without duplicates. */
    public static List<Entry> read(ClassLoader loader) {
        Set<Entry> entries = new LinkedHashSet<>();
        try {
            for (URL url : Collections.list(loader.getResources(PATH))) {
                try (var reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.strip();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        String[] parts = line.split(";");
                        if (parts.length != 3) {
                            throw new IllegalStateException("Malformed line in " + url + ": " + line);
                        }
                        entries.add(new Entry(parts[0], parts[1], Boolean.parseBoolean(parts[2])));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + PATH, e);
        }
        return List.copyOf(entries);
    }
}
