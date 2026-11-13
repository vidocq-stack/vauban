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

    @Test
    @DisplayName("a parameter counts by its type, not by its name, which jlink strips")
    void aParameterCountsByItsType() {
        var string = new TypeInfo.ClassType(DotName.of("java.lang.String"));

        assertEquals(withMethod("question", string), withMethod("arg0", string));
        assertNotEquals(withMethod("question", string),
                withMethod("question", new TypeInfo.ClassType(DotName.of("java.lang.Integer"))));
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
