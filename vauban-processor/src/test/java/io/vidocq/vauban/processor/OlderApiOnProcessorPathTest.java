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
package io.vidocq.vauban.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The processor path of a module can hold a {@code vauban-api} older than {@code vauban-processor}: a codegen bundle
 * built on an earlier Vauban brings its own, and Maven's nearest-wins mediation may pick it. The processor must still
 * start there, so it names the newer {@code vauban-api} types it looks for rather than loading them: a class literal
 * of a type that is missing fails its initialisation, and javac reports "Provider VaubanProcessor could not be
 * instantiated" for every module compiled with it.
 */
class OlderApiOnProcessorPathTest {

    private static final String CLIENT_PROXIES = "io.vidocq.vauban.api.ClientProxies";

    @Test
    void processorStartsWithoutClientProxiesOnItsPath() throws ReflectiveOperationException {
        var loader = new OlderApiLoader(getClass().getClassLoader());

        var processor = Class.forName(VaubanProcessor.class.getName(), true, loader)
                .getDeclaredConstructor().newInstance();

        assertNotNull(processor);
    }

    /**
     * Defines the processor's own classes itself, so that what they link resolves through it, and hides the
     * {@code vauban-api} types an older version does not have.
     */
    private static final class OlderApiLoader extends ClassLoader {

        private static final String PROCESSOR_PACKAGE = "io.vidocq.vauban.processor.";

        OlderApiLoader(ClassLoader parent) {
            super("older-vauban-api", parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(CLIENT_PROXIES)) {
                throw new ClassNotFoundException(name + " is not in this older vauban-api");
            }
            if (!name.startsWith(PROCESSOR_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineOwn(name);
            }
        }

        private Class<?> defineOwn(String name) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
