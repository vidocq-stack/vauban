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
package io.vidocq.vauban.core.extensions;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261117-31: the language model describes a class the index does not hold as the index would have described it,
 * from its class file: a class of a dependency jar (Mansart's {@code CrudRepository}), of the JDK, or of the
 * application that the deployment did not add. It gave an empty stub (VAU-BCE-001): superclass {@code Object}, no
 * interface, no type parameter, no member. Every path to such a declaration gives the same description: a type's
 * {@code declaration()}, {@code superClassDeclaration()}, {@code superInterfacesDeclarations()}, an annotation's
 * {@code declaration()} and an enum value's {@code asEnumClass()}. A class no class file describes keeps the answer it
 * had: a stub behind a type's {@code declaration()}, no superclass declaration, an interface left out, an exception
 * from an annotation's {@code declaration()} and from {@code asEnumClass()}. The bean discovery sees none of these
 * classes.
 */
@DisplayName("BUG-20261117-31 - the language model describes a class outside the index")
class BceUnindexedClassTest {

    private static final String PREFIX = BceUnindexedClassTest.class.getName() + "$";
    private static final String NOWHERE = "io.vidocq.vauban.nowhere.Nowhere";
    private static final String KEPT = "io.vidocq.vauban.kept.Kept";
    private static final String GONE = "io.vidocq.vauban.gone.";

    public interface Basic<T, K> {
    }

    public interface Crud<T, K> extends Basic<T, K> {
    }

    public static class Note {
    }

    public enum Grade {
        LOW, HIGH
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Graded {
        Grade value();
    }

    /** Mansart's shape: in the deployment, above two interfaces outside it. */
    @Graded(Grade.HIGH)
    public interface Archive extends Crud<Note, Long> {
    }

    /** A bean of the deployment whose types reach the interfaces outside it. */
    @Dependent
    public static class Archivist implements Archive {
    }

    /** An application class on the class path that the deployment does not add. */
    @Named("elsewhere")
    public static class Elsewhere<N extends Number> implements Basic<N, String> {
        public N amount;

        public N amount() {
            return amount;
        }
    }

    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    /** Records, in {@code @Synthesis}, the declarations of the classes outside the index, reached by type. */
    public static class OutsideExtension implements BuildCompatibleExtension {

        @Synthesis
        public void outside(Types types) {
            var archive = types.ofClass(Archive.class.getName()).declaration();
            // Down the declarations, Archive's then Crud's; the processor's test walks down the types
            var below = archive;
            for (int depth = 0; depth < 2; depth++) {
                var declarations = below.superInterfacesDeclarations();
                SEEN.add(named(below.name()) + " superInterfacesDeclarations " + names(declarations));
                below = declarations.getFirst();
                SEEN.add(declared(below));
            }
            SEEN.add(declared(types.ofClass(Supplier.class.getName()).declaration()));
            var elsewhere = types.ofClass(Elsewhere.class.getName()).declaration();
            SEEN.add(declared(elsewhere));
            SEEN.add("Elsewhere fields " + names(elsewhere.fields()) + ", methods " + names(elsewhere.methods())
                    + ", constructors " + elsewhere.constructors().size() + ", annotations "
                    + elsewhere.annotations().stream().map(AnnotationInfo::name).toList());
            var object = elsewhere.superClassDeclaration();
            SEEN.add("Elsewhere superClassDeclaration " + (object == null ? null : object.name() + " declares "
                    + names(object.methods()).contains("toString") + " toString, superClassDeclaration "
                    + object.superClassDeclaration()));
            SEEN.add("Archive superClassDeclaration " + archive.superClassDeclaration());
            SEEN.add(declared(types.ofClass(NOWHERE).declaration()));
        }
    }

    /** Records the declaration of an annotation outside the index, and that of the enum class of its value. */
    public static class AnnotationExtension implements BuildCompatibleExtension {

        @Synthesis
        public void annotation(Types types) {
            var graded = types.ofClass(Archive.class.getName()).declaration().annotation(Graded.class);
            SEEN.add(declared(graded.declaration()) + ", methods " + names(graded.declaration().methods()));
            SEEN.add(declared(graded.value().asEnumClass()));
        }
    }

    @Test
    @DisplayName("Mansart's shape, a JDK class and a class not added are described; a missing class is a stub")
    void aClassOutsideTheIndexIsDescribed() {
        var crud = "Crud: superClass=null; superInterfaces=[PARAMETERIZED_TYPE Basic<T, K> of TYPE_VARIABLE,"
                + " TYPE_VARIABLE]; typeParameters=[T extends java.lang.Object, K extends java.lang.Object]; interface";
        var basic = "Basic: superClass=null; superInterfaces=[]; typeParameters=[T extends java.lang.Object,"
                + " K extends java.lang.Object]; interface";
        var supplier = "java.util.function.Supplier: superClass=null; superInterfaces=[];"
                + " typeParameters=[T extends java.lang.Object]; interface";
        var elsewhere = "Elsewhere: superClass=CLASS java.lang.Object; superInterfaces=[PARAMETERIZED_TYPE"
                + " Basic<N, java.lang.String> of TYPE_VARIABLE, CLASS]; typeParameters=[N extends java.lang.Number];"
                + " class";
        var nowhere = NOWHERE + ": superClass=CLASS java.lang.Object; superInterfaces=[]; typeParameters=[]; class";

        var seen = seen(OutsideExtension.class);

        assertEquals(List.of("Archive superInterfacesDeclarations [Crud]", crud,
                "Crud superInterfacesDeclarations [Basic]", basic, supplier, elsewhere,
                "Elsewhere fields [amount], methods [amount], constructors 1, annotations [jakarta.inject.Named]",
                "Elsewhere superClassDeclaration java.lang.Object declares true toString, superClassDeclaration null",
                "Archive superClassDeclaration null", nowhere), seen);
    }

    /**
     * Records each path from {@code Kept}, whose class file the deployment's loader finds, to the classes of
     * {@code io.vidocq.vauban.gone}, which no class file describes: its superclass, one of its interfaces, its two
     * annotations and the enum class of a value.
     */
    public static class UnseenExtension implements BuildCompatibleExtension {

        @Synthesis
        public void unseen(Types types) {
            var kept = types.ofClass(KEPT).declaration();
            SEEN.add("superClass declaration " + named(kept.superClass().asClass().declaration().name())
                    + ", superClassDeclaration " + kept.superClassDeclaration());
            SEEN.add("superInterfacesDeclarations " + names(kept.superInterfacesDeclarations()));
            for (var annotation : kept.annotations()) {
                SEEN.add(annotation.name() + " " + thrownBy(annotation::declaration));
                if (annotation.hasValue()) {
                    SEEN.add("its asEnumClass " + thrownBy(() -> annotation.value().asEnumClass()));
                }
            }
        }

        /** What {@code path} throws, or the class it gives. */
        private static String thrownBy(Supplier<ClassInfo> path) {
            try {
                return "no exception, " + path.get().name();
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName() + " " + e.getMessage();
            }
        }
    }

    /**
     * A path that gave {@code null}, left a class out or threw keeps that answer for a class no class file describes:
     * a consumer keeps its "I cannot see this class" path, as Dirac's reflective scan when
     * {@code superClassDeclaration()} is {@code null}, and Foy's, which catches the {@code IllegalArgumentException}
     * of an annotation's {@code declaration()}. A type's {@code declaration()} gives the stub it gave.
     */
    @Test
    @DisplayName("a class no class file describes keeps its old answers: null, left out, IllegalArgumentException")
    void aClassNoClassFileDescribesKeepsItsOldAnswers(@TempDir Path classes) throws IOException {
        var classFile = classes.resolve(KEPT.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, keptClassFile());
        SEEN.clear();

        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                BceUnindexedClassTest.class.getClassLoader());
                var _ = SeContainerInitializer.newInstance().setClassLoader(loader)
                        .addBeanClasses(Archive.class, Archivist.class, UnseenExtension.class).initialize()) {
            var notFound = "IllegalArgumentException Class not found in index: " + GONE;
            var seen = List.copyOf(SEEN);
            assertEquals(List.of("superClass declaration " + GONE + "Base, superClassDeclaration null",
                    "superInterfacesDeclarations [java.io.Serializable]", GONE + "Marker " + notFound + "Marker",
                    GONE + "Rated " + notFound + "Rated", "its asEnumClass " + notFound + "Grade"), seen);
        }
    }

    /**
     * The class file of {@code Kept}, whose superclass {@code Base}, interface {@code Contract}, annotations
     * {@code @Marker} and {@code @Rated(Grade.HIGH)} and enum {@code Grade} have no class file anywhere.
     */
    private static byte[] keptClassFile() {
        var marker = java.lang.classfile.Annotation.of(ClassDesc.of(GONE + "Marker"));
        var rated = java.lang.classfile.Annotation.of(ClassDesc.of(GONE + "Rated"),
                AnnotationElement.of("value", AnnotationValue.ofEnum(ClassDesc.of(GONE + "Grade"), "HIGH")));
        return ClassFile.of().build(ClassDesc.of(KEPT), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ClassDesc.of(GONE + "Base"))
                .withInterfaceSymbols(ClassDesc.of(GONE + "Contract"), ClassDesc.of(Serializable.class.getName()))
                .with(RuntimeVisibleAnnotationsAttribute.of(marker, rated)));
    }

    @Test
    @DisplayName("an annotation and an enum class outside the index are described")
    void anAnnotationAndAnEnumOutsideTheIndexAreDescribed() {
        var graded = "Graded: superClass=null; superInterfaces=[CLASS java.lang.annotation.Annotation];"
                + " typeParameters=[]; annotation, methods [value, equals, hashCode, toString, annotationType]";
        var grade = "Grade: superClass=PARAMETERIZED_TYPE java.lang.Enum<Grade> of CLASS; superInterfaces=[];"
                + " typeParameters=[]; enum";

        assertEquals(List.of(graded, grade), seen(AnnotationExtension.class));
    }

    /**
     * The bean discovery does not see the classes the language model describes: the deployment has the bean it had,
     * with the types it had, and none of a class it did not add.
     */
    @Test
    @DisplayName("the classes described for the language model do not join the deployment")
    void theDescribedClassesDoNotJoinTheDeployment() {
        SEEN.clear();
        try (var container = deployment(OutsideExtension.class)) {
            var manager = container.getBeanManager();
            var beans = manager.getBeans(Object.class, Any.Literal.INSTANCE).stream()
                    .filter(bean -> bean.getBeanClass().getName().startsWith(PREFIX))
                    .map(bean -> named(bean.getBeanClass().getName()) + " " + bean.getTypes().stream()
                            .map(type -> named(type.getTypeName().replace(" ", ""))).sorted().toList())
                    .toList();

            assertEquals(List.of("Archivist [Archive, Archivist, Basic<Note,java.lang.Long>,"
                    + " Crud<Note,java.lang.Long>, java.lang.Object]"), beans);
            assertEquals(0, manager.getBeans(Elsewhere.class).size(), "a class the deployment did not add");
        }
    }

    /** What {@code extension} records when the container runs it at start. */
    private static List<String> seen(Class<? extends BuildCompatibleExtension> extension) {
        SEEN.clear();
        try (var _ = deployment(extension)) {
            return List.copyOf(SEEN);
        }
    }

    /** A deployment of {@link Archive} and {@link Archivist}, with {@code extension}: nothing else is added. */
    private static SeContainer deployment(Class<? extends BuildCompatibleExtension> extension) {
        return SeContainerInitializer.newInstance().addBeanClasses(Archive.class, Archivist.class, extension)
                .initialize();
    }

    /** The supertypes, the type parameters and the kind of {@code type}. */
    private static String declared(ClassInfo type) {
        return named(BceClassSupertypesTest.supertypesOf(type)) + "; typeParameters=" + type.typeParameters() + "; "
                + kindOf(type);
    }

    private static String kindOf(ClassInfo type) {
        if (type.isAnnotation()) {
            return "annotation";
        }
        if (type.isInterface()) {
            return "interface";
        }
        return type.isEnum() ? "enum" : "class";
    }

    private static List<String> names(Collection<? extends DeclarationInfo> declarations) {
        return declarations.stream().map(BceUnindexedClassTest::nameOf).toList();
    }

    private static String nameOf(DeclarationInfo declaration) {
        return switch (declaration.kind()) {
            case CLASS -> named(declaration.asClass().name());
            case FIELD -> declaration.asField().name();
            case METHOD -> declaration.asMethod().name();
            default -> declaration.toString();
        };
    }

    /** {@code text} with the classes of this test named by their simple names. */
    private static String named(String text) {
        return text.replace(PREFIX, "");
    }
}
