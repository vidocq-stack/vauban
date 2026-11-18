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
package io.vidocq.vauban.core.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The checksums of the classes of a deployment")
class ClassChecksumsTest {

    private static final DotName A = DotName.of("a.A");
    private static final DotName B = DotName.of("a.B");

    private static ClassInfo type(List<FieldInfo> fields, List<MethodInfo> methods, List<AnnotationInfo> annotations) {
        return new ClassInfo(A, DotName.of("java.lang.Object"), List.of(), 1, fields, methods, annotations,
                ClassInfo.ClassKind.CLASS, "A", List.of(), true);
    }

    private static long annotatedWith(AnnotationValue value) {
        return ClassChecksums.ofModel(type(List.of(), List.of(),
                List.of(new AnnotationInfo(DotName.of("a.Tag"), Map.of("v", value)))));
    }

    private static long withField(TypeInfo fieldType) {
        return ClassChecksums.ofModel(type(List.of(new FieldInfo("f", fieldType, 0, List.of())), List.of(), List.of()));
    }

    private static long withMethod(String parameterName, TypeInfo parameterType) {
        var method = new MethodInfo("answer", new TypeInfo.ClassType(DotName.of("java.lang.String")),
                List.of(new ParameterInfo(parameterName, parameterType, List.of())), List.of(), 1, List.of(), null,
                List.of());
        return ClassChecksums.ofModel(type(List.of(), List.of(method), List.of()));
    }

    private static byte[] jdkClass(String module, String path) throws IOException {
        return Files.readAllBytes(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", module, path));
    }

    private static byte[] vaubanClass(Class<?> type) throws IOException {
        try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("each kind of annotation value counts, and two kinds never read the same")
    void everyAnnotationValueCounts() {
        List<List<AnnotationValue>> pairs = List.of(
                List.of(new AnnotationValue.StringVal("1"), new AnnotationValue.StringVal("2")),
                List.of(new AnnotationValue.BooleanVal(true), new AnnotationValue.BooleanVal(false)),
                List.of(new AnnotationValue.ByteVal((byte) 1), new AnnotationValue.ByteVal((byte) 2)),
                List.of(new AnnotationValue.CharVal('1'), new AnnotationValue.CharVal('2')),
                List.of(new AnnotationValue.ShortVal((short) 1), new AnnotationValue.ShortVal((short) 2)),
                List.of(new AnnotationValue.IntVal(1), new AnnotationValue.IntVal(2)),
                List.of(new AnnotationValue.LongVal(1), new AnnotationValue.LongVal(2)),
                List.of(new AnnotationValue.FloatVal(1), new AnnotationValue.FloatVal(2)),
                List.of(new AnnotationValue.DoubleVal(1), new AnnotationValue.DoubleVal(2)),
                List.of(new AnnotationValue.ClassVal(A), new AnnotationValue.ClassVal(B)),
                List.of(new AnnotationValue.EnumVal(A, "ONE"), new AnnotationValue.EnumVal(A, "TWO")),
                List.of(new AnnotationValue.AnnotationVal(new AnnotationInfo(A, Map.of())),
                        new AnnotationValue.AnnotationVal(new AnnotationInfo(B, Map.of()))),
                List.of(new AnnotationValue.ArrayVal(List.of(new AnnotationValue.IntVal(1))),
                        new AnnotationValue.ArrayVal(List.of(new AnnotationValue.IntVal(1),
                                new AnnotationValue.IntVal(1)))));

        for (var pair : pairs) {
            assertEquals(annotatedWith(pair.get(0)), annotatedWith(pair.get(0)), pair::toString);
            assertNotEquals(annotatedWith(pair.get(0)), annotatedWith(pair.get(1)), pair::toString);
        }
        var ones = pairs.stream().map(pair -> annotatedWith(pair.get(0))).distinct().count();
        assertEquals(pairs.size(), ones, "the first value of each kind gives its own checksum");
    }

    @Test
    @DisplayName("each kind of type counts")
    void everyTypeCounts() {
        var list = DotName.of("java.util.List");
        List<List<TypeInfo>> pairs = List.of(
                List.of(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT),
                        new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.LONG)),
                List.of(new TypeInfo.ClassType(A), new TypeInfo.ClassType(B)),
                List.of(new TypeInfo.ArrayType(new TypeInfo.ClassType(A), 1),
                        new TypeInfo.ArrayType(new TypeInfo.ClassType(A), 2)),
                List.of(new TypeInfo.ParameterizedType(list, List.of(new TypeInfo.ClassType(A))),
                        new TypeInfo.ParameterizedType(list, List.of(new TypeInfo.ClassType(B)))),
                List.of(new TypeInfo.TypeVariable("T", List.of()), new TypeInfo.TypeVariable("U", List.of())),
                List.of(new TypeInfo.WildcardType(new TypeInfo.ClassType(A), null),
                        new TypeInfo.WildcardType(null, new TypeInfo.ClassType(A))),
                List.of(new TypeInfo.VoidType(), new TypeInfo.ClassType(A)));

        for (var pair : pairs) {
            assertEquals(withField(pair.get(0)), withField(pair.get(0)), pair::toString);
            assertNotEquals(withField(pair.get(0)), withField(pair.get(1)), pair::toString);
        }
    }

