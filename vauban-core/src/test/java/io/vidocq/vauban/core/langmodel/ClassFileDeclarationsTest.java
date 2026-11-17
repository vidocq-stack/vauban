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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-31: the container describes a class outside the index from the class file the deployment's loader
 * finds, as the index describes the classes it holds, once per class; it never loads the class. Nothing describes a
 * class no class file holds, nor a name that is no class name: a type's {@code declaration()} gives a stub. Wherever
 * it comes from, {@code java.lang.Object} has no superclass, so that a walk up the superclasses ends.
 */
@DisplayName("BUG-20261117-31 - a class outside the index is described from its class file")
class ClassFileDeclarationsTest {

    private static final DotName SUPPLIER = DotName.of("java.util.function.Supplier");
    private static final DotName ARRAY_LIST = DotName.of("java.util.ArrayList");
    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final List<Object> NO_SUPERCLASS = Arrays.asList(null, null);

    /** A loader that serves the bytes of {@code resources} by resource name, and records what it is asked. */
    private static final class Resources extends ClassLoader {

        private final Map<String, InputStream> resources;
        private final List<String> asked = new CopyOnWriteArrayList<>();

        Resources(Map<String, InputStream> resources) {
            super(null);
            this.resources = resources;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            asked.add(name);
            return resources.get(name);
        }
    }

    @Test
    @DisplayName("a JDK class: its type parameters, read once")
    void aJdkClassIsReadOnce() {
        var declarations = IndexLookup.classFiles(ClassLoader.getPlatformClassLoader());

        var first = declarations.apply(SUPPLIER);

        assertAll(() -> assertEquals(List.of("T"), first.orElseThrow().typeParameters().stream()
                        .map(TypeInfo.TypeVariable::name).toList()),
                () -> assertTrue(first.orElseThrow().isInterface()),
                () -> assertSame(first, declarations.apply(SUPPLIER), "the second answer is the first"));
    }

    @Test
    @DisplayName("no loader: the system class loader")
    void noLoaderReadsTheSystemClassLoader() {
        assertTrue(IndexLookup.classFiles(null).apply(SUPPLIER).isPresent());
    }

