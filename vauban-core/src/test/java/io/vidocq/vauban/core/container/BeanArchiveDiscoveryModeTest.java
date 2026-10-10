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
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Default SE discovery ({@link SeContainerInitializer#initialize()} with no class or package added)
 * honours the {@code bean-discovery-mode} of each archive's {@code META-INF/beans.xml}
 * (CDI 4.1 §"Bean archives"): {@code all} makes every concrete class a bean, {@code annotated}
 * (also the mode of an empty {@code beans.xml}) only classes with a bean-defining annotation, and
 * {@code none} no class at all. Vauban used to read every {@code beans.xml} archive as {@code all}.
 */
class BeanArchiveDiscoveryModeTest {

    private static final String BEANS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                                       https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
                   %s
                   version="4.0">
            </beans>
            """;

    @TempDir
    Path tmp;

    @Test
    @DisplayName("bean-discovery-mode=\"all\": every concrete class is a bean")
    void allMode() throws Exception {
        assertDiscovered(directoryArchive(beansXml("bean-discovery-mode=\"all\"")), true, true);
    }

    @Test
    @DisplayName("bean-discovery-mode=\"annotated\": only bean-defining-annotated classes are beans")
    void annotatedMode() throws Exception {
        assertDiscovered(directoryArchive(beansXml("bean-discovery-mode=\"annotated\"")), true, false);
    }

    @Test
    @DisplayName("bean-discovery-mode=\"annotated\" in a jar: only bean-defining-annotated classes are beans")
    void annotatedModeInJar() throws Exception {
        assertDiscovered(jarArchive(beansXml("bean-discovery-mode='annotated'")), true, false);
    }

    @Test
    @DisplayName("bean-discovery-mode=\"none\": the archive contributes no bean")
    void noneMode() throws Exception {
        assertDiscovered(directoryArchive(beansXml("bean-discovery-mode=\"none\"")), false, false);
    }

    @Test
    @DisplayName("empty beans.xml: annotated mode (CDI 4.0 and later)")
    void emptyBeansXml() throws Exception {
        assertDiscovered(directoryArchive(""), true, false);
    }

    @Test
    @DisplayName("beans.xml without bean-discovery-mode: annotated mode")
    void noModeAttribute() throws Exception {
        assertDiscovered(directoryArchive(beansXml("")), true, false);
    }

    @Test
    @DisplayName("a commented-out bean-discovery-mode is ignored")
    void commentedOutMode() throws Exception {
        assertDiscovered(directoryArchive("<!-- bean-discovery-mode=\"all\" -->\n" + beansXml("")), true, false);
    }

    private static String beansXml(String modeAttribute) {
        return BEANS_XML.formatted(modeAttribute);
    }

    private static void assertDiscovered(Path archive, boolean annotated, boolean plain) throws IOException {
        try (var loader = new URLClassLoader(new URL[]{archive.toUri().toURL()},
                BeanArchiveDiscoveryModeTest.class.getClassLoader());
             SeContainer container = SeContainerInitializer.newInstance().setClassLoader(loader).initialize()) {
            assertEquals(annotated, container.select(AnnotatedFixture.class).isResolvable(), "AnnotatedFixture is a bean");
            assertEquals(plain, container.select(PlainFixture.class).isResolvable(), "PlainFixture is a bean");
        }
    }

    private Path directoryArchive(String beansXml) throws IOException, URISyntaxException {
        Path root = Files.createDirectories(tmp.resolve("archive"));
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/beans.xml"), beansXml);
        for (Class<?> fixture : new Class<?>[]{AnnotatedFixture.class, PlainFixture.class}) {
            Path target = root.resolve(entryName(fixture));
            Files.createDirectories(target.getParent());
            Files.copy(classFile(fixture), target);
        }
        return root;
    }

    private Path jarArchive(String beansXml) throws IOException, URISyntaxException {
        Path jar = tmp.resolve("archive.jar");
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("META-INF/beans.xml"));
            zip.write(beansXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
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
