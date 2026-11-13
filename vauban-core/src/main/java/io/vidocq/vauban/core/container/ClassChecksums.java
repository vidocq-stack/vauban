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

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/**
 * The checksums of the classes the fingerprint of a deployment is made of (VAU-PERF-005, BUG-20261113-17).
 *
 * <p>A bean class counts by what the build compatible extensions see of it: its model, as the bytecode index holds it
 * and the language model shows it — names, supertypes, modifiers, members, types, annotations. Neither the bodies of
 * its methods count, which no extension sees, nor the debug information of its class file: {@code jlink
 * --strip-debug} removes the source file, the line numbers, the local variables and the parameter names from the
 * classes of an image, and the record its build wrote must stay that image's.
 *
 * <p>An extension also counts by its code, which is what it does: the constant pool and, for each method, the
 * bytecode and its exception table. The debug attributes stay out, and so do the stack maps, which jlink computes
 * again; jlink keeps the constant pool and the bytecode as they are.
 */
final class ClassChecksums {

    private ClassChecksums() {
    }

    /** What the extensions see of a class. */
    static long ofModel(ClassInfo model) {
        var text = new ModelText();
        text.classInfo(model);
        return crc(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The code of a class: its constant pool, and the bytecode and exception table of each method. */
    static long ofCode(byte[] classFile) {
        var crc = new CRC32C();
        crc.update(classFile, 0, endOfConstantPool(classFile));
        for (MethodModel method : ClassFile.of().parse(classFile).methods()) {
            update(crc, method.methodName().stringValue() + method.methodType().stringValue());
            method.findAttribute(Attributes.code()).ifPresent(code -> updateCode(crc, code));
        }
        return crc.getValue();
    }

    private static void updateCode(CRC32C crc, CodeAttribute code) {
        update(crc, code.maxStack() + "," + code.maxLocals());
        crc.update(code.codeArray());
        for (var handler : code.exceptionHandlers()) {
            update(crc, code.labelToBci(handler.tryStart()) + "," + code.labelToBci(handler.tryEnd()) + ","
                    + code.labelToBci(handler.handler()) + "," + handler.catchType().map(PoolEntry::index).orElse(0));
        }
    }

    /**
     * Where the constant pool of a class file ends (JVMS §4.4): its entries hold their size, by tag, and a long or a
     * double takes two slots.
     */
    static int endOfConstantPool(byte[] classFile) {
        int count = unsignedShort(classFile, 8);
        int offset = 10;
        int slot = 1;
        while (slot < count) {
            int tag = classFile[offset];
            offset += switch (tag) {
                case 1 -> 3 + unsignedShort(classFile, offset + 1); // Utf8: its length, then its bytes
                case 7, 8, 16, 19, 20 -> 3;                          // Class, String, MethodType, Module, Package
                case 15 -> 4;                                        // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> 5;               // Integer, Float, the references, NameAndType, Dynamic
                case 5, 6 -> 9;                                      // Long, Double
                default -> throw new IllegalArgumentException("Not a class file: constant pool tag " + tag);
            };
            slot += tag == 5 || tag == 6 ? 2 : 1;
        }
        return offset;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static void update(CRC32C crc, String text) {
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        crc.update('\n');
    }

    private static long crc(byte[] bytes) {
        var crc = new CRC32C();
        crc.update(bytes);
        return crc.getValue();
    }

    /**
     * The model of a class as one text, the same for the same model on any JVM: each value says what it is, a string
     * says its length, a list its size, and the members of an annotation come in name order.
     */
    private static final class ModelText {

        private final StringBuilder text = new StringBuilder(512);

        void classInfo(ClassInfo type) {
            text.append('C');
            name(type.name());
            name(type.superName());
            list(type.interfaces(), this::name);
            number(type.accessFlags());
            list(type.fields(), this::field);
            list(type.methods(), this::method);
            list(type.annotations(), this::annotation);
            string(type.kind().name());
            string(type.simpleName());
            list(type.typeParameters(), this::type);
            text.append(type.genericMembers() ? 'g' : 'e');
        }

        private void field(FieldInfo field) {
            text.append('F');
            string(field.name());
            type(field.type());
            number(field.accessFlags());
            list(field.annotations(), this::annotation);
        }

        private void method(MethodInfo method) {
            text.append('M');
            string(method.name());
            type(method.returnType());
            // A parameter by its type and annotations: its name comes from debug information jlink strips.
            list(method.parameters(), parameter -> {
                type(parameter.type());
                list(parameter.annotations(), this::annotation);
            });
            list(method.exceptionTypes(), this::type);
            number(method.accessFlags());
            list(method.annotations(), this::annotation);
            value(method.defaultValue());
            list(method.typeParameters(), this::type);
        }

        private void annotation(AnnotationInfo annotation) {
            text.append('@');
            name(annotation.name());
            var members = new TreeMap<>(annotation.members());
            text.append(members.size()).append('{');
            members.forEach((member, value) -> {
                string(member);
                value(value);
            });
            text.append('}');
        }

        private void value(AnnotationValue value) {
            switch (value) {
                case null -> text.append('~');
                case AnnotationValue.StringVal(var string) -> {
                    text.append('s');
                    string(string);
                }
                case AnnotationValue.BooleanVal(var bool) -> text.append(bool ? 'T' : 'F');
                case AnnotationValue.ByteVal(var number) -> text.append('b').append(number).append(';');
                case AnnotationValue.CharVal(var character) -> text.append('c').append((int) character).append(';');
                case AnnotationValue.ShortVal(var number) -> text.append('h').append(number).append(';');
                case AnnotationValue.IntVal(var number) -> text.append('i').append(number).append(';');
                case AnnotationValue.LongVal(var number) -> text.append('j').append(number).append(';');
                case AnnotationValue.FloatVal(var number) ->
                        text.append('f').append(Float.floatToIntBits(number)).append(';');
                case AnnotationValue.DoubleVal(var number) ->
                        text.append('d').append(Double.doubleToLongBits(number)).append(';');
                case AnnotationValue.ClassVal(var className) -> {
                    text.append('k');
                    name(className);
                }
                case AnnotationValue.EnumVal(var enumType, var constantName) -> {
                    text.append('e');
                    name(enumType);
                    string(constantName);
                }
                case AnnotationValue.AnnotationVal(var nested) -> annotation(nested);
                case AnnotationValue.ArrayVal(var values) -> {
                    text.append('[');
                    list(values, this::value);
                }
            }
        }

        private void type(TypeInfo type) {
            switch (type) {
                case null -> text.append('~');
                case TypeInfo.VoidType() -> text.append('V');
                case TypeInfo.PrimitiveType(var kind) -> text.append('P').append(kind.name()).append(';');
                case TypeInfo.ClassType(var name) -> {
                    text.append('L');
                    name(name);
                }
                case TypeInfo.ArrayType(var componentType, var dimensions) -> {
                    text.append('A').append(dimensions).append(';');
                    type(componentType);
                }
                case TypeInfo.ParameterizedType(var rawType, var typeArguments) -> {
                    text.append('Q');
                    name(rawType);
                    list(typeArguments, this::type);
                }
                case TypeInfo.TypeVariable(var name, var bounds) -> {
                    text.append('T');
                    string(name);
                    list(bounds, this::type);
                }
                case TypeInfo.WildcardType(var upperBound, var lowerBound) -> {
                    text.append('?');
                    type(upperBound);
                    type(lowerBound);
                }
            }
        }

        private void name(DotName name) {
            string(name == null ? null : name.value());
        }

        private void string(String string) {
            if (string == null) {
                text.append('~');
            } else {
                text.append(string.length()).append('"').append(string);
            }
        }

        private void number(int number) {
            text.append(number).append(';');
        }

        private <T> void list(List<T> items, Consumer<? super T> each) {
            text.append(items.size()).append('[');
            items.forEach(each);
            text.append(']');
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
