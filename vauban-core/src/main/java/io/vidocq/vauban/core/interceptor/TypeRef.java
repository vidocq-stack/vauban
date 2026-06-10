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
package io.vidocq.vauban.core.interceptor;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;

/**
 * A type reference that carries everything the bytecode emitter needs without
 * holding a {@link Class} object: a primitive kind (void/boolean/byte/char/short/int/long/float/double)
 * or a reference binary name (as returned by {@link Class#getName()}), plus array dimensions.
 *
 * <p>This is the neutral shape used by {@link InterceptedEmitter} so that both the
 * {@code Class<?>}-based front-end (in {@link InterceptorSubclassGenerator}) and the
 * {@code Elements}-based front-end (in {@code vauban-processor}) can produce identical bytecode.
 */
public final class TypeRef {

    /** Primitive kinds, including {@code void}. */
    public enum Primitive {
        VOID, BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE
    }

    // Exactly one of these is non-null
    private final Primitive primitive;
    private final String binaryName; // e.g. "java.lang.String", "io.example.Foo$Bar"

    private final int dims; // 0 = not array, 1+ = array

    // ---- factories ----

    public static TypeRef ofVoid() {
        return new TypeRef(Primitive.VOID, null, 0);
    }

    public static TypeRef ofPrimitive(Primitive kind) {
        if (kind == Primitive.VOID) throw new IllegalArgumentException("Use ofVoid()");
        return new TypeRef(kind, null, 0);
    }

    /**
     * A primitive (non-void) type with array dimensions, e.g. {@code int[]} (dims=1),
     * {@code long[][]} (dims=2).
     */
    public static TypeRef ofPrimitive(Primitive kind, int dims) {
        if (kind == Primitive.VOID) throw new IllegalArgumentException("Use ofVoid()");
        return new TypeRef(kind, null, dims);
    }

    /**
     * A reference type given its binary name (e.g. {@code "java.lang.String"},
     * {@code "io.example.Outer$Inner"}) with {@code dims} array dimensions.
     */
    public static TypeRef ofReference(String binaryName, int dims) {
        return new TypeRef(null, binaryName, dims);
    }

    /** Convenience: reference with no array dimensions. */
    public static TypeRef ofReference(String binaryName) {
        return ofReference(binaryName, 0);
    }

    private TypeRef(Primitive primitive, String binaryName, int dims) {
        this.primitive = primitive;
        this.binaryName = binaryName;
        this.dims = dims;
    }

    // ---- queries ----

    public boolean isVoid() {
        return primitive == Primitive.VOID;
    }

    public boolean isPrimitive() {
        // An array of a primitive (e.g. int[]) is a *reference* type, not a primitive: it must be
        // loaded/stored as a reference (aload/areturn), never boxed, and occupies one slot.
        return primitive != null && primitive != Primitive.VOID && dims == 0;
    }

    public boolean isPrimitiveOrVoid() {
        return primitive != null;
    }

    /**
     * Returns {@code true} for {@code long} and {@code double} — the two category-2
     * computational types that occupy two local-variable slots (JVMS §2.11.1).
     */
    public boolean isCategory2() {
        // Only scalar long/double occupy two slots; long[]/double[] are references (one slot).
        return (primitive == Primitive.LONG || primitive == Primitive.DOUBLE) && dims == 0;
    }

    public String binaryName() {
        return binaryName;
    }

    public int dims() {
        return dims;
    }

    public Primitive primitiveKind() {
        return primitive;
    }

    // ---- ClassDesc ----

    /**
     * Returns the {@link ClassDesc} for this type, applying array dimensions if any.
     * Primitives map directly to {@link ConstantDescs}; reference types are split on the
     * last {@code '.'} to produce {@link ClassDesc#of(String, String)}.
     */
    public ClassDesc classDesc() {
        ClassDesc base = baseClassDesc();
        ClassDesc cd = base;
        for (int i = 0; i < dims; i++) {
            cd = cd.arrayType();
        }
        return cd;
    }

