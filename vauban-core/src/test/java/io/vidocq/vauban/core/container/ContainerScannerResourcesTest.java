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

import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import io.vidocq.vauban.classloader.spi.PluginContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the container scanner reads what a class loader lists: packages in a directory or a jar, the build-time lists,
 * bean archives and encrypted jars, and what it does when a listing fails, a {@code beans.xml} is malformed, an
 * archive cannot be read or no plugin can open an encrypted jar.
 */
@DisplayName("ContainerScanner: packages, bean archives and encrypted jars as a class loader lists them")
class ContainerScannerResourcesTest {

    private static final String PACKAGE = ContainerScannerResourcesTest.class.getPackageName();
    private static final String PACKAGE_PATH = PACKAGE.replace('.', '/');
    private static final String BEANS_XML = "META-INF/beans.xml";
    private static final String FIXTURE_FILE = "ContainerScannerResourcesTest$Fixture.class";

    @Dependent
    public static class Fixture {
        public String id() {
            return "fixture";
        }
    }

    /** Lists the resources it is given, fails the listings it is told to, and delegates the others. */
    private static final class ScriptedLoader extends ClassLoader {
        private final Map<String, List<URL>> resources = new HashMap<>();
        private final List<String> failing = new ArrayList<>();

        ScriptedLoader() {
            super(ContainerScannerResourcesTest.class.getClassLoader());
        }

        ScriptedLoader serve(String name, URL... urls) {
            resources.put(name, List.of(urls));
            return this;
        }

        ScriptedLoader fail(String name) {
            failing.add(name);
            return this;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (failing.contains(name)) {
                throw new IOException("cannot list " + name);
            }
            var served = resources.get(name);
            return served != null ? Collections.enumeration(served) : super.getResources(name);
        }
    }