    /**
     * The class enclosing an inner member class counts, as the extensions see a receiver type of its constructors
     * from it (BUG-20261118-01); a class without one keeps its checksum.
     */
    @Test
    @DisplayName("the enclosing class of an inner member class counts")
    void theEnclosingClassCounts() {
        var plain = type(List.of(), List.of(), List.of());
        var inner = new ClassInfo(A, DotName.of("java.lang.Object"), List.of(), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true, null, null, null, B);
        assertEquals(ClassChecksums.ofModel(plain), ClassChecksums.ofModel(new ClassInfo(A,
                DotName.of("java.lang.Object"), List.of(), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true, null, null, null, null)));
        assertNotEquals(ClassChecksums.ofModel(plain), ClassChecksums.ofModel(inner));
    }

    /**
     * The parameterized type that encloses a type counts where there is one, which the bean types read
     * (BUG-20261117-21): any other type keeps its checksum.
     */
    @Test
    @DisplayName("the enclosing type of a parameterized type counts where there is one")
    void theEnclosingTypeOfAParameterizedTypeCounts() {
        var string = List.<TypeInfo>of(new TypeInfo.ClassType(DotName.of("java.lang.String")));
        var alone = new TypeInfo.ParameterizedType(B, string);
        var owned = new TypeInfo.ParameterizedType(B, string, new TypeInfo.ParameterizedType(A, string));
        var withAlone = new FieldInfo("f", alone, 0, List.of());
        var withOwned = new FieldInfo("f", owned, 0, List.of());
        var withRaw = new FieldInfo("f", new TypeInfo.ParameterizedType(B, string, null), 0, List.of());
        assertEquals(ClassChecksums.ofModel(type(List.of(withAlone), List.of(), List.of())),
                ClassChecksums.ofModel(type(List.of(withRaw), List.of(), List.of())));
        assertNotEquals(ClassChecksums.ofModel(type(List.of(withAlone), List.of(), List.of())),
                ClassChecksums.ofModel(type(List.of(withOwned), List.of(), List.of())));
    }

    /**
     * The modifiers an extension sees count where they differ from the access flags but {@code ACC_SUPER}, a nested
     * class's: a top-level class keeps its checksum (BUG-20261117-37).
     */
    @Test
    @DisplayName("the modifiers count where they are not the access flags")
    void theModifiersCount() {
        var plain = type(List.of(), List.of(), List.of());
        var topLevel = new ClassInfo(A, DotName.of("java.lang.Object"), List.of(), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true, null, null, null, null, 1);
        var nested = new ClassInfo(A, DotName.of("java.lang.Object"), List.of(), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true, null, null, null, null, 1 | 8);
        assertEquals(ClassChecksums.ofModel(plain), ClassChecksums.ofModel(topLevel));
        assertNotEquals(ClassChecksums.ofModel(plain), ClassChecksums.ofModel(nested));
    }

