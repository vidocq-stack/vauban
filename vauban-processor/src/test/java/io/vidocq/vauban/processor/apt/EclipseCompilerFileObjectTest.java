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
package io.vidocq.vauban.processor.apt;

import io.vidocq.vauban.processor.VaubanProcessor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eclipse's compiler (JDT APT: Eclipse, VS Code's Java extension) throws {@link FileSystemNotFoundException} from
 * {@link Elements#getFileObjectOf} for a type read from a jar. The processor took that as a failure of its own and
 * stopped in its last round, before the bean list, the provider service and the BCE marker: an application built by
 * the IDE then booted without its beans (a REST resource answered 404), with no word about it.
 */
class EclipseCompilerFileObjectTest {

    private static final Map<String, String> BEAN = Map.of("app/Greeter.java", """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Greeter {
                @jakarta.inject.Inject
                jakarta.enterprise.inject.spi.BeanManager manager;
            }
            """);

    @Test
    void aJarTypeWhoseFileObjectTheCompilerCannotGiveIsNotCompiledHere(@TempDir Path dir) throws IOException {
        var javac = ProcessorCompiler.compile(dir.resolve("javac"), BEAN, List.of("-Avauban.providesHint=false"));
        var eclipse = ProcessorCompiler.compile(dir.resolve("eclipse"), BEAN, List.of("-Avauban.providesHint=false"),
                new EclipseLikeElements(new VaubanProcessor()));

        assertTrue(eclipse.success(), eclipse.messages().toString());
        assertFalse(eclipse.mentions("FileSystemNotFoundException"), eclipse.messages().toString());
        assertTrue(Files.exists(eclipse.classes().resolve("META-INF/vauban-beans.list")), "the bean list");
        assertTrue(Files.exists(eclipse.classes().resolve(
                "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider")), "the provider service");
        assertEquals(outputs(javac.classes()), outputs(eclipse.classes()));
    }

    /** Every file of a class output, relative path to content. */
    private static Map<String, String> outputs(Path classes) throws IOException {
        try (var files = Files.walk(classes)) {
            var out = new TreeMap<String, String>();
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var name = classes.relativize(file).toString();
                out.put(name, name.endsWith(".class") ? "class" : Files.readString(file));
            }
            return out;
        }
    }

    /** Runs {@code delegate} with an {@link Elements} that fails on a binary type's file object, as Eclipse's does. */
    private static final class EclipseLikeElements extends AbstractProcessor {

        private final AbstractProcessor delegate;

        EclipseLikeElements(AbstractProcessor delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void init(ProcessingEnvironment env) {
            super.init(env);
            var elements = env.getElementUtils();
            var eclipseElements = (Elements) Proxy.newProxyInstance(Elements.class.getClassLoader(),
                    new Class<?>[] {Elements.class}, (proxy, method, args) -> {
                        try {
                            var value = method.invoke(elements, args);
                            if ("getFileObjectOf".equals(method.getName()) && value instanceof JavaFileObject file
                                    && file.getKind() == JavaFileObject.Kind.CLASS) {
                                throw new FileSystemNotFoundException();
                            }
                            return value;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            var wrapped = (ProcessingEnvironment) Proxy.newProxyInstance(ProcessingEnvironment.class.getClassLoader(),
                    new Class<?>[] {ProcessingEnvironment.class}, (proxy, method, args) -> {
                        if ("getElementUtils".equals(method.getName())) {
                            return eclipseElements;
                        }
                        try {
                            return method.invoke(env, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            delegate.init(wrapped);
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return delegate.process(annotations, roundEnv);
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }
    }
}
