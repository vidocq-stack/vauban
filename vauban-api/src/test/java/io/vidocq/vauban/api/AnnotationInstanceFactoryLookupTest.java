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
package io.vidocq.vauban.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Without a provider beside the API, the factory is looked up through the thread's context class loader. */
class AnnotationInstanceFactoryLookupTest {

    private static final String PROVIDER = "probe.Factory";

    @TempDir
    Path tmp;

    /** A class-path provider: a public class with a public no-arg constructor implementing the service. */
    private Path providerOnClassPath() throws IOException {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(PROVIDER), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaceSymbols(ClassDesc.of(AnnotationInstanceFactory.class.getName()));
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
        });
        Files.createDirectories(tmp.resolve("probe"));
        Files.write(tmp.resolve("probe/Factory.class"), bytes);
        Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(tmp.resolve("META-INF/services/" + AnnotationInstanceFactory.class.getName()), PROVIDER);
        return tmp;
    }

    @Test
    void aProviderOfTheContextClassLoaderIsFound() throws IOException {
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        try (var context = new URLClassLoader(new URL[] {providerOnClassPath().toUri().toURL()},
                getClass().getClassLoader())) {
            thread.setContextClassLoader(context);

            assertEquals(PROVIDER, AnnotationInstanceFactory.find().orElseThrow().getClass().getName());
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void withoutAContextClassLoaderThereIsNoFactory() {
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertTrue(AnnotationInstanceFactory.find().isEmpty());
        } finally {
            thread.setContextClassLoader(original);
        }
    }
}
