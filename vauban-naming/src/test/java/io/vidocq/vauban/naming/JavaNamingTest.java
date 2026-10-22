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
package io.vidocq.vauban.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.naming.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Installing the factory as the JVM default: an application's own choice always wins. */
class JavaNamingTest {

    private final ClassLoader loader = Thread.currentThread().getContextClassLoader();

    @AfterEach
    void clear() {
        System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
    }

    @Test
    void installingSetsTheFactoryWhenNothingElseChoseOne() {
        assertTrue(JavaNaming.installAsDefault(loader));

        assertEquals(CdiInitialContextFactory.class.getName(), System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
        assertEquals(JavaNaming.INITIAL_CONTEXT_FACTORY, CdiInitialContextFactory.class.getName());
    }

    @Test
    void aSystemPropertyTheApplicationSetWins() {
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, RefusingFactory.class.getName());

        assertFalse(JavaNaming.installAsDefault(loader));
        assertEquals(RefusingFactory.class.getName(), System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    }

    @Test
    void aJndiPropertiesFileOfTheApplicationWins(@TempDir Path resources) throws IOException {
        // The system property would take precedence over this file: setting it would override the application.
        Files.writeString(resources.resolve("jndi.properties"),
                Context.INITIAL_CONTEXT_FACTORY + "=" + RefusingFactory.class.getName() + "\n");
        try (var application = new URLClassLoader(new URL[] {resources.toUri().toURL()}, loader)) {
            assertFalse(JavaNaming.installAsDefault(application));
        }
        assertNull(System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    }

    @Test
    void aJndiPropertiesFileWithoutAFactoryDoesNotCount(@TempDir Path resources) throws IOException {
        Files.writeString(resources.resolve("jndi.properties"), Context.URL_PKG_PREFIXES + "=com.example\n");
        try (var application = new URLClassLoader(new URL[] {resources.toUri().toURL()}, loader)) {
            assertTrue(JavaNaming.installAsDefault(application));
        }
    }

    @Test
    void withoutAClassLoaderTheSystemResourcesAreRead() {
        assertTrue(JavaNaming.installAsDefault(null));
    }

    @Test
    void aJndiPropertiesFileOfThePlatformWins(@TempDir Path javaHome) throws IOException {
        Files.createDirectories(javaHome.resolve("conf"));
        Files.writeString(javaHome.resolve("conf/jndi.properties"),
                Context.INITIAL_CONTEXT_FACTORY + "=" + RefusingFactory.class.getName() + "\n");
        String realHome = System.getProperty("java.home");
        System.setProperty("java.home", javaHome.toString());
        try {
            assertFalse(JavaNaming.installAsDefault(loader));
        } finally {
            System.setProperty("java.home", realHome);
        }
        assertNull(System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    }

    @Test
    void anUnreadableJndiPropertiesFileFailsTheInstallation(@TempDir Path resources) throws IOException {
        URL gone = resources.resolve("gone/jndi.properties").toUri().toURL();
        var application = new ClassLoader(loader) {
            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.enumeration(List.of(gone));
            }
        };

        assertThrows(UncheckedIOException.class, () -> JavaNaming.installAsDefault(application));
        assertNull(System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    }

    @Test
    void uninstallingClearsOnlyThisFactory() {
        JavaNaming.installAsDefault(loader);
        JavaNaming.uninstall();
        assertNull(System.getProperty(Context.INITIAL_CONTEXT_FACTORY));

        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, RefusingFactory.class.getName());
        JavaNaming.uninstall();
        assertEquals(RefusingFactory.class.getName(), System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    }
}