    /**
     * The supertypes as the class declares them count where they carry type arguments, which the extensions see
     * (BUG-20261117-22); a class without any keeps the checksum it had before the index read them, the one of a model
     * built from the raw names alone, so the record of a build by an earlier Vauban stays valid for it.
     */
    @Test
    @DisplayName("the generic supertypes count where they carry type arguments")
    void genericSupertypesCount() {
        var supplier = DotName.of("java.util.function.Supplier");
        var raw = new TypeInfo.ClassType(supplier);
        var ofA = new TypeInfo.ParameterizedType(supplier, List.of(new TypeInfo.ClassType(A)));
        var ofB = new TypeInfo.ParameterizedType(supplier, List.of(new TypeInfo.ClassType(B)));

        assertEquals(ClassChecksums.ofModel(implementing(supplier, null)),
                ClassChecksums.ofModel(implementing(supplier, raw)));
        assertNotEquals(ClassChecksums.ofModel(implementing(supplier, raw)),
                ClassChecksums.ofModel(implementing(supplier, ofA)));
        assertNotEquals(ClassChecksums.ofModel(implementing(supplier, ofA)),
                ClassChecksums.ofModel(implementing(supplier, ofB)));
        var extendingBOfA = new ClassInfo(A, B, List.of(), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true, new TypeInfo.ParameterizedType(B,
                        List.of(new TypeInfo.ClassType(A))), null);
        assertNotEquals(ClassChecksums.ofModel(new ClassInfo(A, B, List.of(), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true)), ClassChecksums.ofModel(extendingBOfA));
    }

    /**
     * The checksum of a class whose supertypes carry no type arguments is the one an earlier Vauban wrote in the
     * frozen record: {@code 374142584L} is what {@code ClassChecksums.ofModel} gave this model on 455067f5, the
     * commit before the index read the generic supertypes (BUG-20261117-22). The scan of a class that names a generic
     * class raw gives the checksum of its model without generic supertypes; both keep the modifiers of the nested
     * class, which count (BUG-20261117-37).
     */
    @Test
    @DisplayName("a class whose supertypes carry no type arguments keeps the checksum an earlier Vauban wrote")
    void aClassWithRawSupertypesKeepsItsChecksum() throws IOException {
        var t = new TypeInfo.TypeVariable("T", List.of());
        var items = new FieldInfo("items", new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(t)),
                2, List.of());
        var serializable = DotName.of("java.io.Serializable");
        var comparable = DotName.of("java.lang.Comparable");
        var rawSupertypes = new ClassInfo(A, B, List.of(serializable, comparable), 1, List.of(items), List.of(),
                List.of(), ClassInfo.ClassKind.CLASS, "A", List.of(t), true);
        var declaredRaw = new ClassInfo(A, B, List.of(serializable, comparable), 1, List.of(items), List.of(),
                List.of(), ClassInfo.ClassKind.CLASS, "A", List.of(t), true, new TypeInfo.ClassType(B),
                List.of(new TypeInfo.ClassType(serializable), new TypeInfo.ClassType(comparable)));
        var scanned = ClassFileScanner.scan(nestedClass(RawFixture.class));
        var scannedWithoutGenericSupertypes = new ClassInfo(scanned.name(), scanned.superName(), scanned.interfaces(),
                scanned.accessFlags(), scanned.fields(), scanned.methods(), scanned.annotations(), scanned.kind(),
                scanned.simpleName(), scanned.typeParameters(), scanned.genericMembers(), null, null, null, null,
                scanned.modifiers());