    private static Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[0]);
    }

    private static Path jar(Path file, String... entries) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.closeEntry();
            }
        }
        return file;
    }

    private static URL url(Path path) throws IOException {
        return path.toUri().toURL();
    }

    private static URL jarUrl(Path jar, String entry) throws IOException {
        return URI.create("jar:" + jar.toUri() + "!/" + entry).toURL();
    }

    @Test
    @DisplayName("a package whose resources cannot be listed fails the scan")
    void unlistablePackage() {
        var builder = VaubanContainer.builder().classLoader(new ScriptedLoader().fail(PACKAGE_PATH));

        var thrown = assertThrows(ContainerOperationException.class, () -> builder.scanPackage(PACKAGE));

        assertTrue(thrown.getMessage().startsWith("Failed to scan package"), thrown.getMessage());
    }

    @Test
    @DisplayName("a package directory gives its classes, nested ones included, and skips other files")
    void packageDirectory(@TempDir Path dir) throws IOException {
        touch(dir.resolve(FIXTURE_FILE));
        touch(dir.resolve("notes.txt"));
        touch(dir.resolve("sub/Unloadable.class"));
        var builder = VaubanContainer.builder().classLoader(new ScriptedLoader().serve(PACKAGE_PATH, url(dir)));

        try (var container = builder.scanPackage(PACKAGE).build()) {
            assertEquals("fixture", container.select(Fixture.class).id());
        }
    }

    @Test
    @DisplayName("a package in a jar gives the classes under its path only")
    void packageJar(@TempDir Path dir) throws IOException {
        var jar = jar(dir.resolve("beans.jar"), PACKAGE_PATH + "/", PACKAGE_PATH + "/" + FIXTURE_FILE,
                PACKAGE_PATH + "/readme.txt", "other/Elsewhere.class");
        var builder = VaubanContainer.builder()
                .classLoader(new ScriptedLoader().serve(PACKAGE_PATH, jarUrl(jar, PACKAGE_PATH)));

        try (var container = builder.scanPackage(PACKAGE).build()) {
            assertEquals("fixture", container.select(Fixture.class).id());
        }
    }

    @Test
    @DisplayName("an unreadable processed marker is no failure; an unreadable bean list is")
    void classpathLists() {
        var markerFails = new ScriptedLoader().fail("META-INF/vauban-bce-processed");
        var listFails = new ScriptedLoader().fail("META-INF/vauban-beans.list");
        var failingBuilder = VaubanContainer.builder().classLoader(listFails);

        try (var container = VaubanContainer.builder().classLoader(markerFails).scanClasspath()
                .addBeanClass(Fixture.class).build()) {
            assertEquals("fixture", container.select(Fixture.class).id());
        }
        var thrown = assertThrows(UncheckedIOException.class, failingBuilder::scanClasspath);
        assertTrue(thrown.getMessage().contains("vauban-beans.list"), thrown.getMessage());
    }

    @Test
    @DisplayName("a beans.xml with an invalid discovery mode fails the deployment")
    void invalidDiscoveryMode(@TempDir Path dir) throws IOException {
        var beansXml = Files.writeString(touch(dir.resolve(BEANS_XML)), "<beans bean-discovery-mode=\"some\"/>");
        var builder = VaubanContainer.builder().classLoader(new ScriptedLoader().serve(BEANS_XML, url(beansXml)));

        var thrown = assertThrows(DeploymentException.class, builder::scanBeanArchivesFromClasspath);

        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    /** A URL whose content, an empty {@code beans.xml}, is served from memory. */
    private static URL inMemory(String spec) throws IOException {
        return URL.of(URI.create(spec), new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) {
                return new URLConnection(u) {
                    @Override
                    public void connect() {
                        // nothing to connect to
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream("<beans/>".getBytes(StandardCharsets.UTF_8));
                    }
                };
            }
        });
    }

    @Test
    @DisplayName("bean archives that cannot be listed, read or located are skipped")
    void skippedBeanArchives(@TempDir Path dir) throws IOException {
        var queried = Files.writeString(touch(dir.resolve("queried/" + BEANS_XML)), "<beans/>");
        var unlisted = VaubanContainer.builder().classLoader(new ScriptedLoader().fail(BEANS_XML));
        var odd = VaubanContainer.builder().classLoader(new ScriptedLoader().serve(BEANS_XML,
                URI.create(url(queried) + "?query").toURL(),
                jarUrl(jar(dir.resolve("plain.jar"), BEANS_XML), BEANS_XML),
                inMemory("jar:http://example.com/remote.jar!/" + BEANS_XML),
                inMemory("jar:no-entry-separator"),
                inMemory("memory:/" + BEANS_XML)));
        var archives = new ArrayList<URL>();

        unlisted.scanBeanArchivesFromClasspath();
        odd.scanUnprocessedBeanArchives(archives::add);

        assertEquals(1, archives.size(), archives::toString);
        assertTrue(archives.getFirst().toString().endsWith("plain.jar!/"), archives::toString);
    }

    @Test
    @DisplayName("a processed directory archive is left to the indexes; an unprocessed one gives its classes")
    void directoryBeanArchives(@TempDir Path dir) throws IOException {
        var processed = dir.resolve("processed");
        var processedXml = Files.writeString(touch(processed.resolve(BEANS_XML)), "<beans/>");
        touch(processed.resolve("META-INF/vauban-beans.list"));
        var unprocessed = dir.resolve("unprocessed");
        var unprocessedXml = Files.writeString(touch(unprocessed.resolve(BEANS_XML)),
                "<beans bean-discovery-mode=\"all\"/>");
        touch(unprocessed.resolve(PACKAGE_PATH + "/" + FIXTURE_FILE));
        touch(unprocessed.resolve(PACKAGE_PATH + "/Unloadable.class"));
        var archives = new ArrayList<URL>();
        var builder = VaubanContainer.builder()
                .classLoader(new ScriptedLoader().serve(BEANS_XML, url(processedXml), url(unprocessedXml)));

        builder.scanUnprocessedBeanArchives(archives::add);

        assertEquals(List.of(url(unprocessed)), archives);
        assertTrue(builder.forcedDiscoveryClasses.contains(Fixture.class), builder.forcedDiscoveryClasses::toString);
    }

    @Test
    @DisplayName("an encrypted jar no plugin can open is skipped; a marker outside a jar or unlistable is ignored")
    void encryptedJarsOnTheClasspath(@TempDir Path dir) throws IOException {
        var encrypted = jar(dir.resolve("secret.jar"), "META-INF/vauban.header");
        var loader = new ScriptedLoader().serve("META-INF/vauban.header",
                jarUrl(encrypted, "META-INF/vauban.header"), url(touch(dir.resolve("vauban.header"))));
        var unlistable = new ScriptedLoader().fail("META-INF/vauban.header");

        try (var container = VaubanContainer.builder().classLoader(loader).scanClasspath()
                .addBeanClass(Fixture.class).build()) {
            assertEquals("fixture", container.select(Fixture.class).id());
        }
        try (var container = VaubanContainer.builder().classLoader(unlistable).scanClasspath()
                .addBeanClass(Fixture.class).build()) {
            assertEquals("fixture", container.select(Fixture.class).id());
        }
    }

    /** Claims every archive and cannot open any. */
    private static final class BrokenPlugin implements ByteSourcePlugin {
        @Override
        public String protocol() {
            return "broken";
        }

        @Override
        public boolean handles(Path archivePath) {
            return true;
        }

        @Override
        public ArchiveReader open(Path archivePath, PluginContext context) throws IOException {
            throw new IOException("cannot open " + archivePath.getFileName());
        }
    }

    @Test
    @DisplayName("an encrypted jar its plugin cannot open fails with the I/O error")
    void unreadableEncryptedJar(@TempDir Path dir) {
        var sjar = dir.resolve("broken.sjar");
        var builder = VaubanContainer.builder().addByteSourcePlugin(new BrokenPlugin());

        var thrown = assertThrows(UncheckedIOException.class, () -> builder.scanSjar(sjar));

        assertTrue(thrown.getMessage().startsWith("Failed to scan SJAR"), thrown.getMessage());
    }
}
