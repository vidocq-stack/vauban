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

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-31: a class outside the compilation, which the compiler reads from its class file, is described by the
 * processor from the compiler's model, and by the container and {@code vauban:generate} from the class file itself.
 * Both give the language model the same declaration: the name, the kind, the type parameters with their bounds, the
 * supertypes with their type arguments, and for a class of a library, its fields, methods and annotations.
 */
@DisplayName("BUG-20261117-31: the processor and the bytecode scan describe a class outside the compilation alike")
class ElementScannerOutsideClassesTest {

    /** A library compiled apart, on the class path of the compilation that reads it: Mansart's shape. */
    private static final Map<String, String> LIBRARY = Map.of("lib/Basic.java", """
            package lib;
            public interface Basic<T, K> {
                T find(K key);
            }
            """, "lib/Crud.java", """
            package lib;
            public interface Crud<T, K> extends Basic<T, K> {
                void save(T entity);
            }
            """, "lib/Store.java", """
            package lib;
            @Deprecated
            public abstract class Store<E extends Comparable<E>> implements Crud<E, Long>, java.io.Serializable {
                protected java.util.List<? extends E> items;
                public abstract int size();
            }
            """, "lib/Wild.java", """
            package lib;
            public interface Wild extends Basic<java.util.List<?>, java.util.List<? extends Object>> {
                java.util.List<? super Integer> lower(java.util.Map<?, ? extends Object> map);
            }
            """);
    private static final List<String> LIBRARY_CLASSES = List.of("lib.Basic", "lib.Crud", "lib.Store", "lib.Wild");
    private static final List<String> JDK_CLASSES = List.of("java.util.function.Supplier", "java.util.AbstractMap",
            "java.lang.Enum", "java.util.Map$Entry", "java.lang.Object");
    private static final Map<String, ClassInfo> PROCESSOR = new HashMap<>();
    private static final Map<String, ClassInfo> BYTECODE = new HashMap<>();

    /** Describes, once the compilation is over, each class outside it with the processor's scanner. */
    private static final class Outside extends AbstractProcessor {

        private final Map<String, ClassInfo> described = new HashMap<>();

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (round.processingOver()) {
                var elements = processingEnv.getElementUtils();
                var scanner = new ElementScanner(elements, processingEnv.getTypeUtils());
                Stream.concat(LIBRARY_CLASSES.stream(), JDK_CLASSES.stream()).forEach(name -> described.put(name,
                        scanner.scan(elements.getTypeElement(name.replace('$', '.')))));
            }
            return false;
        }
    }

    @BeforeAll
    static void describeBothWays(@TempDir Path directory) throws IOException {
        var library = ProcessorCompiler.compile(directory.resolve("library"), LIBRARY, List.of(), List.of());
        assertTrue(library.success(), () -> "the library must compile: " + library.messages());
        var outside = new Outside();
        var application = ProcessorCompiler.compile(directory.resolve("application"),
                Map.of("app/Archive.java", "package app;\npublic interface Archive extends lib.Crud<String, Long> {}"),
                List.of("-classpath", library.classes().toString()), List.of(outside));
        assertTrue(application.success(), () -> "the application must compile: " + application.messages());
        PROCESSOR.putAll(outside.described);
        for (var name : LIBRARY_CLASSES) {
            BYTECODE.put(name, ClassFileScanner.scan(Files.readAllBytes(library.classes().resolve(classFile(name)))));
        }
        for (var name : JDK_CLASSES) {
            try (var classFile = ClassLoader.getSystemResourceAsStream(classFile(name))) {
                assertNotNull(classFile, name);
                BYTECODE.put(name, ClassFileScanner.scan(classFile.readAllBytes()));
            }
        }
    }

    /**
     * The declaration of each class: an interface's superclass is left out, as the compiler gives an interface none
     * and its class file names {@code Object}, which the language model hides (BUG-20261117-32).
     */
    @Test
    @DisplayName("the same declaration: name, kind, type parameters and supertypes with their type arguments")
    void theSameDeclaration() {
        assertAll(Stream.concat(LIBRARY_CLASSES.stream(), JDK_CLASSES.stream()).<Executable>map(name ->
                () -> assertEquals(declaration(BYTECODE.get(name)), declaration(PROCESSOR.get(name)), name)));
    }

    /**
     * The members of a library class; those of a JDK class are not compared, as the compiler reads only its API. The
     * library declares no lambda and needs no bridge method: the class file would add those synthetic methods, which
     * the compiler does not show (BUG-20261117-43).
     */
    @Test
    @DisplayName("the same fields, methods and annotations for a class of a library")
    void theSameMembers() {
        assertAll(LIBRARY_CLASSES.stream().<Executable>map(name ->
                () -> assertEquals(members(BYTECODE.get(name)), members(PROCESSOR.get(name)), name)));
    }

    /**
     * A field typed by a type variable bounded by itself: the variable met again has no bound, both ways
     * (BUG-20261117-36).
     */
    @Test
    @DisplayName("the same type for a field of a type variable bounded by itself")
    void theSameTypeForASelfBoundedField() {
        var store = "lib.Store";

        assertEquals(BYTECODE.get(store).fields().getFirst().type(), PROCESSOR.get(store).fields().getFirst().type());
    }

    /**
     * The wildcards of a class outside the compilation, in its supertypes and in its method's types: {@code ?} and
     * {@code ? extends Object} take the one form of the index both ways, a wildcard without bounds (BUG-20261117-34).
     */
    @Test
    @DisplayName("the same wildcards in the supertypes and the method types of a class outside the compilation")
    void theSameWildcards() {
        var wild = "lib.Wild";
        var processor = PROCESSOR.get(wild).methods().getFirst();
        var bytecode = BYTECODE.get(wild).methods().getFirst();

        assertAll(() -> assertEquals(BYTECODE.get(wild).genericInterfaces(), PROCESSOR.get(wild).genericInterfaces()),
                () -> assertEquals(bytecode.returnType(), processor.returnType()),
                () -> assertEquals(bytecode.parameters().getFirst().type(), processor.parameters().getFirst().type()));
    }

    private static List<Object> declaration(ClassInfo type) {
        var superclass = type.isInterface() ? List.of() : Arrays.asList(type.superName(), type.genericSuperclass());
        return List.of(type.name(), type.kind(), type.simpleName(), superclass, type.interfaces(),
                type.typeParameters(), type.genericInterfaces());
    }

    private static List<List<String>> members(ClassInfo type) {
        return List.of(type.fields().stream().map(FieldInfo::name).toList(),
                type.methods().stream().map(MethodInfo::name).sorted().toList(),
                type.annotations().stream().map(annotation -> annotation.name().value()).toList());
    }

    private static String classFile(String name) {
        return name.replace('.', '/') + ".class";
    }
}
