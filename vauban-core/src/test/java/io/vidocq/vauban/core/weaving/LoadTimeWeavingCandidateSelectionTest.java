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
package io.vidocq.vauban.core.weaving;

import io.vidocq.vauban.classloader.VaubanClassLoader;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which declared classes {@link LoadTimeWeaving} plans to weave: generated classes, missing, unreadable or
 * unparseable bytes and beans that cannot chain to their superclass are left out, and under a Vauban class
 * loader the plan is reported without attaching any agent.
 */
class LoadTimeWeavingCandidateSelectionTest {

    private static final String PACKAGE = "com.example.ltwselect.";
    private static final String GOOD = PACKAGE + "Good";
    private static final String CHILD = PACKAGE + "Child";
    private static final String GARBAGE = PACKAGE + "Garbage";
    private static final String UNREADABLE = PACKAGE + "Unreadable";
    private static final String MISSING_SUPER = PACKAGE + "MissingSuper";
    private static final String GARBAGE_SUPER = PACKAGE + "GarbageSuper";
    private static final byte[] NOT_A_CLASS = "not a class file".getBytes(StandardCharsets.UTF_8);

    /** A loader that serves the bean list and class bytes given to it, and fails to read one class. */
    private static final class Fixtures extends ClassLoader {
        private final URL beanList;
        private final Map<String, byte[]> classes;

        Fixtures(ClassLoader parent, URL beanList, Map<String, byte[]> classes) {
            super(parent);
            this.beanList = beanList;
            this.classes = classes;
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            return "META-INF/vauban-beans.list".equals(name)
                    ? Collections.enumeration(List.of(beanList))
                    : Collections.emptyEnumeration();
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            var binaryName = name.substring(0, name.length() - ".class".length()).replace('/', '.');
            if (UNREADABLE.equals(binaryName)) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable");
                    }
                };
            }
            var bytes = classes.get(binaryName);
            return bytes == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
        }
    }

    /** An application-scoped class whose only constructor takes a String: it needs the woven marker. */
    private static byte[] unwovenBean(String binaryName, String superName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), clb -> clb
                .withSuperclass(ClassDesc.of(superName))
                .with(RuntimeVisibleAnnotationsAttribute.of(java.lang.classfile.Annotation.of(
                        ClassDesc.of("jakarta.enterprise.context.ApplicationScoped"))))
                .withMethodBody(ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_()));
    }

    @Test
    void onlyChainableUnwovenBeansArePlannedAndAVaubanLoaderWeavesThem(@TempDir Path dir) throws IOException {
        var list = dir.resolve("vauban-beans.list");
        Files.writeString(list, String.join("\n", "# declared beans", GOOD, CHILD, PACKAGE + "Good_ClientProxy",
                PACKAGE + "Good$$Generated", PACKAGE + "Absent", GARBAGE, UNREADABLE, MISSING_SUPER, GARBAGE_SUPER));
        var classes = Map.of(
                GOOD, unwovenBean(GOOD, "java.lang.Object"),
                CHILD, unwovenBean(CHILD, GOOD),
                GARBAGE, NOT_A_CLASS,
                MISSING_SUPER, unwovenBean(MISSING_SUPER, PACKAGE + "NoSuchSuper"),
                GARBAGE_SUPER, unwovenBean(GARBAGE_SUPER, GARBAGE));

        try (var vaubanLoader = VaubanClassLoader.of(List.of(), getClass().getClassLoader(), PluginContext.empty())) {
            var result = LoadTimeWeaving.prepare(new Fixtures(vaubanLoader, list.toUri().toURL(), classes));

            assertNull(result.failure());
            assertEquals(Set.of(GOOD, CHILD), result.planned());
        }
    }
}
