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

import io.vidocq.vauban.core.container.marked.MarkedBean;
import io.vidocq.vauban.core.container.marked.PackageMark;
import io.vidocq.vauban.core.container.vetoed.PackageVetoedBean;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code package-info} class carries the annotations of its package, {@code @Vetoed} among them: the index holds it
 * for that, and no extension sees it as a type. An {@code @Enhancement} for every type of an archive no Vauban build
 * step processed is never given the {@code package-info} of a package annotated {@code @XmlSchema} or
 * {@code @NullMarked}, nor that of a vetoed package (BUG-20261117-01).
 */
@DisplayName("No extension sees a package-info as a type (BUG-20261117-01)")
class PackageInfoIsNoTypeTest {

    private static final String PACKAGE_INFO = ".package-info";

    /** The classes of the archive: a package annotated {@link PackageMark}, and a vetoed package. */
    private static final List<String> ARCHIVE_CLASSES = List.of(
            MarkedBean.class.getPackageName() + PACKAGE_INFO, PackageMark.class.getName(), MarkedBean.class.getName(),
            PackageVetoedBean.class.getPackageName() + PACKAGE_INFO, PackageVetoedBean.class.getName());

    @TempDir
    Path output;

    @TempDir
    Path archives;

    /** Records every type its {@code @Enhancement} is given. */
    public static class EnhancementRecorder implements BuildCompatibleExtension {

        static final List<String> ENHANCED = new CopyOnWriteArrayList<>();

        @Enhancement(types = Object.class)
        public void enhance(ClassConfig clazz) {
            ENHANCED.add(clazz.info().name());
        }
    }

    @BeforeEach
    @AfterEach
    void reset() {
        EnhancementRecorder.ENHANCED.clear();
    }

    @Test
    @DisplayName("when the extensions run at start, @Enhancement is given no package-info")
    void anEnhancementRunAtStartIsGivenNoPackageInfo() throws IOException {
        try (var loader = loader(jarArchive()); VaubanContainer container = deployment(loader).build()) {
            assertEquals(List.of(EnhancementRecorder.class.getName()), container.extensionsAtStart().ranAtStart());
        }
        assertGivenNoPackageInfo(List.copyOf(EnhancementRecorder.ENHANCED), "at start");
    }

    @Test
    @DisplayName("when the build freezes the extensions, @Enhancement is given no package-info")
    void anEnhancementFrozenAtBuildIsGivenNoPackageInfo() throws IOException {
        Path archive = jarArchive();
        try (var loader = loader(archive)) {
            FrozenExtensions.Outcome outcome = deployment(loader).freezeExtensions(output);
            assertTrue(outcome.frozen(), outcome.reason());
        }
        assertGivenNoPackageInfo(List.copyOf(EnhancementRecorder.ENHANCED), "at build");

        try (var loader = loader(archive); VaubanContainer container = deployment(loader).build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
        }
    }

    private static void assertGivenNoPackageInfo(List<String> enhanced, String when) {
        assertTrue(enhanced.contains(MarkedBean.class.getName()), enhanced::toString);
        assertEquals(List.of(), enhanced.stream().filter(name -> name.endsWith(PACKAGE_INFO)).toList(),
                "the package-info classes @Enhancement is given " + when);
    }

    private VaubanContainerBuilder deployment(ClassLoader loader) {
        return VaubanContainer.builder().classLoader(loader).addBeanClass(EnhancementRecorder.class)
                .scanUnprocessedBeanArchives(archive -> { });
    }

    /** The loader of a start: the build's output first, where the record is, then the archive. */
    private URLClassLoader loader(Path archive) throws IOException {
        return new URLClassLoader(new URL[] {output.toUri().toURL(), archive.toUri().toURL()},
                PackageInfoIsNoTypeTest.class.getClassLoader());
    }

    /** A jar with a {@code beans.xml} of discovery mode {@code annotated}, which no Vauban build step processed. */
    private Path jarArchive() throws IOException {
        Path jar = archives.resolve("marked.jar");
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry("META-INF/beans.xml"));
            zip.write("<beans bean-discovery-mode=\"annotated\" version=\"4.0\"/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String className : ARCHIVE_CLASSES) {
                String entry = className.replace('.', '/') + ".class";
                zip.putNextEntry(new JarEntry(entry));
                try (InputStream in = PackageInfoIsNoTypeTest.class.getClassLoader().getResourceAsStream(entry)) {
                    Objects.requireNonNull(in, entry).transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        return jar;
    }
}
