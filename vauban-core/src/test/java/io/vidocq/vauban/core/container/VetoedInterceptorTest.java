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

import io.vidocq.vauban.core.container.vetoed.PackageVetoedOverseer;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI 4.1 ignores a vetoed type altogether, an interceptor included: a class annotated {@code @Vetoed}, or a class of
 * a package annotated {@code @Vetoed}, intercepts nothing, and no extension sees it as an interceptor. In both kinds
 * of deployment: one that runs its extensions at start, and one whose extensions the build froze (BUG-20261116-12).
 */
@DisplayName("A vetoed interceptor intercepts nothing (BUG-20261116-12)")
class VetoedInterceptorTest {

    /** The vetoed package, which only its {@code package-info} class says is vetoed. */
    private static final String VETOED_PACKAGE_INFO = PackageVetoedOverseer.class.getPackageName() + ".package-info";

    @TempDir
    Path output;

    @BeforeEach
    @AfterEach
    void reset() {
        InterceptorRecordingExtension.SEEN.clear();
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

    /** Records the interceptors {@code @Registration} gives it, so that the deployment has an extension to freeze. */
    public static class InterceptorRecordingExtension implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void registration(BeanInfo bean) {
            if (bean.isInterceptor()) {
                SEEN.add(bean.declaringClass().name());
            }
        }
    }

    // ---- the tests ----

    @Test
    @DisplayName("when the extensions run at start, neither a vetoed class nor a class of a vetoed package intercepts")
    void aVetoedInterceptorInterceptsNothingWhenTheExtensionsRunAtStart() {
        try (VaubanContainer container = deployment().build()) {
            assertFalse(container.extensionsAtStart().frozen());
            assertEquals(List.of(InterceptorRecordingExtension.class.getName()),
                    container.extensionsAtStart().ranAtStart());
            assertEquals("overseer(call)", container.select(OverseenService.class).call());
            List<String> seen = List.copyOf(InterceptorRecordingExtension.SEEN);
            assertEquals(List.of(Overseer.class.getName()), seen, "the interceptors the extension is given");
        }
    }

    @Test
    @DisplayName("in a deployment frozen at build, neither a vetoed class nor a class of a vetoed package intercepts")
    void aVetoedInterceptorInterceptsNothingInADeploymentFrozenAtBuild() {
        FrozenExtensions.Outcome outcome = deployment().freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());
        List<String> seenAtBuild = List.copyOf(InterceptorRecordingExtension.SEEN);

        InterceptorRecordingExtension.SEEN.clear();
        try (VaubanContainer container = deployment().build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertEquals(List.of(), container.extensionsAtStart().ranAtStart());
            List<String> seenAtStart = List.copyOf(InterceptorRecordingExtension.SEEN);
            assertEquals(List.of(), seenAtStart, "the start runs no frozen extension");
            assertEquals("overseer(call)", container.select(OverseenService.class).call());
        }
        assertEquals(List.of(Overseer.class.getName()), seenAtBuild, "the interceptors the extension is given at build");
    }

    // ---- helpers ----

    /** The deployment, on the loader of a start: the build's output first, where the record is. */
    private VaubanContainerBuilder deployment() {
        VaubanContainerBuilder builder = VaubanContainer.builder().classLoader(outputLoader());
        for (Class<?> clazz : List.of(Overseer.class, VetoedOverseer.class, PackageVetoedOverseer.class,
                packageInfo(), OverseenService.class, InterceptorRecordingExtension.class)) {
            builder.addBeanClass(clazz);
        }
        return builder;
    }

    private ClassLoader outputLoader() {
        try {
            return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@code package-info} class of the vetoed package, as a deployment that holds the package lists it. */
    private static Class<?> packageInfo() {
        try {
            return Class.forName(VETOED_PACKAGE_INFO);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
