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
package io.vidocq.vauban.core.opens;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link OpensApplier} over produced types it cannot load, types on the class path and a type of a named module
 * it would have to open: with the auto-open lever off, it opens nothing and never fails the boot.
 */
class OpensApplierProducedTypesTest {

    private static final String UNNAMED = "gen.opens.OnTheClassPath";

    /** Serves the required-opens list and defines one class of its own, in its unnamed module. */
    private static final class Listing extends ClassLoader {
        private final URL list;

        Listing(URL list) {
            super(OpensApplierProducedTypesTest.class.getClassLoader());
            this.list = list;
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            return "META-INF/vauban/required-opens.list".equals(name)
                    ? Collections.enumeration(List.of(list))
                    : Collections.emptyEnumeration();
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!UNNAMED.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = ClassFile.of().build(ClassDesc.of(UNNAMED), clb -> { });
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    void producedTypesThatNeedNoOpeningOrCannotBeOpenedLeaveModulesUntouched(@TempDir Path dir) throws IOException {
        var list = dir.resolve("required-opens.list");
        Files.writeString(list, String.join("\n", "# produced types", "com.acme.Missing", UNNAMED,
                "java.util.concurrent.atomic.AtomicLong"));
        var loader = new Listing(list.toUri().toURL());

        assertFalse(OpensApplier.autoOpenEnabled());
        assertDoesNotThrow(() -> OpensApplier.apply(loader));
        assertFalse(Object.class.getModule().isOpen("java.util.concurrent.atomic", OpensApplier.class.getModule()));
    }
}