    @Test
    @DisplayName("no class file, one that does not parse, one that fails, or one of another class: no description")
    void whatNoClassFileDescribes() throws IOException {
        try (var garbage = new ByteArrayInputStream(new byte[] {1, 2, 3});
                var failing = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable");
                    }
                };
                var otherClass = ClassLoader.getSystemResourceAsStream("java/util/function/Supplier.class")) {
            var loader = new Resources(Map.of("p/Garbage.class", garbage, "p/Failing.class", failing,
                    "p/supplier.class", otherClass));
            var declarations = IndexLookup.classFiles(loader);

            assertAll(() -> assertEquals(Optional.empty(), declarations.apply(DotName.of("p.Missing"))),
                    () -> assertEquals(Optional.empty(), declarations.apply(DotName.of("p.Garbage"))),
                    () -> assertEquals(Optional.empty(), declarations.apply(DotName.of("p.Failing"))),
                    () -> assertEquals(Optional.empty(), declarations.apply(DotName.of("p.supplier")),
                            "a file system that ignores case gives the class file of another class"));
        }
    }

    @Test
    @DisplayName("a name that is no class name is not looked up")
    void aNameThatIsNoClassNameIsNotLookedUp() {
        var loader = new Resources(Map.of());
        var declarations = IndexLookup.classFiles(loader);

        for (var name : List.of("a/b.C", "a\\b.C", "a..b")) {
            assertEquals(Optional.empty(), declarations.apply(DotName.of(name)), name);
        }
        assertEquals(List.of(), loader.asked);
    }

    @Test
    @DisplayName("asked from many virtual threads at once, every asker gets the same description")
    void concurrentAskersGetTheSameDescription() {
        var declarations = IndexLookup.classFiles(ClassLoader.getPlatformClassLoader());
        var answers = new CopyOnWriteArrayList<Optional<ClassInfo>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 32; i++) {
                executor.submit(() -> answers.add(declarations.apply(SUPPLIER)));
            }
        }

        assertEquals(32, answers.size());
        assertTrue(answers.stream().allMatch(answer -> answer == answers.getFirst()), "one description");
    }

    /**
     * A walk up {@code superClassDeclaration()} from {@code java.util.ArrayList} ends: inside the extension phases,
     * where the class files describe it and the classes above it up to {@code java.lang.Object}; outside them, where
     * {@code ArrayList} is a stub and nothing describes its superclass; and with no lookup. The stub of
     * {@code Object} named {@code Object} as its superclass, and a walk that met it never ended.
     */
    @Test
    @DisplayName("a walk up the superclasses of ArrayList ends, inside the phases, outside them and with no lookup")
    void aWalkUpTheSuperclassesEnds() {
        var lookup = new IndexLookup(new IndexBuilder().build());
        var outside = IndexLookup.classFiles(ClassLoader.getPlatformClassLoader());

        var inside = IndexLookup.whereOutsideDescribedBy(outside,
                () -> superclassesOf(VaubanClassInfo.of(ARRAY_LIST, lookup)));

        assertAll(() -> assertEquals(List.of("java.util.ArrayList", "java.util.AbstractList",
                        "java.util.AbstractCollection", OBJECT.value()), inside, "inside the phases"),
                () -> assertEquals(List.of("java.util.ArrayList"), superclassesOf(VaubanClassInfo.of(ARRAY_LIST,
                        lookup)), "outside the phases"),
                () -> assertEquals(List.of("java.util.ArrayList"), superclassesOf(VaubanClassInfo.of(ARRAY_LIST,
                        null)), "with no lookup"));
    }

    /**
     * {@code java.lang.Object} has no superclass, as the language model asks, wherever its description comes from:
     * the index, its class file, the stub outside the extension phases, or the stub of a class with no lookup.
     */
    @Test
    @DisplayName("java.lang.Object has no superclass: indexed, read from its class file, or a stub")
    void objectHasNoSuperclass() throws IOException {
        ClassInfo scanned;
        try (var classFile = ClassLoader.getSystemResourceAsStream("java/lang/Object.class")) {
            scanned = ClassFileScanner.scan(Objects.requireNonNull(classFile).readAllBytes());
        }
        var indexed = new IndexLookup(new IndexBuilder().add(scanned).build());
        var lookup = new IndexLookup(new IndexBuilder().build());
        var outside = IndexLookup.classFiles(ClassLoader.getPlatformClassLoader());

        assertAll(() -> assertEquals(NO_SUPERCLASS, superclassOf(VaubanClassInfo.of(OBJECT, indexed)), "indexed"),
                () -> assertEquals(NO_SUPERCLASS, IndexLookup.whereOutsideDescribedBy(outside,
                        () -> superclassOf(VaubanClassInfo.of(OBJECT, lookup))), "its class file"),
                () -> assertEquals(NO_SUPERCLASS, superclassOf(VaubanClassInfo.of(OBJECT, lookup)), "a stub"),
                () -> assertEquals(NO_SUPERCLASS, superclassOf(VaubanClassInfo.of(OBJECT, null)), "no lookup"));
    }

    /** The names up {@code superClassDeclaration()} from {@code type}, ten at most. */
    private static List<String> superclassesOf(jakarta.enterprise.lang.model.declarations.ClassInfo type) {
        var names = new ArrayList<String>();
        for (var current = type; current != null && names.size() < 10; current = current.superClassDeclaration()) {
            names.add(current.name());
        }
        return names;
    }

    /** The superclass of {@code type} and its declaration. */
    private static List<Object> superclassOf(jakarta.enterprise.lang.model.declarations.ClassInfo type) {
        return Arrays.asList(type.superClass(), type.superClassDeclaration());
    }

    @Test
    @DisplayName("the index first, then the class file, then the stub")
    void theIndexFirstThenTheClassFileThenTheStub() {
        var indexed = IndexLookup.stub(DotName.of("p.Indexed"));
        var lookup = new IndexLookup(new IndexBuilder().add(indexed).build());
        var outside = IndexLookup.classFiles(ClassLoader.getPlatformClassLoader());

        var answers = IndexLookup.whereOutsideDescribedBy(outside, () -> List.of(
                lookup.declaration(DotName.of("p.Indexed")), lookup.declaration(SUPPLIER),
                lookup.declaration(DotName.of("p.Nowhere"))));

        assertAll(() -> assertSame(indexed, answers.get(0)),
                () -> assertSame(outside.apply(SUPPLIER).orElseThrow(), answers.get(1)),
                () -> assertEquals(IndexLookup.stub(DotName.of("p.Nowhere")), answers.get(2)),
                () -> assertEquals(IndexLookup.stub(SUPPLIER), lookup.declaration(SUPPLIER),
                        "outside the extension phases, a class outside the index is a stub"));
    }
}
