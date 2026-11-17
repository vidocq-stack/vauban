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
package io.vidocq.vauban.maven.generate;

import io.vidocq.vauban.maven.generate.typeuse.Tagged;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261117-60: {@code vauban:generate} runs an {@code @Enhancement} with the annotations on the types of a class
 * shown, as the container does at start and the processor at build: the build that freezes the extensions sees what
 * they see at start.
 */
@DisplayName("VaubanGenerator - an extension sees the annotations on types")
class TypeAnnotationsGenerationTest {

    @TempDir
    Path tempDir;

    /** Records the types of the class it is given, with the number of annotations on each. */
    public static class TypeRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Enhancement(types = Tagged.class, withSubtypes = false)
        public void enhance(ClassConfig clazz) {
            SEEN.addAll(typesOf(clazz.info()));
        }
    }

    @Test
    @DisplayName("an @Enhancement sees the annotations on the types of a class")
    void anEnhancementSeesTheAnnotationsOnTypes() throws IOException, URISyntaxException {
        var classesDir = tempDir.resolve("classes");
        var relative = Tagged.class.getName().replace('.', '/') + ".class";
        var source = Path.of(Tagged.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(relative);
        var target = classesDir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
        var services = tempDir.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve("META-INF/services/" + BuildCompatibleExtension.class.getName()),
                TypeRecorder.class.getName());

        TypeRecorder.SEEN.clear();
        try (var loader = new URLClassLoader(new URL[] {services.toUri().toURL()},
                TypeAnnotationsGenerationTest.class.getClassLoader())) {
            VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classesDir, tempDir.resolve("output"),
                    loader));
        }

        assertEquals(List.of("typeParameters [1 T: 1 Number]", "superInterfaces [1 Labelled<1 String>]",
                "values 0 List<1 String 1 ARRAY>",
                "count 0 PRIMITIVE [1 T: 1 Number] throws [1 IllegalStateException]"),
                List.copyOf(TypeRecorder.SEEN));
    }

    private static List<String> typesOf(ClassInfo type) {
        var lines = new ArrayList<String>();
        lines.add("typeParameters " + all(type.typeParameters()));
        lines.add("superInterfaces " + all(type.superInterfaces()));
        type.fields().forEach(field -> lines.add(field.name() + " " + of(field.type())));
        type.methods().stream().filter(method -> method.declaringClass().name().equals(type.name()))
                .sorted(Comparator.comparing(MethodInfo::name))
                .forEach(method -> lines.add(method.name() + " " + of(method.returnType()) + " "
                        + all(method.parameters().stream().map(ParameterInfo::type).toList()) + " throws "
                        + all(method.throwsTypes())));
        return lines;
    }

    private static String all(List<? extends Type> types) {
        return types.stream().map(TypeAnnotationsGenerationTest::of).collect(Collectors.joining(", ", "[", "]"));
    }

    /** A type as {@code <annotations> <name>}, an array's component first, a type variable with its bounds. */
    private static String of(Type type) {
        var count = type.annotations().size() + " ";
        return switch (type.kind()) {
            case ARRAY -> of(type.asArray().componentType()) + " " + count + "ARRAY";
            case CLASS -> count + simple(type.asClass().declaration().name());
            case PARAMETERIZED_TYPE -> count + simple(type.asParameterizedType().genericClass().declaration().name())
                    + all(type.asParameterizedType().typeArguments()).replace('[', '<').replace(']', '>');
            case TYPE_VARIABLE -> count + type.asTypeVariable().name() + ": "
                    + type.asTypeVariable().bounds().stream().map(TypeAnnotationsGenerationTest::of)
                            .collect(Collectors.joining(" & "));
            default -> count + type.kind();
        };
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
