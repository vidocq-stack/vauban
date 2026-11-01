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
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * At build, a class an annotation value names, and which is neither a bean nor carries a trigger annotation, shows
 * its members to a Build Compatible Extension, as it does at boot (VAU-BCE-008: Champollion's extension checks the
 * {@code @Inject} members of the adapter {@code @JsonbTypeAdapter(AmountAdapter.class)} names, and saw none).
 */
class AnnotationValueClassInfoTest {

    @TempDir
    Path workDir;

    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    /** Reads the members of the class {@code @app.Uses} names on each bean that carries it. */
    public static class ReadingBce implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withSubtypes = true)
        public void read(ClassConfig clazz) {
            for (AnnotationInfo annotation : clazz.info().annotations()) {
                if (!annotation.name().equals("app.Uses")) {
                    continue;
                }
                var named = annotation.value().asType().asClass().declaration();
                named.fields().stream().map(FieldInfo::name).forEach(f -> SEEN.add("field " + f));
                named.methods().stream().map(MethodInfo::name).forEach(m -> SEEN.add("method " + m));
                SEEN.add("injects " + named.fields().stream()
                        .anyMatch(f -> f.hasAnnotation(a -> a.name().equals("jakarta.inject.Inject"))));
            }
        }
    }

    @BeforeEach
    void clear() {
        SEEN.clear();
    }

    @Test
    void theClassAnAnnotationValueNamesShowsItsMembersAtBuild() throws IOException {
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(ReadingBce.class));

        var result = ProcessorCompiler.compile(workDir, Map.of(
                "app/Uses.java", """
                        package app;

                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        public @interface Uses {
                            Class<?> value();
                        }
                        """,
                "app/Helper.java", """
                        package app;

                        public class Helper {
                            @jakarta.inject.Inject
                            Object dependency;

                            public String work() {
                                return "done";
                            }
                        }
                        """,
                "app/Holder.java", """
                        package app;

                        @Uses(Helper.class)
                        @jakarta.enterprise.context.ApplicationScoped
                        public class Holder {
                        }
                        """), List.of(), processor);

        var messages = String.join("\n", result.messages());
        assertTrue(result.success(), messages);
        assertEquals(List.of("field dependency", "method work", "injects true"), SEEN, messages);
    }
}
