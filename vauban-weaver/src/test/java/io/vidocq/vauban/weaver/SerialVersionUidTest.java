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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.LongEntry;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The marker constructor is not private, so it changes the {@code serialVersionUID} the JVM computes for a
 * {@code Serializable} class that declares none: weaving keeps the one of the original class (BUG-20261118-95).
 */
@DisplayName("ProxyLinkWeaver keeps the serialVersionUID of the class it weaves (BUG-20261118-95)")
class SerialVersionUidTest {

    /** A shape of langchain4j-cdi's classes: no no-arg constructor, state, a static initializer. */
    public static class Gadget implements Serializable {
        private static final List<String> NAMES = List.of("a");
        protected final String label;
        transient int cache;
        private int count;

        public Gadget(String label) {
            this.label = label;
        }

        Gadget(String label, Gadget other) {
            this(label + other.label);
        }

        public String label() {
            return label + count + NAMES.size();
        }

        protected synchronized void touch(int[] values, Gadget other) {
            count += values.length + other.count;
        }

        private void secret() {
            cache++;
        }
    }

    /** Package-private, final, with an interface declared after another one. */
    static final class Plain implements Comparable<Plain>, Serializable {
        @Override
        public int compareTo(Plain other) {
            return 0;
        }
    }

    /** Declares its own value: weaving keeps it as it is. */
    public static class Declared implements Serializable {
        private static final long serialVersionUID = 42L;

        public Declared(String ignored) {
            // nothing
        }
    }

    static List<Class<?>> serializableClasses() {
        return List.of(Gadget.class, Plain.class, Declared.class);
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(in, type.getName());
            return in.readAllBytes();
        }
    }

    /** The value of the {@code serialVersionUID} field {@code bytes} declare, or {@code null}. */
    private static Long declaredSerialVersionUid(byte[] bytes) {
        var fields = ClassFile.of().parse(bytes).fields().stream()
                .filter(field -> field.fieldName().equalsString("serialVersionUID")).toList();
        if (fields.isEmpty()) {
            return null;
        }
        assertEquals(1, fields.size(), "one field");
        var value = fields.getFirst().findAttribute(java.lang.classfile.Attributes.constantValue())
                .map(ConstantValueAttribute::constant).orElseThrow();
        return ((LongEntry) value).longValue();
    }

    @ParameterizedTest
    @MethodSource("serializableClasses")
    @DisplayName("the value read from the class file is the one the JVM computes")
    void theDefaultSerialVersionUidIsTheJvmOne(Class<?> type) throws IOException {
        long jvm = ObjectStreamClass.lookup(type).getSerialVersionUID();
        if (declaredSerialVersionUid(bytesOf(type)) == null) {
            assertEquals(jvm, ProxyLinkWeaver.defaultSerialVersionUid(bytesOf(type)), type.getName());
        }
    }

    @Test
    @DisplayName("a class that declares none keeps the value of its original bytes once woven")
    void aWovenClassKeepsItsSerialVersionUid() throws IOException {
        byte[] original = bytesOf(Gadget.class);
        byte[] woven = ProxyLinkWeaver.addMarkerConstructorKeepingSerialVersionUid(original,
                ProxyLinkWeaver.SuperChain.NO_ARG);

        assertNotNull(woven);
        assertEquals(ObjectStreamClass.lookup(Gadget.class).getSerialVersionUID(), declaredSerialVersionUid(woven));
        assertEquals(ProxyLinkWeaver.defaultSerialVersionUid(original), declaredSerialVersionUid(woven));
        assertNull(ProxyLinkWeaver.addMarkerConstructorKeepingSerialVersionUid(woven, ProxyLinkWeaver.SuperChain.NO_ARG),
                "woven once only");
    }

    @Test
    @DisplayName("a class that declares its value keeps that one, and one field")
    void aDeclaredSerialVersionUidStaysAsItIs() throws IOException {
        byte[] woven = ProxyLinkWeaver.addMarkerConstructorKeepingSerialVersionUid(bytesOf(Declared.class),
                ProxyLinkWeaver.SuperChain.NO_ARG);

        assertEquals(42L, declaredSerialVersionUid(woven));
    }

    @Test
    @DisplayName("addMarkerConstructor alone still adds no field: Vidocq's staged copy pins the value itself")
    void theMarkerAloneAddsNoField() throws IOException {
        byte[] woven = ProxyLinkWeaver.addMarkerConstructor(bytesOf(Gadget.class), ProxyLinkWeaver.SuperChain.NO_ARG);

        assertNull(declaredSerialVersionUid(woven));
    }
}
