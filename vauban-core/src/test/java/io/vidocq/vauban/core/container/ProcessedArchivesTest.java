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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The location of an archive, compared whatever its spelling: the root of the archive that holds a marker resource
 * against the code source of a class (BUG-20261115-07).
 */
@DisplayName("Processed archives: one location, whatever its spelling")
class ProcessedArchivesTest {

    private static final String MARKER = "META-INF/vauban-bce-processed";

    private static Object markerRoot(String resourceUrl) {
        return ProcessedArchives.location(ProcessedArchives.rootOf(resourceUrl, MARKER));
    }

    @Test
    @DisplayName("a jar's entry as the Vauban class loader writes it, and the jar as its code source")
    void vaubanLoaderJar() {
        assertEquals(ProcessedArchives.location("file:/a/app.jar"),
                markerRoot("jar:file:///a/app.jar!/" + MARKER));
    }

    @Test
    @DisplayName("the #runtime fragment of a multi-release jar's entry")
    void multiReleaseFragment() {
        assertEquals(ProcessedArchives.location("file:/a/app.jar"),
                markerRoot("jar:file:///a/app.jar!/" + MARKER + "#runtime"));
    }

    @Test
    @DisplayName("a directory of classes, with or without its trailing slash")
    void directory() {
        assertEquals(ProcessedArchives.location("file:/a/classes/"), markerRoot("file:/a/classes/" + MARKER));
        assertEquals(ProcessedArchives.location("file:///a/classes"), markerRoot("file:/a/classes/" + MARKER));
    }

    @Test
    @DisplayName("a space escaped on one side and raw on the other")
    void escapedAndRawSpace() {
        assertEquals(ProcessedArchives.location("file:/a b/app.jar"),
                markerRoot("jar:file:///a%20b/app.jar!/" + MARKER));
    }

    @Test
    @DisplayName("a non-ASCII character escaped on one side and raw on the other")
    void escapedAndRawNonAscii() {
        assertEquals(ProcessedArchives.location("file:/a/%C3%A9t%C3%A9/app.jar"),
                markerRoot("jar:file:/a/été/app.jar!/" + MARKER));
    }

    @Test
    @DisplayName("an encrypted jar's vauban: entry, and the jar as its code source")
    void encryptedJar() {
        assertEquals(ProcessedArchives.location("file:/a/app.sjar"),
                markerRoot("vauban:file:///a/app.sjar!/" + MARKER));
    }

    @Test
    @DisplayName("a module of a run-time image, which names no file")
    void runtimeImageModule() {
        assertEquals(ProcessedArchives.location("jrt:/app.module"), markerRoot("jrt:/app.module/" + MARKER));
    }

    @Test
    @DisplayName("a module name that names no file, escaped on one side and raw on the other")
    void runtimeImageModuleWithANonAsciiName() {
        assertEquals(ProcessedArchives.location("jrt:/%C3%A9t%C3%A9"), markerRoot("jrt:/été/" + MARKER));
    }

    @Test
    @DisplayName("a path with a dot-dot segment")
    void dotDotSegment() {
        assertEquals(ProcessedArchives.location("file:/a/app.jar"), ProcessedArchives.location("file:/a/lib/../app.jar"));
    }

    @Test
    @DisplayName("a file URL with a host, which names no local path, is compared as a URI")
    void fileUrlWithAHost() {
        assertEquals(ProcessedArchives.location("file://server/share/app.jar"),
                markerRoot("jar:file://server/share/app.jar!/" + MARKER));
    }

    @Test
    @DisplayName("two jars of one name in two directories are two locations")
    void sameNameElsewhere() {
        assertNotEquals(ProcessedArchives.location("file:/a/app.jar"), markerRoot("jar:file:/b/app.jar!/" + MARKER));
    }

    @Test
    @DisplayName("a jar nested in another is not the outer jar")
    void nestedJar() {
        assertNotEquals(ProcessedArchives.location("file:/a/app.jar"),
                markerRoot("jar:file:/a/app.jar!/lib/inner.jar!/" + MARKER));
        assertEquals(ProcessedArchives.location("jar:file:/a/app.jar!/lib/inner.jar!/"),
                markerRoot("jar:file:/a/app.jar!/lib/inner.jar!/" + MARKER));
    }

    @Test
    @DisplayName("a URL that does not end with the resource name is kept whole")
    void foreignResourceUrl() {
        assertEquals("custom:opaque", ProcessedArchives.rootOf("custom:opaque", MARKER));
    }

    @Test
    @DisplayName("a URL with a character a URI cannot hold is compared escaped")
    void urlWithAnIllegalCharacter() {
        assertEquals("custom:%7Ba%7D", ProcessedArchives.location("custom:{a}"));
    }

    @Test
    @DisplayName("a URL no URI can hold is compared as written")
    void unparsableUrl() {
        assertEquals("{a}", ProcessedArchives.location("{a}"));
        assertEquals("1x:{a}", ProcessedArchives.location("1x:{a}"));
    }

    @Test
    @DisplayName("the local jar of a resource, its path decoded; none for a directory or a nested jar "
            + "(BUG-20261115-08)")
    void localJarOf() throws Exception {
        var header = "META-INF/vauban.header";

        assertEquals(Optional.of(Path.of("/a b/été/x.jar")), ProcessedArchives.localJarOf(
                URI.create("jar:file:/a%20b/%C3%A9t%C3%A9/x.jar!/" + header).toURL(), header));
        assertEquals(Optional.of(Path.of("/a/x.jar")),
                ProcessedArchives.localJarOf(URI.create("jar:file:///a/x.jar!/" + header).toURL(), header));
        assertEquals(Optional.empty(),
                ProcessedArchives.localJarOf(URI.create("file:/a/classes/" + header).toURL(), header));
        assertEquals(Optional.empty(), ProcessedArchives.localJarOf(
                URI.create("jar:file:/a/app.jar!/lib/inner.jar!/" + header).toURL(), header));
    }

    @Test
    @DisplayName("an archive recorded from its marker holds the classes it defines, and only those")
    void holds() throws Exception {
        var archives = new ProcessedArchives();
        assertTrue(archives.isEmpty());
        assertFalse(archives.holds(getClass()), "nothing recorded");
        URL ownCodeSource = getClass().getProtectionDomain().getCodeSource().getLocation();
        archives.addArchiveOf(URI.create(ownCodeSource.toURI() + MARKER).toURL(), MARKER);

        assertFalse(archives.isEmpty());
        assertTrue(archives.holds(getClass()), ownCodeSource.toString());
        assertTrue(archives.holds(getClass()), "asked again, answered from what the first question found");
        assertFalse(archives.holds(Test.class), "a class of another archive");
        assertFalse(archives.holds(String.class), "a class without a code source");

        URL junitJar = Test.class.getProtectionDomain().getCodeSource().getLocation();
        archives.addArchiveOf(URI.create("jar:" + junitJar.toURI() + "!/" + MARKER).toURL(), MARKER);
        assertTrue(archives.holds(Test.class), "an archive recorded after a question counts: " + junitJar);
    }
}
