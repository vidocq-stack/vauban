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
package io.vidocq.vauban.indexer.scanner;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Step;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.time.Duration;
import java.util.Optional;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * BUG-20261117-60: the bytecode scan reads the {@code RuntimeVisibleTypeAnnotations} of a class, its fields and its
 * methods into the index, placed on the index's own types: a parameter by its position in the parameters the index
 * records, a bound by its position in the bounds it records, a nested type by its innermost name.
 */
@DisplayName("BUG-20261117-60 - the bytecode scan reads the annotations on types")
class ClassFileScannerTypeAnnotationsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.TYPE_USE)
    @interface A {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.TYPE_USE)
    @interface B {
    }

    @java.lang.annotation.Target(ElementType.TYPE_USE)
    @interface Unseen {
    }

    static class Outer {
        class Inner {
            Inner(@A String name, @B List<String> names) {
            }
        }

        static class Nested {
        }
    }

    enum Level {
        LOW("low");

        Level(@A String label) {
        }
    }

    static class Members<T extends @A CharSequence, U extends @B Object, V extends Number & @A Serializable>
            implements @B Comparable<@A Members<T, U, V>> {

        Map<@A String, @B List<? super @A Integer>> map;
        @A int @B [] [] grid;
        Outer.@A Inner inner;
        @A Outer.Inner enclosing;
        Outer.@B Nested nested;
        @Unseen String unseen;

        <@A W extends @B Number> @A String method(@B String first, @A W second) throws @B IllegalStateException {
            return null;
        }

        @Override
        public int compareTo(Members<T, U, V> other) {
            return 0;
        }
    }

    @Test
    @DisplayName("a field: the path along its type, a nested type by its innermost name, runtime annotations only")
    void fieldTypeAnnotations() throws IOException {
        var fields = scan(Members.class).fields().stream()
                .collect(Collectors.toMap(FieldInfo::name, field -> described(field.typeAnnotations())));

        assertEquals("[FIELD 0 0 [TYPE_ARGUMENT 0] A, FIELD 0 0 [TYPE_ARGUMENT 1] B,"
                + " FIELD 0 0 [TYPE_ARGUMENT 1, TYPE_ARGUMENT 0, WILDCARD] A]", fields.get("map"));
        assertEquals("[FIELD 0 0 [] B, FIELD 0 0 [ARRAY, ARRAY] A]", fields.get("grid"));
        assertEquals("[FIELD 0 0 [] A]", fields.get("inner"));
        assertEquals("[]", fields.get("enclosing"));
        assertEquals("[FIELD 0 0 [] B]", fields.get("nested"));
        assertEquals("[]", fields.get("unseen"));
    }

    @Test
    @DisplayName("a class: its supertypes, and its bounds by their position in the bounds the index records")
    void classTypeAnnotations() throws IOException {
        assertEquals("[TYPE_PARAMETER_BOUND 0 0 [] A, TYPE_PARAMETER_BOUND 1 0 [] B, TYPE_PARAMETER_BOUND 2 1 [] A,"
                + " INTERFACE 0 0 [] B, INTERFACE 0 0 [TYPE_ARGUMENT 0] A]",
                described(scan(Members.class).typeAnnotations()));
    }

    @Test
    @DisplayName("a method: its return type, parameters, throws types, type parameters and their bounds")
    void methodTypeAnnotations() throws IOException {
        assertEquals("[RETURN 0 0 [] A, PARAMETER 0 0 [] B, PARAMETER 1 0 [] A, THROWS 0 0 [] B,"
                + " TYPE_PARAMETER 0 0 [] A, TYPE_PARAMETER_BOUND 0 0 [] B]",
                described(method(Members.class, "method")));
    }

    @Test
    @DisplayName("a constructor's parameter by its position in the parameters the index records, implicit ones first")
    void constructorParametersAfterTheImplicitOnes() throws IOException {
        assertEquals("[PARAMETER 1 0 [] A, PARAMETER 2 0 [] B]", described(method(Outer.Inner.class, "<init>")));
        assertEquals("[PARAMETER 2 0 [] A]", described(method(Level.class, "<init>")));
    }

    @Test
    @DisplayName("a class without type annotations has none")
    void noTypeAnnotation() throws IOException {
        var plain = scan(Outer.class);
        assertEquals(List.of(), plain.typeAnnotations());
        assertEquals(List.of(), plain.methods().getFirst().typeAnnotations());
    }

    @Test
    @DisplayName("a step on a type annotation leads one step further along its path")
    void stepsAlongThePath() {
        var annotation = TypeAnnotationInfo.of(Target.FIELD, 0, new io.vidocq.vauban.indexer.model.AnnotationInfo(
                io.vidocq.vauban.indexer.model.DotName.of("A"), Map.of()), Step.typeArgument(1), Step.ARRAY);
        assertEquals(List.of(Step.ARRAY), annotation.after(Step.typeArgument(1)).path());
        assertEquals(null, annotation.after(Step.typeArgument(0)));
        assertEquals(null, annotation.after(Step.typeArgument(1)).after(Step.ARRAY).after(Step.WILDCARD));
    }

    /**
     * {@code InnerClasses} entries that name each other as outer classes, or a class itself: legal for the JVM, which
     * does not check them. The scan of such a class ends, its type annotations read or not; the bound only stops a
     * scan that would never end.
     */
    @Test
    @DisplayName("a class whose InnerClasses entries form a cycle is scanned")
    void cyclicInnerClasses() {
        var x = ClassDesc.of("p.X");
        var y = ClassDesc.of("p.Y");
        var cycle = InnerClassesAttribute.of(InnerClassInfo.of(x, Optional.of(y), Optional.of("X")),
                InnerClassInfo.of(y, Optional.of(x), Optional.of("Y")));
        var itself = InnerClassesAttribute.of(InnerClassInfo.of(x, Optional.of(x), Optional.of("X")));
        for (var inner : List.of(cycle, itself)) {
            var plain = classFile(x, inner, field -> { });
            var annotated = classFile(x, inner, field -> field.with(RuntimeVisibleTypeAnnotationsAttribute.of(
                    java.lang.classfile.TypeAnnotation.of(java.lang.classfile.TypeAnnotation.TargetInfo.ofField(),
                            List.of(), annotationA()))));

            assertEquals("p.X", assertTimeoutPreemptively(Duration.ofMinutes(1), () -> ClassFileScanner.scan(plain))
                    .name().toString());
            assertEquals(1, assertTimeoutPreemptively(Duration.ofMinutes(1), () -> ClassFileScanner.scan(annotated))
                    .fields().size());
        }
    }

    /**
     * An inner class's constructor without a {@code MethodParameters} attribute, as javac before Java 21 compiles a
     * non-generic one: its outer instance is its first parameter all the same, which a type annotation does not
     * number.
     */
    @Test
    @DisplayName("an inner class's constructor without MethodParameters numbers its parameters after the outer one")
    void innerConstructorWithoutMethodParameters() {
        var outer = ClassDesc.of("p.Outer");
        var inner = ClassDesc.of("p.Outer$Inner");
        var bytes = ClassFile.of().build(inner, type -> type.withSuperclass(ConstantDescs.CD_Object)
                .with(InnerClassesAttribute.of(InnerClassInfo.of(inner, Optional.of(outer), Optional.of("Inner"))))
                .withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, outer,
                        ConstantDescs.CD_String), 0, method -> method.with(
                        RuntimeVisibleTypeAnnotationsAttribute.of(java.lang.classfile.TypeAnnotation.of(
                                java.lang.classfile.TypeAnnotation.TargetInfo.ofMethodFormalParameter(0), List.of(),
                                annotationA())))));

        assertEquals("[PARAMETER 1 0 [] A]", described(ClassFileScanner.scan(bytes).methods().stream()
                .filter(method -> method.name().equals("<init>")).findFirst().orElseThrow().typeAnnotations()));
    }

    private static java.lang.classfile.Annotation annotationA() {
        return java.lang.classfile.Annotation.of(A.class.describeConstable().orElseThrow());
    }

    private static byte[] classFile(ClassDesc name, InnerClassesAttribute inner,
            java.util.function.Consumer<java.lang.classfile.FieldBuilder> field) {
        return ClassFile.of().build(name, type -> type.withSuperclass(ConstantDescs.CD_Object).with(inner)
                .withField("x", name, field));
    }

    private static List<TypeAnnotationInfo> method(Class<?> type, String name) throws IOException {
        return scan(type).methods().stream().filter(method -> method.name().equals(name)).findFirst()
                .map(MethodInfo::typeAnnotations).orElseThrow();
    }

    private static String described(List<TypeAnnotationInfo> annotations) {
        return annotations.stream().map(annotation -> annotation.target() + " " + annotation.index() + " "
                + annotation.boundIndex() + " " + annotation.path().stream()
                        .map(step -> step.kind() == Step.Kind.TYPE_ARGUMENT
                                ? "TYPE_ARGUMENT " + step.typeArgumentIndex() : step.kind().name())
                        .collect(Collectors.joining(", ", "[", "]")) + " "
                + annotation.annotation().name().simpleName().substring(
                        annotation.annotation().name().simpleName().lastIndexOf('$') + 1))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static ClassInfo scan(Class<?> type) throws IOException {
        try (var in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return ClassFileScanner.scan(in.readAllBytes());
        }
    }
}
