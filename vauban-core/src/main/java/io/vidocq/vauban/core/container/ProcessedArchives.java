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
package io.vidocq.vauban.core.container;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The archives a Vauban build step processed, each found by its {@code META-INF/vauban-bce-processed} marker, and
 * whether a class comes from one of them. The marker is a resource, found by its URL; a class names its archive by
 * its code source. The two spell one location differently: under the Vauban class loader a jar's entry is
 * {@code jar:file:///a/app.jar!/…} and its classes' code source {@code file:/a/app.jar} (BUG-20261115-07). So they are
 * compared by location, not by spelling: the normalised path of a local file or directory, and the normalised URI of
 * whatever names none.
 */
final class ProcessedArchives {

    /** The schemes whose URL names an entry of the archive that the URL inside names: {@code jar:<archive>!/<entry>}. */
    private static final List<String> ARCHIVE_SCHEMES = List.of("jar:", "vauban:");
    private static final String ENTRY_SEPARATOR = "!/";
    private static final String FILE_SCHEME = "file:";

    /** One location per archive: a {@link Path}, or the {@link String} of a normalised URI. */
    private final Set<Object> locations = new HashSet<>();
    /** The answer for each code source already asked about, by its URL: one parse per archive, not per class. */
    private final Map<String, Boolean> answers = new HashMap<>();

    /** Records the archive that holds {@code resource}, the URL of the resource {@code name}. */
    void addArchiveOf(URL resource, String name) {
        locations.add(location(rootOf(resource.toExternalForm(), name)));
        answers.clear();
    }

    /** Whether {@code clazz} was defined from a recorded archive; a class without a code source was not. */
    boolean holds(Class<?> clazz) {
        if (locations.isEmpty()) {
            return false;
        }
        var codeSource = clazz.getProtectionDomain().getCodeSource();
        var url = codeSource == null ? null : codeSource.getLocation();
        return url != null
                && answers.computeIfAbsent(url.toExternalForm(), spelling -> locations.contains(location(spelling)));
    }

    boolean isEmpty() {
        return locations.isEmpty();
    }

    /**
     * The root of the archive a resource belongs to, from the resource's URL: without its fragment, such as a
     * multi-release jar's {@code #runtime}, and without the resource's name. {@code jar:file:/a.jar!/name} gives
     * {@code jar:file:/a.jar!/}, {@code file:/a/classes/name} gives {@code file:/a/classes/}; a URL that does not end
     * with the name is kept whole.
     */
    static String rootOf(String resourceUrl, String name) {
        var url = withoutFragment(resourceUrl);
        return url.endsWith(name) ? url.substring(0, url.length() - name.length()) : url;
    }

    /**
     * One key per location, whatever its spelling: the absolute normalised path of a local file or directory, the jar
     * a {@code jar:} or {@code vauban:} URL of the jar's root names, and, for what names no local file (a module of a
     * run-time image, a nested jar), the normalised URI without a trailing slash.
     */
    static Object location(String url) {
        var archive = archiveOf(withoutFragment(url));
        if (archive.startsWith(FILE_SCHEME)) {
            var path = localPath(archive);
            if (path != null) {
                return path;
            }
        }
        var uri = uri(archive);
        var normalised = uri == null ? archive : uri.normalize().toASCIIString();
        return normalised.endsWith("/") ? normalised.substring(0, normalised.length() - 1) : normalised;
    }

    private static String withoutFragment(String url) {
        int fragment = url.indexOf('#');
        return fragment < 0 ? url : url.substring(0, fragment);
    }

    /** {@code jar:file:/a.jar!/} gives {@code file:/a.jar}; a nested jar, with one more separator, is kept whole. */
    private static String archiveOf(String url) {
        for (var scheme : ARCHIVE_SCHEMES) {
            if (url.startsWith(scheme)
                    && url.indexOf(ENTRY_SEPARATOR) == url.length() - ENTRY_SEPARATOR.length()) {
                return url.substring(scheme.length(), url.length() - ENTRY_SEPARATOR.length());
            }
        }
        return url;
    }

    /** The path a {@code file:} URL names; {@code null} for one with a host, which names no local path. */
    private static Path localPath(String fileUrl) {
        var uri = uri(fileUrl);
        if (uri == null) {
            return null;
        }
        try {
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | FileSystemNotFoundException _) {
            return null;
        }
    }

    /**
     * The URI a URL writes. A URL may hold characters a URI cannot, such as the raw space {@code java.io.File#toURL}
     * leaves: they are escaped. {@code null} when no URI can hold it.
     */
    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException _) {
            return escaped(url);
        }
    }

    private static URI escaped(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            return new URI(url.substring(0, colon), url.substring(colon + 1), null);
        } catch (URISyntaxException _) {
            return null;
        }
    }
}
