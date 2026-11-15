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
package io.vidocq.vauban.maven.modularize;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Changes the class a few method references of a class file name, in its constant pool and nowhere else.
 *
 * <p>The Class-File API rebuilds the code of a method it transforms, and with it the stack maps, which needs the
 * hierarchy of every type the code merges: types of the library and of its dependencies, which a build cannot always
 * load. A method reference names its class through a constant, so pointing that constant elsewhere leaves the code,
 * and its stack maps, byte for byte as they were.</p>
 */
final class ConstantPoolPatch {

    private static final int CONSTANT_POOL_COUNT = 8;
    private static final int UTF8 = 1;
    private static final int CLASS = 7;
    private static final int METHODREF = 10;

    private ConstantPoolPatch() {
    }

    /**
     * {@code bytes} with the method references at {@code indexes} pointing to the class {@code owner} (an internal
     * name), added at the end of the constant pool.
     *
     * @throws IllegalArgumentException if an index is not a method reference, or the constant pool is full
     */
    static byte[] redirectOwner(byte[] bytes, Set<Integer> indexes, String owner) {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int count = Short.toUnsignedInt(in.getShort(CONSTANT_POOL_COUNT));
        int[] offsets = new int[count];
        int position = CONSTANT_POOL_COUNT + 2;
        int index = 1;
        while (index < count) {
            offsets[index] = position;
            int tag = Byte.toUnsignedInt(in.get(position));
            position += 1 + entrySize(tag, in, position);
            index += tag == 5 || tag == 6 ? 2 : 1; // a long or a double takes two entries
        }
        int end = position;
        int utf8Index = count;
        int classIndex = count + 1;
        if (classIndex + 1 > 0xFFFF) {
            throw new IllegalArgumentException("the constant pool is full");
        }
        byte[] out = new byte[bytes.length + 3 + owner.length() * 3 + 3];
        ByteBuffer patched = ByteBuffer.wrap(out);
        patched.put(bytes, 0, end);
        patched.putShort(CONSTANT_POOL_COUNT, (short) (count + 2));
        for (int ref : indexes) {
            if (ref <= 0 || ref >= count || offsets[ref] == 0 || bytes[offsets[ref]] != METHODREF) {
                throw new IllegalArgumentException("constant " + ref + " is not a method reference");
            }
            patched.putShort(offsets[ref] + 1, (short) classIndex);
        }
        byte[] name = modifiedUtf8(owner);
        patched.put((byte) UTF8).put(name);
        patched.put((byte) CLASS).putShort((short) utf8Index);
        patched.put(bytes, end, bytes.length - end);
        byte[] result = new byte[patched.position()];
        System.arraycopy(out, 0, result, 0, result.length);
        return result;
    }

    /** The size of an entry after its tag, at {@code position} in {@code in}. */
    private static int entrySize(int tag, ByteBuffer in, int position) {
        return switch (tag) {
            case UTF8 -> 2 + Short.toUnsignedInt(in.getShort(position + 1));
            case 3, 4, 9, METHODREF, 11, 12, 17, 18 -> 4;
            case 5, 6 -> 8;
            case CLASS, 8, 16, 19, 20 -> 2;
            case 15 -> 3;
            default -> throw new IllegalArgumentException("unknown constant pool tag " + tag + " at " + position);
        };
    }

    /** The length-prefixed modified UTF-8 form of {@code value}, as a constant pool stores it. */
    private static byte[] modifiedUtf8(String value) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            new DataOutputStream(buffer).writeUTF(value);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
