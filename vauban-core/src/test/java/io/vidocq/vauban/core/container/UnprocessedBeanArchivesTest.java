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

import io.vidocq.vauban.core.container.beansxml.AnnotatedFixture;
import io.vidocq.vauban.core.container.beansxml.PlainFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code scanUnprocessedBeanArchives}: the bean archives that no Vauban build step indexed, and only those. An
 * IDE build of a Vidocq application runs the annotation processors but never {@code vidocq:generate}, which indexes
 * the beans of dependency jars compiled without Vauban: at boot, the runtime scans those jars instead
 * (Vidocq/vidocq#83). An archive Vauban already processed carries its own index, read by {@code scanClasspath}:
 * scanning it again would register its beans twice.
 */
class UnprocessedBeanArchivesTest {

    private static final String ANNOTATED = "<beans bean-discovery-mode=\"annotated\" version=\"4.0\"/>";
    private static final String ALL = "<beans bean-discovery-mode=\"all\" version=\"4.0\"/>";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("an unprocessed bean archive is scanned, and reported")
    void scansAnUnprocessedArchive() throws Exception {
        Path jar = jar("plain-lib.jar", ANNOTATED);
        List<URL> scanned = new ArrayList<>();

        try (var loader = loader(jar); var container = VaubanContainer.builder().classLoader(loader)
                .scanUnprocessedBeanArchives(scanned::add).build()) {
            assertTrue(isBean(container, AnnotatedFixture.class), "AnnotatedFixture is a bean");
            assertFalse(isBean(container, PlainFixture.class), "annotated mode: PlainFixture is no bean");
        }
        List<URL> ours = fromTmp(scanned);
        assertEquals(1, ours.size(), scanned.toString());
        assertTrue(ours.getFirst().toString().contains("plain-lib.jar"), scanned.toString());
    }

    @Test
    @DisplayName("an archive with a Vauban index or the processed marker is left to scanClasspath")
    void skipsProcessedArchives() throws Exception {
        for (String marker : List.of("META-INF/vauban-beans.list", "META-INF/vauban-bce-processed",
                "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider")) {
            Path jar = jar("processed-" + marker.hashCode() + ".jar", ALL, marker);
            List<URL> scanned = new ArrayList<>();

            try (var loader = loader(jar); var container = VaubanContainer.builder().classLoader(loader)
                    .scanUnprocessedBeanArchives(scanned::add).build()) {
                assertFalse(isBean(container, PlainFixture.class), marker + ": the archive must not be scanned");
            }
            assertEquals(List.of(), fromTmp(scanned), marker);
        }
    }

    @Test
    @DisplayName("a directory bean archive without a Vauban index is scanned too")
    void scansAnUnprocessedDirectory() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("classes"));
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/beans.xml"), ALL);
        for (Class<?> fixture : new Class<?>[]{AnnotatedFixture.class, PlainFixture.class}) {
            Path target = root.resolve(entryName(fixture));
            Files.createDirectories(target.getParent());
            Files.copy(classFile(fixture), target);
        }
        List<URL> scanned = new ArrayList<>();

        try (var loader = loader(root); var container = VaubanContainer.builder().classLoader(loader)
                .scanUnprocessedBeanArchives(scanned::add).build()) {
            assertTrue(isBean(container, PlainFixture.class), "all mode: PlainFixture is a bean");
        }
        assertEquals(1, fromTmp(scanned).size(), scanned.toString());
    }

    /** The archives of this test: the test class path may hold bean archives of its own. */
    private List<URL> fromTmp(List<URL> scanned) {
        String root = tmp.toUri().toString().replaceFirst("^file:/+", "");
        return scanned.stream().filter(url -> url.toString().contains(root)).toList();
    }

    private static boolean isBean(VaubanContainer container, Class<?> type) {
        return !container.getBeanManager().getBeans(type).isEmpty();
    }

    private static URLClassLoader loader(Path archive) throws IOException {
        return new URLClassLoader(new URL[]{archive.toUri().toURL()}, UnprocessedBeanArchivesTest.class.getClassLoader());
    }

    private Path jar(String name, String beansXml, String... markers) throws IOException, URISyntaxException {
        Path jar = tmp.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("META-INF/beans.xml"));
            zip.write(beansXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String marker : markers) {
                zip.putNextEntry(new JarEntry(marker));
                zip.closeEntry();
            }
            for (Class<?> fixture : new Class<?>[]{AnnotatedFixture.class, PlainFixture.class}) {
                zip.putNextEntry(new JarEntry(entryName(fixture)));
                zip.write(Files.readAllBytes(classFile(fixture)));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static String entryName(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    private static Path classFile(Class<?> type) throws URISyntaxException {
        return Path.of(type.getResource(type.getSimpleName() + ".class").toURI());
    }
}
