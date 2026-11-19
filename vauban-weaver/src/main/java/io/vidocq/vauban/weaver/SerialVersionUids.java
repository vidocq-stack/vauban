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
package io.vidocq.vauban.weaver;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code serialVersionUID} of a class, read from its class file: the algorithm of the Java Object Serialization
 * Specification, section 4.6, that {@code ObjectStreamClass} runs through reflection on a class that declares none
 * (BUG-20261118-95). Vidocq's {@code StagedProxyLinks} runs the same algorithm, and both give the JVM's value.
 */
final class SerialVersionUids {

    static final String FIELD = "serialVersionUID";
    private static final int CLASS_MASK = Modifier.PUBLIC | Modifier.FINAL | Modifier.INTERFACE | Modifier.ABSTRACT;
    private static final int FIELD_MASK = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED | Modifier.STATIC
            | Modifier.FINAL | Modifier.VOLATILE | Modifier.TRANSIENT;
    private static final int METHOD_MASK = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED | Modifier.STATIC
            | Modifier.FINAL | Modifier.SYNCHRONIZED | Modifier.NATIVE | Modifier.ABSTRACT | Modifier.STRICT;
    private static final String CLASS_INITIALIZER = "<clinit>";

    private SerialVersionUids() {
    }

    /** Whether {@code model} declares a {@code serialVersionUID} field. */
    static boolean declares(ClassModel model) {
        return model.fields().stream().anyMatch(field -> field.fieldName().equalsString(FIELD));
    }

    /** {@code bytes} with a {@code private static final long serialVersionUID} of {@code value}. */
    static byte[] with(byte[] bytes, long value) {
        ClassFile files = ClassFile.of();
        return files.transformClass(files.parse(bytes), ClassTransform.endHandler(builder -> builder.withField(
                FIELD, ConstantDescs.CD_long, field -> field
                        .withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
                        .with(ConstantValueAttribute.of(value)))));
    }

    /** The value the JVM computes for the class {@code model} describes when it declares none. */
    static long computed(ClassModel model) {
        List<MethodModel> methods = model.methods().stream()
                .filter(method -> !method.methodName().stringValue().startsWith("<")).toList();
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF(binaryName(model.thisClass()));
            out.writeInt(classModifiers(model, !methods.isEmpty()));
            for (String name : model.interfaces().stream().map(SerialVersionUids::binaryName).sorted().toList()) {
                out.writeUTF(name);
            }
            writeFields(out, model.fields());
            if (model.methods().stream().anyMatch(method -> method.methodName().equalsString(CLASS_INITIALIZER))) {
                out.writeUTF(CLASS_INITIALIZER);
                out.writeInt(Modifier.STATIC);
                out.writeUTF("()V");
            }
            writeMembers(out, model.methods().stream()
                    .filter(method -> method.methodName().equalsString(ConstantDescs.INIT_NAME)).toList(), true);
            writeMembers(out, methods, false);
            out.flush();
            byte[] hash = MessageDigest.getInstance("SHA").digest(buffer.toByteArray());
            long value = 0;
            for (int i = Math.min(hash.length, 8) - 1; i >= 0; i--) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            return value;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot compute the serialVersionUID of " + model.thisClass().name(), e);
        }
    }

    /** The fields by name, private static and private transient ones aside. */
    private static void writeFields(DataOutputStream out, List<FieldModel> fields) throws IOException {
        for (FieldModel field : fields.stream()
                .sorted(Comparator.comparing(field -> field.fieldName().stringValue())).toList()) {
            int flags = field.flags().flagsMask() & FIELD_MASK;
            if ((flags & Modifier.PRIVATE) == 0 || (flags & (Modifier.STATIC | Modifier.TRANSIENT)) == 0) {
                out.writeUTF(field.fieldName().stringValue());
                out.writeInt(flags);
                out.writeUTF(field.fieldType().stringValue());
            }
        }
    }

    /** The non-private constructors or methods, sorted by signature (by name first for methods). */
    private static void writeMembers(DataOutputStream out, List<MethodModel> members, boolean constructors)
            throws IOException {
        Comparator<MethodModel> bySignature = Comparator.comparing(method -> method.methodType().stringValue());
        Comparator<MethodModel> order = constructors ? bySignature
                : Comparator.<MethodModel, String>comparing(method -> method.methodName().stringValue())
                        .thenComparing(bySignature);
        for (MethodModel member : members.stream().sorted(order).toList()) {
            int flags = member.flags().flagsMask() & METHOD_MASK;
            if ((flags & Modifier.PRIVATE) == 0) {
                out.writeUTF(member.methodName().stringValue());
                out.writeInt(flags);
                out.writeUTF(member.methodType().stringValue().replace('/', '.'));
            }
        }
    }

    /** The modifiers {@code Class.getModifiers()} gives: those of its inner class entry for a nested class. */
    private static int classModifiers(ClassModel model, boolean hasMethods) {
        int flags = model.flags().flagsMask();
        for (var inner : model.findAttributes(Attributes.innerClasses())) {
            for (var info : inner.classes()) {
                if (info.innerClass().asInternalName().equals(model.thisClass().asInternalName())) {
                    flags = info.flagsMask();
                }
            }
        }
        flags &= CLASS_MASK;
        if ((flags & Modifier.INTERFACE) != 0) {
            flags = hasMethods ? flags | Modifier.ABSTRACT : flags & ~Modifier.ABSTRACT;
        }
        return flags;
    }

    private static String binaryName(ClassEntry entry) {
        return entry.asInternalName().replace('/', '.');
    }
}