    private ClassDesc baseClassDesc() {
        if (primitive != null) {
            return switch (primitive) {
                case VOID -> ConstantDescs.CD_void;
                case BOOLEAN -> ConstantDescs.CD_boolean;
                case BYTE -> ConstantDescs.CD_byte;
                case CHAR -> ConstantDescs.CD_char;
                case SHORT -> ConstantDescs.CD_short;
                case INT -> ConstantDescs.CD_int;
                case LONG -> ConstantDescs.CD_long;
                case FLOAT -> ConstantDescs.CD_float;
                case DOUBLE -> ConstantDescs.CD_double;
            };
        }
        // reference
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot >= 0) {
            return ClassDesc.of(binaryName.substring(0, lastDot), binaryName.substring(lastDot + 1));
        }
        return ClassDesc.of(binaryName);
    }

    /**
     * For a primitive (non-void) type, returns the binary name of the corresponding wrapper
     * class (e.g. {@code int} → {@code "java.lang.Integer"}). Single authority for the
     * primitive→wrapper table — both code renderers (bytecode and source) derive from it.
     *
     * @throws IllegalStateException if this is not a non-void primitive
     */
    public String wrapperBinaryName() {
        if (!isPrimitive()) throw new IllegalStateException("Not a non-void primitive: " + this);
        return switch (primitive) {
            case BOOLEAN -> "java.lang.Boolean";
            case BYTE -> "java.lang.Byte";
            case CHAR -> "java.lang.Character";
            case SHORT -> "java.lang.Short";
            case INT -> "java.lang.Integer";
            case LONG -> "java.lang.Long";
            case FLOAT -> "java.lang.Float";
            case DOUBLE -> "java.lang.Double";
            default -> throw new IllegalStateException("unexpected: " + primitive);
        };
    }

    /**
     * For a primitive (non-void) type, returns the name of the wrapper accessor used to unbox
     * it (e.g. {@code int} → {@code "intValue"}). Single authority for the unbox table.
     *
     * @throws IllegalStateException if this is not a non-void primitive
     */
    public String unboxAccessorName() {
        if (!isPrimitive()) throw new IllegalStateException("Not a non-void primitive: " + this);
        return primitive.name().toLowerCase(java.util.Locale.ROOT) + "Value";
    }

    /**
     * Java source syntax for this type: {@code int}, {@code int[]}, {@code java.lang.String},
     * {@code a.b.Outer.Inner} (binary {@code '$'} becomes source {@code '.'}).
     */
    public String sourceName() {
        String base = primitive != null
                ? primitive.name().toLowerCase(java.util.Locale.ROOT)
                : binaryName.replace('$', '.');
        return base + "[]".repeat(dims);
    }

    /**
     * For a primitive (non-void) type, returns the {@link ClassDesc} of the corresponding
     * wrapper class (e.g. {@code int} → {@code Integer}).
     *
     * @throws IllegalStateException if this is not a non-void primitive
     */
    public ClassDesc wrapperClassDesc() {
        String wrapperName = wrapperBinaryName();
        int dot = wrapperName.lastIndexOf('.');
        return ClassDesc.of(wrapperName.substring(0, dot), wrapperName.substring(dot + 1));
    }

    // ---- factory from Class<?> ----

    /**
     * Builds a {@link TypeRef} from a {@link Class}, including arrays and primitives.
     */
    public static TypeRef fromClass(Class<?> type) {
        int dims = 0;
        Class<?> t = type;
        while (t.isArray()) {
            dims++;
            t = t.getComponentType();
        }
        if (t.isPrimitive()) {
            if (dims > 0) {
                // primitive array — represented as a reference array in the descriptor
                // e.g. int[] → ClassDesc "[I", int[][] → "[[I"
                // We encode these as a reference to the component primitive descriptor
                // by keeping primitive + dims.
            }
            Primitive kind = switch (t.getName()) {
                case "void" -> Primitive.VOID;
                case "boolean" -> Primitive.BOOLEAN;
                case "byte" -> Primitive.BYTE;
                case "char" -> Primitive.CHAR;
                case "short" -> Primitive.SHORT;
                case "int" -> Primitive.INT;
                case "long" -> Primitive.LONG;
                case "float" -> Primitive.FLOAT;
                case "double" -> Primitive.DOUBLE;
                default -> throw new IllegalArgumentException("unknown primitive: " + t.getName());
            };
            // dims on a primitive means int[], long[][], etc. — we record that.
            return new TypeRef(kind, null, dims);
        }
        return new TypeRef(null, t.getName(), dims);
    }

    // ---- Object ----

    @Override
    public boolean equals(Object o) {
        return o instanceof TypeRef tr
                && tr.primitive == primitive
                && java.util.Objects.equals(tr.binaryName, binaryName)
                && tr.dims == dims;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(primitive, binaryName, dims);
    }

    @Override
    public String toString() {
        String base = primitive != null ? primitive.name().toLowerCase() : binaryName;
        return base + "[]".repeat(dims);
    }
}
