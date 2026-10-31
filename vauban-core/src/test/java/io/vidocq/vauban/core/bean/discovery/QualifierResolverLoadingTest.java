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
package io.vidocq.vauban.core.bean.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.indexer.IndexBuilder;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Discovery reads the inherited annotations of a dependency's class without initialising it: under the annotation
 * processor, a static initialiser runs inside javac, where the libraries it needs are not on the processor path
 * (VAU-APT-008, an application with Fault Tolerance and mansart-transactions failed to compile on
 * {@code NoClassDefFoundError: io/opentelemetry/api/common/AttributeKey} from Heisenberg's {@code OtelFtMetrics}).
 */
class QualifierResolverLoadingTest {

    static boolean initialised;

    static final class WithStaticInitialiser {
        static {
            initialised = true;
        }
    }

    @Test
    void theFallbackLoaderDoesNotInitialiseTheClass() throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        // A context loader that cannot see the class, as under javac: discovery falls back on its own loader.
        try (var blind = new URLClassLoader(new URL[0], null)) {
            thread.setContextClassLoader(blind);
            var loaded = QualifierResolver.loadWithFallback(WithStaticInitialiser.class.getName());
            assertEquals(WithStaticInitialiser.class.getName(), loaded.getName());
            assertFalse(initialised, "loading for discovery ran the class's static initialiser");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * A dependency's class whose superclass is not on the processor path cannot even be loaded under javac (an
     * Heisenberg bean referring to MicroProfile Metrics' {@code RegistryScope}): discovery keeps the annotations the
     * index read, and the build goes on.
     */
    @Test
    void aClassThatCannotBeLinkedHasNoInheritedAnnotations(@TempDir Path dir) throws Exception {
        byte[] sub = ClassFile.of().build(ClassDesc.of("gen.Sub"),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ClassDesc.of("gen.Missing")));
        Files.createDirectories(dir.resolve("gen"));
        Files.write(dir.resolve("gen/Sub.class"), sub);
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            thread.setContextClassLoader(loader);
            var discovery = new BeanDiscovery(new IndexBuilder().build());
            List<java.lang.annotation.Annotation> inherited = discovery.getInheritedAnnotations(
                    BeanDiscoveryTest.makeAnnotatedClass("gen.Sub", "jakarta.enterprise.context.ApplicationScoped"));
            assertTrue(inherited.isEmpty());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
