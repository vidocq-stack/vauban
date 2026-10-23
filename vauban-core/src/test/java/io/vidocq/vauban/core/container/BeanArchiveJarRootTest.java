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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.JarURLConnection;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The root URL the bean archive scan builds for a jar, from the URL of its {@code beans.xml}: pinned before the
 * scanner stopped using the deprecated {@code URL(String)} constructor, so that a jar whose path holds a character
 * a URL escapes is still scanned, and reported with a root that opens.
 */
class BeanArchiveJarRootTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a jar in a directory whose name holds a space is scanned and reported with an openable root")
    void jarUnderEscapedPath() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("with space"));
        Path jar = jar(dir.resolve("lib.jar"));
        List<URL> scanned = new ArrayList<>();

        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             var container = VaubanContainer.builder().classLoader(loader)
                     .scanUnprocessedBeanArchives(scanned::add).build()) {
            assertFalse(container.getBeanManager().getBeans(AnnotatedFixture.class).isEmpty(),
                    "AnnotatedFixture is a bean");
        }
        var ours = scanned.stream().filter(url -> url.toString().contains("lib.jar")).toList();
        assertEquals(1, ours.size(), scanned.toString());
        var root = ours.getFirst();
        assertEquals("jar:" + jar.toUri().toURL() + "!/", root.toString());
        var connection = (JarURLConnection) root.openConnection();
        connection.setUseCaches(false);
        try (var jarFile = connection.getJarFile()) {
            assertNotNull(jarFile.getEntry("META-INF/beans.xml"));
        }
    }

    private static Path jar(Path jar) throws IOException, URISyntaxException {
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("META-INF/beans.xml"));
            zip.write("<beans bean-discovery-mode=\"annotated\" version=\"4.0\"/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new JarEntry(AnnotatedFixture.class.getName().replace('.', '/') + ".class"));
            zip.write(Files.readAllBytes(Path.of(AnnotatedFixture.class
                    .getResource(AnnotatedFixture.class.getSimpleName() + ".class").toURI())));
            zip.closeEntry();
        }
        return jar;
    }
}
