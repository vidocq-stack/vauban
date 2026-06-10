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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The naming decisions of the generated {@code $$Intercepted} subclass live on the shared
 * {@link InterceptedShape} IR, NOT in the renderers: both {@code InterceptedEmitter} (bytecode)
 * and {@code InterceptedSourceRenderer} (source) must consume the same single implementation.
 * VAU-INT-001 (overload collision on {@code $$ti$<name>}) had to be fixed twice because each
 * renderer carried its own copy of this logic — these tests pin the now-unique authority.
 */
@DisplayName("InterceptedShape — shared naming authority for both renderers")
class InterceptedShapeTest {

    private static MethodShape method(String name, TypeRef... params) {
        return new MethodShape(name, TypeRef.ofVoid(), List.of(params));
    }

    private static InterceptedShape shape(MethodShape... methods) {
        return new InterceptedShape("com.example.MyService", List.of(), List.of(methods));
    }

    @Test
    @DisplayName("subclassName appends the $$Intercepted suffix to the bean binary name")
    void subclassName() {
        assertEquals("com.example.MyService$$Intercepted", shape().subclassName());
    }

    @Test
    @DisplayName("non-overloaded methods keep the bare $$ti$<name> glue name (byte-for-byte stable)")
    void bareNamesWithoutOverloads() {
        var s = shape(method("work"), method("close"));
        assertEquals(List.of("$$ti$work", "$$ti$close"), s.targetInvokerNames());
    }

    @Test
    @DisplayName("overloaded methods get a $<occurrence> suffix so erased glues do not collide (VAU-INT-001)")
    void overloadsGetOccurrenceSuffix() {
        var s = shape(
                method("work"),
                method("work", TypeRef.ofPrimitive(TypeRef.Primitive.INT)),
                method("other"));
        assertEquals(List.of("$$ti$work$0", "$$ti$work$1", "$$ti$other"), s.targetInvokerNames());
    }

    @Test
    @DisplayName("three overloads number their occurrences in declaration order")
    void tripleOverload() {
        var s = shape(
                method("work"),
                method("work", TypeRef.ofPrimitive(TypeRef.Primitive.LONG)),
                method("work", TypeRef.ofReference("java.lang.String")));
        assertEquals(List.of("$$ti$work$0", "$$ti$work$1", "$$ti$work$2"), s.targetInvokerNames());
    }

    @Test
    @DisplayName("superBridgeName prepends $$super$ — single authority for both renderers")
    void superBridgeName() {
        assertEquals("$$super$work", InterceptedShape.superBridgeName("work"));
    }
}
