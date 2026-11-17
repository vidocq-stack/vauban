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
package io.vidocq.vauban.maven.generate;

import io.vidocq.vauban.maven.generate.vetoed.WatchedService;
import io.vidocq.vauban.maven.generate.vetoed.Watcher;
import io.vidocq.vauban.maven.generate.vetoed.marked.MarkedService;
import io.vidocq.vauban.maven.generate.vetoed.quiet.QuietService;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vauban:generate} on classes no annotation processor handled: it indexes their class files, a package's
 * {@code package-info} among them, so it lists no class of a vetoed package as a bean and generates nothing for one
 * (BUG-20261117-01). No extension sees a {@code package-info} as a type.
 */
@DisplayName("VaubanGenerator — a vetoed package")
class VetoedPackageGenerationTest {

    private static final String QUIET = QuietService.class.getPackageName();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("lists no class of a vetoed package as a bean, and generates nothing for one")
    void aVetoedPackageHoldsNoBean() throws IOException, URISyntaxException {
        var classesDir = tempDir.resolve("classes");
        copyFixtures(classesDir);
        var outputDir = tempDir.resolve("output");

        var result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classesDir, outputDir,
                VetoedPackageGenerationTest.class.getClassLoader()));

        var beans = result.discoveredBeanClasses();
        assertTrue(beans.containsAll(List.of(WatchedService.class.getName(), Watcher.class.getName())),
                beans::toString);
        assertEquals(List.of(), beans.stream().filter(name -> name.startsWith(QUIET + ".")).toList(),
                "the beans of the vetoed package");
        var beansList = Files.readString(outputDir.resolve(VaubanGenerator.BEANS_LIST_PATH));
        assertFalse(beansList.contains(QUIET), beansList);
        assertTrue(Files.exists(classFile(outputDir, WatchedService.class.getName() + "$$Intercepted")),
                "a bean bound to an interceptor gets an intercepted subclass");
        assertFalse(Files.exists(outputDir.resolve(QUIET.replace('.', '/'))),
                "the build generates nothing in the vetoed package");
    }

    /** Records every type its {@code @Enhancement} is given. */
    public static class EnhancementRecorder implements BuildCompatibleExtension {

        static final List<String> ENHANCED = new CopyOnWriteArrayList<>();

        @Enhancement(types = Object.class)
        public void enhance(ClassConfig clazz) {
            ENHANCED.add(clazz.info().name());
        }
    }

    @Test
    @DisplayName("gives an @Enhancement for every type no package-info, vetoed or annotated otherwise")
    void anEnhancementIsGivenNoPackageInfo() throws IOException, URISyntaxException {
        var classesDir = tempDir.resolve("classes");
        copyFixtures(classesDir);
        var services = tempDir.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve("META-INF/services/" + BuildCompatibleExtension.class.getName()),
                EnhancementRecorder.class.getName());

        EnhancementRecorder.ENHANCED.clear();
        try (var loader = new URLClassLoader(new URL[] {services.toUri().toURL()},
                VetoedPackageGenerationTest.class.getClassLoader())) {
            VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classesDir, tempDir.resolve("output"),
                    loader));
        }

        var enhanced = List.copyOf(EnhancementRecorder.ENHANCED);
        assertTrue(enhanced.contains(MarkedService.class.getName()), enhanced::toString);
        assertEquals(List.of(), enhanced.stream().filter(name -> name.endsWith(".package-info")).toList(),
                "the package-info classes @Enhancement is given");
    }

    /** The class files of the fixtures, their vetoed package included, in a directory of classes. */
    private static void copyFixtures(Path classesDir) throws IOException, URISyntaxException {
        var root = Path.of(WatchedService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var fixtures = root.resolve(WatchedService.class.getPackageName().replace('.', '/'));
        try (Stream<Path> files = Files.walk(fixtures)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var target = classesDir.resolve(root.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }

    private static Path classFile(Path dir, String className) {
        return dir.resolve(className.replace('.', '/') + ".class");
    }
}
