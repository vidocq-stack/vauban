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

import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.codegen.CompiledSupertypes;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import io.vidocq.vauban.processor.apt.ElementScanner;
import io.vidocq.vauban.processor.apt.ProcessorCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-21: a member type of a parameterized type, {@code Box<String>.Item<Integer>}, is one type at build and
 * at start, its enclosing type included, now that the enclosing type is part of the type. The bean types of
 * {@code Items extends ArrayList<Box<String>.Item<Integer>>} named it without its owner at build, where the processor
 * reads the classes above a bean from the compiler, and with it at start, where reflection reads them. So does
 * {@code Box<String>.Plain}, which declares no type parameter: it was a class type, without its owner, in the processor
 * and in the class file's scan (BUG-20261118-47).
 */
@DisplayName("BUG-20261117-21 - a member type of a parameterized type is one type at build and at start")
class OwnedTypesAtBuildAndStartTest {

    private static final String ITEMS = "p.Items";
    private static final Map<String, String> SOURCES = Map.of(
            "p/Box.java", """
                    package p;
                    public class Box<B> {
                        public class Item<I> {
                        }
                        public class Plain {
                        }
                    }
                    """,
            "p/Items.java", """
                    package p;
                    @jakarta.enterprise.context.Dependent
                    public class Items extends java.util.ArrayList<Box<String>.Item<Integer>>
                            implements java.util.function.Supplier<Box<String>.Plain> {
                        @Override
                        public Box<String>.Plain get() {
                            return null;
                        }
                    }
                    """);

    @Test
    @DisplayName("the bean types of a class above a member type of a parameterized type are one set")
    void theBeanTypesAreOneSet(@TempDir Path dir) throws Exception {
        var atBuild = new BuildTypes();
        var result = ProcessorCompiler.compile(dir, SOURCES, List.of(), atBuild);
        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        var atStart = startTypes(result.classes());

        var boxOfString = new TypeInfo.ParameterizedType(DotName.of("p.Box"), List.of(classType("java.lang.String")));
        var item = new TypeInfo.ParameterizedType(DotName.of("p.Box$Item"), List.of(classType("java.lang.Integer")),
                boxOfString);
        var listOfItems = new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.<TypeInfo>of(item));
        // Box<String>.Plain: no type argument of its own, its owner still part of it (BUG-20261118-47)
        var plain = new TypeInfo.ParameterizedType(DotName.of("p.Box$Plain"), List.of(), boxOfString);
        var supplierOfPlains = new TypeInfo.ParameterizedType(DotName.of("java.util.function.Supplier"),
                List.<TypeInfo>of(plain));
        assertTrue(atStart.contains(listOfItems), () -> "at start: " + atStart);
        assertTrue(atStart.contains(supplierOfPlains), () -> "at start: " + atStart);
        assertEquals(atStart, atBuild.types, "the bean types at build");
    }

    private static TypeInfo classType(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    /** The bean types of {@code p.Items} as the bean discovery gives them from {@code index}. */
    private static Set<TypeInfo> itemsTypes(VaubanIndex index) {
        return new BeanDiscovery(index).discoverBeans().stream()
                .filter(bean -> bean.beanClass().equals(DotName.of(ITEMS))).findFirst().orElseThrow().types();
    }

    /** The bean types at start: from the class file, the classes above it read by reflection. */
    private static Set<TypeInfo> startTypes(Path classes) throws Exception {
        var index = new IndexBuilder().add(ClassFileScanner.scan(Files.readAllBytes(classes.resolve("p/Items.class"))))
                .build();
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                OwnedTypesAtBuildAndStartTest.class.getClassLoader())) {
            thread.setContextClassLoader(loader);
            return itemsTypes(index);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Records the bean types of {@code p.Items} at build: the processor's scan, the classes above it the compiler's. */
    @SupportedAnnotationTypes("*")
    private static final class BuildTypes extends AbstractProcessor {

        private Set<TypeInfo> types = Set.of();

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            var items = processingEnv.getElementUtils().getTypeElement(ITEMS);
            if (round.processingOver() || items == null || !types.isEmpty()) {
                return false;
            }
            var index = new IndexBuilder().add(new ElementScanner(processingEnv).scan(items)).build();
            types = CompiledSupertypes.where(new ParameterizedSupertypes(processingEnv.getElementUtils(),
                    processingEnv.getTypeUtils()), () -> itemsTypes(index));
            return false;
        }
    }
}