        assertEquals(374142584L, ClassChecksums.ofModel(rawSupertypes));
        assertEquals(374142584L, ClassChecksums.ofModel(declaredRaw));
        assertEquals(ClassChecksums.ofModel(scannedWithoutGenericSupertypes), ClassChecksums.ofModel(scanned));
    }

    /** Names {@code ArrayList} and {@code Comparable} raw. */
    @SuppressWarnings("rawtypes")
    abstract static class RawFixture extends java.util.ArrayList implements Comparable {
        private static final long serialVersionUID = 1L;
    }

    private static byte[] nestedClass(Class<?> type) throws IOException {
        var name = type.getName();
        try (var in = type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
            return in.readAllBytes();
        }
    }

    /** {@code a.A implements} the interface {@code name}, declared {@code declared}, raw when {@code null}. */
    private static ClassInfo implementing(DotName name, TypeInfo declared) {
        return new ClassInfo(A, DotName.of("java.lang.Object"), List.of(name), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true, null, declared == null ? null : List.of(declared));
    }

    @Test
    @DisplayName("a parameter counts by its type, not by its name, which jlink strips")
    void aParameterCountsByItsType() {
        var string = new TypeInfo.ClassType(DotName.of("java.lang.String"));

        assertEquals(withMethod("question", string), withMethod("arg0", string));
        assertNotEquals(withMethod("question", string),
                withMethod("question", new TypeInfo.ClassType(DotName.of("java.lang.Integer"))));
    }

    @Test
    @DisplayName("a class whose parameters javac marks itself keeps its checksum once stripped (BUG-20261118-60)")
    void classesWithMarkedParametersKeepTheirChecksumsOnceStripped() throws IOException {
        int marked = 0;
        for (Class<?> type : StrippedShapes.all()) {
            byte[] built = binaryClass(type);
            byte[] linked = JlinkStripping.strip(built);
            marked += markedMethods(built);
            assertEquals(0, markedMethods(linked), () -> type + " keeps no MethodParameters once stripped");

            assertEquals(ClassFileScanner.scan(built).methods().stream().map(ClassChecksumsTest::shape).toList(),
                    ClassFileScanner.scan(linked).methods().stream().map(ClassChecksumsTest::shape).toList(),
                    type::toString);
            assertEquals(ClassChecksums.ofModel(ClassFileScanner.scan(built)),
                    ClassChecksums.ofModel(ClassFileScanner.scan(linked)), type::toString);
        }
        assertTrue(marked >= 8, "javac marks the parameters it adds: " + marked + " methods");
    }

    /** A method by what its checksum counts of its parameters: their types, not their names. */
    private static String shape(MethodInfo method) {
        return method.name() + method.parameters().stream().map(ParameterInfo::type).toList();
    }

    private static long markedMethods(byte[] classFile) {
        return ClassFile.of().parse(classFile).methods().stream()
                .filter(method -> method.findAttribute(Attributes.methodParameters()).isPresent()).count();
    }

    private static byte[] binaryClass(Class<?> type) throws IOException {
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("Vauban's own classes, compiled with their parameter names, keep their checksums once stripped")
    void realClassesKeepTheirChecksumsOnceStripped() throws IOException {
        for (Class<?> type : List.of(VaubanContainerBuilder.class, ClassChecksums.class, DeploymentFingerprint.class)) {
            byte[] built = vaubanClass(type);
            byte[] linked = JlinkStripping.strip(built);
            assertTrue(linked.length < built.length, type + " has debug information to strip");

            assertEquals(ClassChecksums.ofModel(ClassFileScanner.scan(built)),
                    ClassChecksums.ofModel(ClassFileScanner.scan(linked)), type::toString);
            assertEquals(ClassChecksums.ofCode(built), ClassChecksums.ofCode(linked), type::toString);
        }
        var handlers = ClassFile.of().parse(vaubanClass(VaubanContainerBuilder.class)).methods().stream()
                .flatMap(method -> method.findAttribute(Attributes.code()).stream())
                .mapToInt(code -> code.exceptionHandlers().size()).sum();
        assertTrue(handlers > 0, "the code compared holds exception handlers");
    }

    @Test
    @DisplayName("the constant pool ends where the class's flags and name start, whatever its entries")
    void theConstantPoolEnds() throws IOException {
        var classes = Map.of(
                "longs", jdkClass("java.base", "java/lang/Long.class"),
                "doubles", jdkClass("java.base", "java/lang/Double.class"),
                "method handles and invokedynamic", jdkClass("java.base", "java/util/stream/Collectors.class"),
                "modules and packages", jdkClass("java.base", "module-info.class"),
                "a class of Vauban", vaubanClass(VaubanContainerBuilder.class));

        classes.forEach((what, classFile) -> {
            var model = ClassFile.of().parse(classFile);
            int end = ClassChecksums.endOfConstantPool(classFile);
            assertEquals(model.flags().flagsMask(), unsignedShort(classFile, end), what);
            assertEquals(model.thisClass().index(), unsignedShort(classFile, end + 2), what);
        });
    }

    /**
     * The annotations on the types a class declares count, which the extensions see (BUG-20261117-60): on a field, a
     * method or the class, by their target, index, bound and path. A class without them keeps the checksum it had, and
     * with it the record an earlier Vauban wrote: {@link #aClassWithRawSupertypesKeepsItsChecksum} pins it.
     */
    @Test
    @DisplayName("the annotations on types count, where a class has any")
    void typeAnnotationsCount() {
        var marker = new AnnotationInfo(B, Map.of());
        var onField = TypeAnnotationInfo.of(TypeAnnotationInfo.Target.FIELD, 0, marker);
        var inField = TypeAnnotationInfo.of(TypeAnnotationInfo.Target.FIELD, 0, marker,
                TypeAnnotationInfo.Step.typeArgument(0));
        var string = new TypeInfo.ClassType(DotName.of("java.lang.String"));
        var plain = new FieldInfo("f", string, 2, List.of());
        var method = new MethodInfo("m", string, List.of(), List.of(), 1, List.of(), null, null,
                List.of(TypeAnnotationInfo.of(TypeAnnotationInfo.Target.RETURN, 0, marker)));
        var onClass = new ClassInfo(A, DotName.of("java.lang.Object"), List.of(), 1, List.of(), List.of(), List.of(),
                ClassInfo.ClassKind.CLASS, "A", List.of(), true, null, null,
                List.of(TypeAnnotationInfo.of(TypeAnnotationInfo.Target.SUPERCLASS, 0, marker)));
        long none = ClassChecksums.ofModel(type(List.of(plain), List.of(), List.of()));

        assertNotEquals(none, ClassChecksums.ofModel(type(List.of(new FieldInfo("f", string, 2, List.of(),
                List.of(onField))), List.of(), List.of())));
        assertNotEquals(ClassChecksums.ofModel(type(List.of(new FieldInfo("f", string, 2, List.of(),
                List.of(onField))), List.of(), List.of())), ClassChecksums.ofModel(type(List.of(new FieldInfo("f",
                string, 2, List.of(), List.of(inField))), List.of(), List.of())));
        assertNotEquals(ClassChecksums.ofModel(type(List.of(), List.of(new MethodInfo("m", string, List.of(),
                List.of(), 1, List.of())), List.of())), ClassChecksums.ofModel(type(List.of(), List.of(method),
                List.of())));
        assertNotEquals(ClassChecksums.ofModel(type(List.of(), List.of(), List.of())), ClassChecksums.ofModel(onClass));
    }

    @Test
    @DisplayName("bytes that are no class file are refused")
    void notAClassFile() {
        byte[] unknownTag = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 69, 0, 2, 99};

        assertThrows(IllegalArgumentException.class, () -> ClassChecksums.endOfConstantPool(unknownTag));
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }
}
