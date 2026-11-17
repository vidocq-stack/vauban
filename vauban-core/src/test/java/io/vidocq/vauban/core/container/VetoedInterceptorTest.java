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

import io.vidocq.vauban.core.container.vetoed.PackageVetoedBean;
import io.vidocq.vauban.core.container.vetoed.PackageVetoedOverseer;
import io.vidocq.vauban.core.container.vetoed.PackageVetoedTypeVariableObserver;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI 4.1 ignores a vetoed type altogether, an interceptor included: a class annotated {@code @Vetoed}, or a class of
 * a package annotated {@code @Vetoed}, is no bean, intercepts nothing, and no extension sees it. In both kinds of
 * deployment: one that runs its extensions at start, and one whose extensions the build froze (BUG-20261116-12). The
 * container reads a package's veto on its {@code package-info} class, which it looks up beside every class it indexes,
 * whatever way the class reaches the deployment (BUG-20261117-01).
 */
@DisplayName("Nothing vetoed is a bean or intercepts (BUG-20261116-12, BUG-20261117-01)")
class VetoedInterceptorTest {

    /** The vetoed package, which only its {@code package-info} class says is vetoed. */
    private static final String VETOED_PACKAGE = PackageVetoedOverseer.class.getPackageName();

    /** The types of the vetoed package, every one of them. */
    private static final List<String> VETOED_PACKAGE_TYPES = List.of(PackageVetoedOverseer.class.getName(),
            PackageVetoedBean.class.getName(), PackageVetoedTypeVariableObserver.class.getName());

    /** The classes of the vetoed package, its {@code package-info} among them. */
    private static final List<String> VETOED_PACKAGE_CLASSES = Stream.concat(
            Stream.of(VETOED_PACKAGE + ".package-info"), VETOED_PACKAGE_TYPES.stream()).toList();

    private static final String BEANS_XML = "META-INF/beans.xml";
    private static final String BEANS_LIST = "META-INF/vauban-beans.list";

    @TempDir
    Path output;

    @TempDir
    Path archives;

    @BeforeEach
    @AfterEach
    void reset() {
        RecordingExtension.INTERCEPTORS.clear();
        RecordingExtension.BEANS.clear();
    }

    /** How the classes of the vetoed package reach the deployment. */
    enum Arrival {
        /** Added one by one, the {@code package-info} among them, as the CDI TCK runner hands them. */
        ADDED_CLASSES,
        /** Found by {@code scanPackage}. */
        SCANNED_PACKAGE,
        /** A directory with a {@code beans.xml} of discovery mode {@code all}, which no Vauban build step processed. */
        DIRECTORY_ARCHIVE,
        /** A jar with a {@code beans.xml} of discovery mode {@code annotated}, which no Vauban build step processed. */
        JAR_ARCHIVE,
        /** Added by an extension through {@code ScannedClasses}, from a jar with no {@code beans.xml}. */
        SCANNED_CLASSES,
        /**
         * Named by a hand-written {@code vauban-beans.list}, as an annotation processor from before BUG-20261117-01
         * listed them; no {@code package-info} in the list.
         */
        BEANS_LIST
    }

    // ---- the deployment ----

    /** The interceptor that is not vetoed: what it intercepts comes back in {@code overseer(...)}. */
    @Overseen
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Overseer {

        @AroundInvoke
        Object oversee(InvocationContext invocation) throws Exception {
            return "overseer(" + invocation.proceed() + ")";
        }
    }

