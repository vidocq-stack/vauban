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
package io.vidocq.vauban.core.codegen;

import io.vidocq.vauban.core.codegen.BridgeCalls.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a compiler bridge calls, read from the class bytes, and judged by the names when a class has none: an overload
 * of the bridge's name and arity misleads the names, not the bytes (BUG-20261118-16, BUG-20261118-17).
 */
@DisplayName("BridgeCalls - what a compiler bridge calls")
class BridgeCallsTest {

    private static final String TAG = "tag";
    private static final String PUT = "put";

    public interface Tagged<T> {
        String tag(T value);
    }

    public static class PlainTagBase {
        public String tag(String value) {
            return value;
        }
    }

    /** javac's bridge {@code tag(Object)} calls {@code PlainTagBase.tag(String)} by invokespecial. */
    public static class TaggedBySuperclass extends PlainTagBase implements Tagged<String> {
    }

    public static class TaggedWithOverload extends PlainTagBase implements Tagged<String> {
        public String tag(Integer value) {
            return String.valueOf(value);
        }
    }

    static class HiddenPutBase {
        public String put(String value) {
            return value;
        }
    }

    /** javac's visibility bridge {@code put(String)} calls {@code HiddenPutBase.put(String)} by invokespecial. */
    public static class VisiblePut extends HiddenPutBase {
    }

    public static class OverloadedPut extends HiddenPutBase {
        public String put(Integer value) {
            return String.valueOf(value);
        }
    }

    /** Its bridge {@code Object get()} calls its own {@code String get()}. */
    public static class OwnSupplier implements Supplier<String> {
        @Override
        public String get() {
            return "own";
        }
    }

    @Test
    @DisplayName("the class bytes name the call, whatever the overloads")
    void theBytesNameTheCall() throws Exception {
        var superTarget = PlainTagBase.class.getMethod(TAG, String.class);
        var hiddenTarget = HiddenPutBase.class.getMethod(PUT, String.class);
        assertAll(
                () -> assertEquals(new BridgeCalls.Call(Kind.SUPER_CALL, superTarget),
                        BridgeCalls.of(bridge(TaggedBySuperclass.class, TAG))),
                () -> assertEquals(new BridgeCalls.Call(Kind.SUPER_CALL, superTarget),
                        BridgeCalls.of(bridge(TaggedWithOverload.class, TAG))),
                () -> assertEquals(new BridgeCalls.Call(Kind.VISIBILITY, hiddenTarget),
                        BridgeCalls.of(bridge(VisiblePut.class, PUT))),
                () -> assertEquals(new BridgeCalls.Call(Kind.VISIBILITY, hiddenTarget),
                        BridgeCalls.of(bridge(OverloadedPut.class, PUT))),
                () -> assertEquals(new BridgeCalls.Call(Kind.OWN, null),
                        BridgeCalls.of(bridge(OwnSupplier.class, "get"))));
    }

    @Test
    @DisplayName("without class bytes, the names judge, and an overload misleads them")
    void theNamesJudgeWithoutBytes() throws Exception {
        var hiddenTarget = HiddenPutBase.class.getMethod(PUT, String.class);
        assertAll(
                () -> assertEquals(new BridgeCalls.Call(Kind.SUPER_CALL, null),
                        BridgeCalls.byNames(bridge(TaggedBySuperclass.class, TAG))),
                () -> assertEquals(new BridgeCalls.Call(Kind.VISIBILITY, hiddenTarget),
                        BridgeCalls.byNames(bridge(VisiblePut.class, PUT))),
                () -> assertEquals(new BridgeCalls.Call(Kind.OWN, null),
                        BridgeCalls.byNames(bridge(OwnSupplier.class, "get"))),
                // The overloads read as the method the bridge calls: what the class bytes correct.
                () -> assertEquals(Kind.OWN, BridgeCalls.byNames(bridge(TaggedWithOverload.class, TAG)).kind()),
                () -> assertNull(BridgeCalls.byNames(bridge(OverloadedPut.class, PUT)).target()));
    }

    @Test
    @DisplayName("a descriptor is the JVM's")
    void descriptor() throws Exception {
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                BridgeCalls.descriptorOf(PlainTagBase.class.getMethod(TAG, String.class)));
    }

    /** The one bridge named {@code name} that {@code type} declares. */
    private static Method bridge(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.isBridge() && m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " declares no bridge " + name));
    }
}
