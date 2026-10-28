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

import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.interceptor.fixtures.GoldenBean;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an intercepted call does on its way to the chain (vauban#227): the bridge {@code Method} is resolved once per
 * class, the chain once per bean instance and method, and nothing walks a map or a class on every call.
 */
@DisplayName("Intercepted subclass: nothing resolved again on each call")
class InterceptedHotPathTest {

    private static final ClassModel GOLDEN = ClassFile.of().parse(
            InterceptorSubclassGenerator.generate(GoldenBean.class).bytecode());

    private static List<MethodModel> overrides() {
        return GOLDEN.methods().stream()
                .filter(m -> !m.methodName().stringValue().startsWith("$$"))
                .filter(m -> !m.methodName().stringValue().startsWith("<"))
                .filter(m -> !m.flags().has(AccessFlag.SYNTHETIC) && !m.flags().has(AccessFlag.BRIDGE))
                .toList();
    }

    private static List<String> invoked(MethodModel method) {
        return method.code().orElseThrow().elementStream()
                .filter(InvokeInstruction.class::isInstance)
                .map(e -> ((InvokeInstruction) e).name().stringValue())
                .toList();
    }

    @Test
    @DisplayName("each override reads its bridge Method from a static final field and hands the call to intercept")
    void overridesResolveNothingPerCall() {
        assertFalse(overrides().isEmpty());
        for (var override : overrides()) {
            var calls = invoked(override);
            var name = override.methodName().stringValue();
            assertFalse(calls.contains("getDeclaredMethod"), name + " looks its bridge up on every call: " + calls);
            assertFalse(calls.contains("getClass"), name + ": " + calls);
            assertFalse(calls.contains("resolveChainForMethod"), name + ": " + calls);
            assertFalse(calls.contains("shareInstances"), name + ": " + calls);
            assertFalse(calls.contains("chainFor"), name + ": " + calls);
            assertFalse(calls.contains("<init>"), name + " builds its own invocation context: " + calls);
            assertTrue(calls.contains("intercept"), name + ": " + calls);
        }
    }

    @Test
    @DisplayName("one private static final Method per intercepted method, set by the class initializer")
    void bridgeMethodsAreStaticFinal() {
        var methodFields = GOLDEN.fields().stream()
                .filter(f -> f.fieldName().stringValue().startsWith(InterceptedShape.METHOD_FIELD_PREFIX))
                .toList();
        assertEquals(overrides().size(), methodFields.size());
        for (var field : methodFields) {
            assertTrue(field.flags().has(AccessFlag.PRIVATE) && field.flags().has(AccessFlag.STATIC)
                    && field.flags().has(AccessFlag.FINAL), field.fieldName().stringValue());
        }
        assertTrue(GOLDEN.methods().stream().anyMatch(m -> m.methodName().equalsString("<clinit>")));
        assertTrue(GOLDEN.fields().stream().anyMatch(f -> f.fieldName().equalsString(InterceptedShape.FIELD_CHAINS)));
    }

    @Test
    @DisplayName("the $$super$ bridges are private: nothing outside the subclass reaches a method past its interceptors")
    void superBridgesArePrivate() {
        var bridges = GOLDEN.methods().stream()
                .filter(m -> m.methodName().stringValue().startsWith(InterceptedShape.SUPER_BRIDGE_PREFIX))
                .toList();
        assertEquals(overrides().size(), bridges.size());
        for (var bridge : bridges) {
            assertTrue(bridge.flags().has(AccessFlag.PRIVATE), bridge.methodName().stringValue());
        }
    }

    public static class Target {
        public void run() {
            // Only its Method is used: no interceptor applies, the chain is what is checked.
        }
    }

    @Test
    @DisplayName("chainFor resolves a chain once per bean instance and keeps it")
    void chainIsKeptPerInstance() throws NoSuchMethodException {
        var manager = new InterceptorManager(List.of());
        var method = Target.class.getDeclaredMethod("run");
        var target = new Target();
        List<?>[] chains = new List<?>[1];
        var ctx = new CreationalContextImpl<>();

        var first = manager.chainFor(chains, 0, Set.of(), method, target, ctx);

        assertNotNull(chains[0]);
        assertSame(first, chains[0]);
        assertSame(first, manager.chainFor(chains, 0, Set.of(), method, target, ctx));
    }

    @Test
    @DisplayName("without a creational context, chainFor keeps nothing: the chain's interceptor instances are not settled")
    void noContextNoCache() throws NoSuchMethodException {
        var manager = new InterceptorManager(List.of());
        List<?>[] chains = new List<?>[1];

        manager.chainFor(chains, 0, Set.of(), Target.class.getDeclaredMethod("run"), new Target(), null);

        assertNull(chains[0]);
    }

    /** An {@code @AroundInvoke} body: records what it saw and wraps the result. */
    public static class Recorder {
        Object[] seen;
        Object data;

        public Object around(InvocationContext ctx) throws Exception {
            seen = ctx.getParameters();
            ctx.getContextData().put("by", "recorder");
            var result = ctx.proceed();
            data = ctx.getContextData().get("by");
            return "wrapped:" + result;
        }
    }

    @Test
    @DisplayName("intercept with no interceptor calls the target directly, on the caller's arguments")
    void interceptWithoutInterceptors() throws Exception {
        var manager = new InterceptorManager(List.of());
        List<?>[] chains = {List.of()};
        Object[] args = {"x"};
        var received = new Object[1][];

        var result = manager.intercept(chains, 0, Set.of(), Target.class.getDeclaredMethod("run"), new Target(),
                new CreationalContextImpl<>(), args, (target, params) -> {
                    received[0] = params;
                    return "done";
                });

        assertEquals("done", result);
        assertSame(args, received[0]);
    }

    @Test
    @DisplayName("intercept runs the chain on the caller's arguments and shares the context data along it")
    void interceptRunsTheChain() throws Exception {
        var manager = new InterceptorManager(List.of());
        var recorder = new Recorder();
        List<?>[] chains = {List.of(new VaubanInvocationContext.InterceptorInvocation(recorder,
                Recorder.class.getDeclaredMethod("around", InvocationContext.class)))};

        var result = manager.intercept(chains, 0, Set.of(), Target.class.getDeclaredMethod("run"), new Target(),
                new CreationalContextImpl<>(), new Object[] {"x"}, (target, params) -> params[0]);

        assertEquals("wrapped:x", result);
        assertArrayEquals(new Object[] {"x"}, recorder.seen);
        assertEquals("recorder", recorder.data);
    }
}
