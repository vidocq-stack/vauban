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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import javax.naming.Context;

/**
 * Makes {@link CdiInitialContextFactory} the default initial context factory of the JVM, through the
 * {@value Context#INITIAL_CONTEXT_FACTORY} system property.
 *
 * <p>Not {@code NamingManager.setInitialContextFactoryBuilder}: once a builder is installed, every
 * {@code InitialContext} of the JVM goes to it, {@code ldap://} URLs and an explicit factory in the
 * environment included, and it can be set only once per JVM. With the property, the JDK keeps its own
 * routing: URL schemes still go to their URL context factories, and an environment that names a factory
 * still gets it. A {@code java:} name has no URL context factory, so it falls through to the default
 * context, this one.
 */
public final class JavaNaming {

    /** The class name the property is set to. */
    public static final String INITIAL_CONTEXT_FACTORY = CdiInitialContextFactory.class.getName();

    private static final String RESOURCE_FILE = "jndi.properties";

    private JavaNaming() {}

    /**
     * Sets the system property to {@link #INITIAL_CONTEXT_FACTORY}, unless the application chose a factory:
     * in that same property, or in a {@code jndi.properties} file, which the property would override.
     * The JDK reads the files of the thread context class loader and {@code <java.home>/conf}. Call it
     * before the application creates its first {@code InitialContext}.
     *
     * @param loader the class loader whose {@code jndi.properties} files the application's
     *               {@code InitialContext} reads, usually the thread context class loader
     * @return {@code true} when the property was set, {@code false} when the application's choice was kept
     * @throws UncheckedIOException when a {@code jndi.properties} file cannot be read
     */
    public static synchronized boolean installAsDefault(ClassLoader loader) {
        if (System.getProperty(Context.INITIAL_CONTEXT_FACTORY) != null || chosenInResourceFiles(loader)) {
            return false;
        }
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, INITIAL_CONTEXT_FACTORY);
        return true;
    }

    /** Clears the system property if it names {@link #INITIAL_CONTEXT_FACTORY}, and leaves any other value. */
    public static synchronized void uninstall() {
        if (INITIAL_CONTEXT_FACTORY.equals(System.getProperty(Context.INITIAL_CONTEXT_FACTORY))) {
            System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
        }
    }

    private static boolean chosenInResourceFiles(ClassLoader loader) {
        try {
            var files = loader != null
                    ? loader.getResources(RESOURCE_FILE)
                    : ClassLoader.getSystemResources(RESOURCE_FILE);
            for (URL file : Collections.list(files)) {
                try (var in = file.openStream()) {
                    if (namesAFactory(in)) {
                        return true;
                    }
                }
            }
            Path platform = Path.of(System.getProperty("java.home"), "conf", RESOURCE_FILE);
            if (Files.isRegularFile(platform)) {
                try (var in = Files.newInputStream(platform)) {
                    return namesAFactory(in);
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the " + RESOURCE_FILE + " files of the application", e);
        }
    }

    private static boolean namesAFactory(InputStream in) throws IOException {
        var properties = new Properties();
        properties.load(in);
        return properties.containsKey(Context.INITIAL_CONTEXT_FACTORY);
    }
}