    /** A vetoed interceptor: it would wrap what it intercepts in {@code vetoed(...)}. */
    @Vetoed
    @Overseen
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION + 1)
    public static class VetoedOverseer {

        @AroundInvoke
        Object oversee(InvocationContext invocation) throws Exception {
            return "vetoed(" + invocation.proceed() + ")";
        }
    }

    @Overseen
    @Dependent
    public static class OverseenService {

        public String call() {
            return "call";
        }
    }

    /**
     * Records the interceptors and the beans {@code @Registration} gives it, so that the deployment has an extension
     * to freeze.
     */
    public static class RecordingExtension implements BuildCompatibleExtension {

        static final List<String> INTERCEPTORS = new CopyOnWriteArrayList<>();
        static final List<String> BEANS = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void registration(BeanInfo bean) {
            (bean.isInterceptor() ? INTERCEPTORS : BEANS).add(bean.declaringClass().name());
        }
    }

    /** Adds the types of the vetoed package to the deployment, as an extension may add those of a library. */
    public static class ScanningExtension implements BuildCompatibleExtension {

        @Discovery
        public void discovery(ScannedClasses classes) {
            VETOED_PACKAGE_TYPES.forEach(classes::add);
        }
    }

    // ---- the tests ----

    @ParameterizedTest
    @EnumSource(Arrival.class)
    @DisplayName("when the extensions run at start, nothing vetoed is a bean or intercepts")
    void nothingVetoedInterceptsWhenTheExtensionsRunAtStart(Arrival arrival) throws IOException {
        List<Path> path = deploymentPath(arrival);
        try (VaubanContainer container = deployment(arrival, path).build()) {
            assertFalse(container.extensionsAtStart().frozen());
            assertTrue(container.extensionsAtStart().ranAtStart().contains(RecordingExtension.class.getName()),
                    container.extensionsAtStart().ranAtStart()::toString);
            assertNothingVetoedIsABean(container);
            assertSeenByTheExtension(List.copyOf(RecordingExtension.INTERCEPTORS),
                    List.copyOf(RecordingExtension.BEANS), "at start");
        }
    }

    @ParameterizedTest
    @EnumSource(Arrival.class)
    @DisplayName("in a deployment frozen at build, nothing vetoed is a bean or intercepts")
    void nothingVetoedInterceptsInADeploymentFrozenAtBuild(Arrival arrival) throws IOException {
        List<Path> path = deploymentPath(arrival);
        FrozenExtensions.Outcome outcome = deployment(arrival, path).freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());
        List<String> interceptorsAtBuild = List.copyOf(RecordingExtension.INTERCEPTORS);
        List<String> beansAtBuild = List.copyOf(RecordingExtension.BEANS);

        reset();
        try (VaubanContainer container = deployment(arrival, path).build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertEquals(List.of(), container.extensionsAtStart().ranAtStart());
            List<String> seenAtStart = new ArrayList<>(RecordingExtension.INTERCEPTORS);
            seenAtStart.addAll(RecordingExtension.BEANS);
            assertEquals(List.of(), seenAtStart, "the start runs no frozen extension");
            assertNothingVetoedIsABean(container);
        }
        assertSeenByTheExtension(interceptorsAtBuild, beansAtBuild, "at build");
    }

    /**
     * The fingerprint of a frozen deployment leaves out the beans the runtime adds itself, so a start that adds one
     * still matches the build's record; the {@code package-info} of the package of such a bean stays out too. The veto
     * holds all the same: the bean of a vetoed package the runtime adds is no bean.
     */
    @Test
    @DisplayName("a frozen deployment whose runtime adds a bean of a vetoed package matches its record")
    void aHostBeanOfAVetoedPackageLeavesTheRecordValid() {
        List<Path> path = List.of(output);
        FrozenExtensions.Outcome outcome = deployment(path).freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());

        reset();
        try (VaubanContainer container = deployment(path).addHostBean(PackageVetoedBean.class, PackageVetoedBean::new)
                .build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertNothingVetoedIsABean(container);
        }
    }

    // ---- assertions ----

    private static void assertNothingVetoedIsABean(VaubanContainer container) {
        assertEquals("overseer(call)", container.select(OverseenService.class).call());
        assertTrue(container.getBeanManager().getBeans(PackageVetoedBean.class).isEmpty(),
                "a bean class of the vetoed package");
    }

    private static void assertSeenByTheExtension(List<String> interceptors, List<String> beans, String when) {
        assertEquals(List.of(Overseer.class.getName()), interceptors, "the interceptors the extension is given " + when);
        assertTrue(beans.contains(OverseenService.class.getName()), beans::toString);
        List<String> vetoed = beans.stream().filter(VetoedInterceptorTest::isVetoed).toList();
        assertEquals(List.of(), vetoed, "the vetoed beans the extension is given " + when);
    }

    private static boolean isVetoed(String className) {
        return className.startsWith(VETOED_PACKAGE + ".") || className.equals(VetoedOverseer.class.getName());
    }

    // ---- helpers ----

    /** The deployment on the loader of a start, with the vetoed package as {@code arrival} brings it. */
    private static VaubanContainerBuilder deployment(Arrival arrival, List<Path> path) {
        VaubanContainerBuilder builder = deployment(path);
        switch (arrival) {
            case ADDED_CLASSES -> VETOED_PACKAGE_CLASSES.forEach(name -> builder.addBeanClass(load(name)));
            case SCANNED_PACKAGE -> builder.scanPackage(VETOED_PACKAGE);
            case DIRECTORY_ARCHIVE, JAR_ARCHIVE -> builder.scanUnprocessedBeanArchives(archive -> { });
            case SCANNED_CLASSES -> builder.addBeanClass(ScanningExtension.class);
            case BEANS_LIST -> builder.scanClasspath();
        }
        return builder;
    }

    /**
     * The deployment without the vetoed package, on the loader of a start: the build's output first, where the record
     * is, then the archive.
     */
    private static VaubanContainerBuilder deployment(List<Path> path) {
        VaubanContainerBuilder builder = VaubanContainer.builder().classLoader(loader(path));
        for (Class<?> clazz : List.of(Overseer.class, VetoedOverseer.class, OverseenService.class,
                RecordingExtension.class)) {
            builder.addBeanClass(clazz);
        }
        return builder;
    }

    /** The directories and jars of the deployment: the build's output, then the archive that holds the package. */
    private List<Path> deploymentPath(Arrival arrival) throws IOException {
        return switch (arrival) {
            case ADDED_CLASSES, SCANNED_PACKAGE -> List.of(output);
            case DIRECTORY_ARCHIVE -> List.of(output, directoryArchive(BEANS_XML, "<beans bean-discovery-mode=\"all\" "
                    + "version=\"4.0\"/>"));
            case JAR_ARCHIVE -> List.of(output, jarArchive("<beans bean-discovery-mode=\"annotated\" version=\"4.0\"/>"));
            case SCANNED_CLASSES -> List.of(output, jarArchive(null));
            case BEANS_LIST -> List.of(output, directoryArchive(BEANS_LIST, String.join("\n", VETOED_PACKAGE_TYPES)));
        };
    }

    /** A directory of the classes of the vetoed package, with {@code descriptor} in {@code META-INF}. */
    private Path directoryArchive(String descriptor, String content) throws IOException {
        Path root = Files.createDirectories(archives.resolve("classes"));
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve(descriptor), content);
        for (String className : VETOED_PACKAGE_CLASSES) {
            Path target = root.resolve(entryName(className));
            Files.createDirectories(target.getParent());
            Files.write(target, classFile(className));
        }
        return root;
    }

    /** A jar of the classes of the vetoed package, with a {@code beans.xml} unless {@code beansXml} is null. */
    private Path jarArchive(String beansXml) throws IOException {
        Path jar = archives.resolve("vetoed.jar");
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            if (beansXml != null) {
                zip.putNextEntry(new JarEntry(BEANS_XML));
                zip.write(beansXml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (String className : VETOED_PACKAGE_CLASSES) {
                zip.putNextEntry(new JarEntry(entryName(className)));
                zip.write(classFile(className));
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static ClassLoader loader(List<Path> path) {
        URL[] urls = path.stream().map(VetoedInterceptorTest::url).toArray(URL[]::new);
        return new URLClassLoader(urls, VetoedInterceptorTest.class.getClassLoader());
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String entryName(String className) {
        return className.replace('.', '/') + ".class";
    }

    private static byte[] classFile(String className) throws IOException {
        try (InputStream in = VetoedInterceptorTest.class.getClassLoader().getResourceAsStream(entryName(className))) {
            if (in == null) {
                throw new IllegalStateException("no class file for " + className);
            }
            return in.readAllBytes();
        }
    }
}
