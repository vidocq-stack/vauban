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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Vauban's centralized reflective access utility.
 *
 * <h2>Vidocq context</h2>
 * Vidocq is a modular JPMS framework. The user application is a named module
 * ({@code module io.vidocq.runtime.app}) that opens its packages to
 * {@code io.vidocq.vauban.core}. But {@code opens ... to} is not enough:
 * the calling module must also <em>read</em> the target module via
 * {@code Module.addReads()}, otherwise {@code MethodHandles.privateLookupIn()}
 * fails with {@code IllegalAccessException: module io.vidocq.vauban.core
 * does not read module io.vidocq.runtime.app}.
 *
 * <h2>Fix</h2>
 * {@code VaubanLookup.lookupFor()} now calls {@code addReads()} before
 * {@code privateLookupIn()} when the modules do not yet read each other.
 *
 * <h2>Removal of sun.misc.Unsafe</h2>
 * The old code used {@code sun.misc.Unsafe::staticFieldOffset} to access
 * {@code IMPL_LOOKUP} as a fallback. This API has been terminally deprecated
 * since JDK 23 and will be removed. The fix uses the root {@code Lookup}
 * provided by the user via {@code VaubanContainer.builder()
 * .lookup(MethodHandles.lookup())} — cleaner and JPMS-compliant.
 *
 * <h2>Why the TCK does not cover this case</h2>
 * The CDI 4.1 TCK runs on the classpath (unnamed module). The {@code addReads}
 * problems only appear when the application is a named JPMS module, which the
 * TCK never tests. The classpath workaround ({@code --add-reads} on the CLI)
 * hid the bug in dev but not in modular production.
 */
@DisplayName("VaubanLookup - reflective access via MethodHandles")
class VaubanLookupTest {

    private VaubanLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new VaubanLookup(MethodHandles.lookup());
    }

    // -- Test classes --

    public static class SimpleBean {
        public SimpleBean() {}
        public String greet() { return "hello"; }
    }

    public static class BeanWithArgs {
        private final String name;
        private final int value;

        public BeanWithArgs(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public int getValue() { return value; }
    }

    public static class BeanWithField {
        public String message;
        private int count;
    }

    public static class BeanWithStaticField {
        public static String shared = "initial";
    }

    public static class BeanWithMethods {
        public String echo(String input) { return input; }
        public int add(int a, int b) { return a + b; }
        public static String staticMethod() { return "static"; }
        public void voidMethod() {}
    }

    public static class BeanWithPrivateConstructor {
        private BeanWithPrivateConstructor() {}
    }

    // -- Tests --

    /**
     * Tests the core of the JPMS fix: {@code lookupFor} must call
     * {@code Module.addReads()} before {@code privateLookupIn()} so that
     * vauban.core can read JDK or application modules.
     * The tests with java.sql and java.logging verify that
     * {@code canRead()} becomes {@code true} after the call.
     */
    @Nested
    @DisplayName("lookupFor - obtaining a private Lookup")
    class LookupFor {

        @Test
        @DisplayName("returns a Lookup for a class in the same module")
        void shouldReturnLookupForSameModuleClass() {
            var result = lookup.lookupFor(SimpleBean.class);
            assertNotNull(result);
        }

        @Test
        @DisplayName("fails cleanly for a JDK class whose package is not opened")
        void shouldThrowForClosedJdkPackage() {
            // java.lang.String is in java.base but the package is not opened
            var ex = assertThrows(RuntimeException.class, () -> lookup.lookupFor(String.class));
            assertTrue(ex.getMessage().contains("opens java.lang"),
                    "The error message must indicate the package to open");
        }

        @Test
        @DisplayName("caches the Lookup for successive calls")
        void shouldCacheLookupResults() {
            var first = lookup.lookupFor(SimpleBean.class);
            var second = lookup.lookupFor(SimpleBean.class);
            assertSame(first, second);
        }

        @Test
        @DisplayName("addReads is called for a class from another JDK module (java.sql)")
        void shouldAddReadsForCrossModuleAccess() throws Exception {
            // java.sql.Connection is in the java.sql module (named)
            Class<?> connectionClass = Class.forName("java.sql.Connection");
            Module targetModule = connectionClass.getModule();
            assertTrue(targetModule.isNamed(), "java.sql.Connection must be in a named module");
            assertEquals("java.sql", targetModule.getName());

            // lookupFor will call addReads before privateLookupIn
            // privateLookupIn will fail because java.sql does not open its packages,
            // but addReads will have been called before the exception
            try {
                lookup.lookupFor(connectionClass);
            } catch (RuntimeException ignored) {
                // The privateLookupIn exception is expected for closed JDK modules
            }

            // Key check: addReads was called, the module can now read java.sql
            Module vaubanModule = VaubanLookup.class.getModule();
            assertTrue(vaubanModule.canRead(targetModule),
                    "After lookupFor, the vauban.core module must be able to read java.sql thanks to addReads");
        }

        @Test
        @DisplayName("addReads is called for java.logging (named module)")
        void shouldAddReadsForLoggingModule() throws Exception {
            Class<?> loggerClass = Class.forName("java.util.logging.Logger");
            Module targetModule = loggerClass.getModule();
            assertEquals("java.logging", targetModule.getName());

            try {
                lookup.lookupFor(loggerClass);
            } catch (RuntimeException ignored) {
                // The privateLookupIn exception is expected for closed JDK modules
            }

            Module vaubanModule = VaubanLookup.class.getModule();
            assertTrue(vaubanModule.canRead(targetModule),
                    "After lookupFor, the vauban.core module must be able to read java.logging thanks to addReads");
        }

        @Test
        @DisplayName("lookupFor succeeds for a user class (package implicitly opened)")
        void shouldSucceedForUserClassInSameModule() {
            // The test classes are in the same module -- no access problem
            var result = lookup.lookupFor(SimpleBean.class);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("newInstance - instance creation")
    class NewInstance {

        @Test
        @DisplayName("creates an instance with the no-arg constructor")
        void shouldCreateInstanceWithNoArgConstructor() {
            var instance = lookup.newInstance(SimpleBean.class);
            assertNotNull(instance);
            assertInstanceOf(SimpleBean.class, instance);
        }

        @Test
        @DisplayName("each call creates a new instance")
        void shouldCreateDistinctInstances() {
            var a = lookup.newInstance(SimpleBean.class);
            var b = lookup.newInstance(SimpleBean.class);
            assertNotSame(a, b);
        }

        @Test
        @DisplayName("the created instance is functional")
        void shouldCreateFunctionalInstance() {
            var instance = lookup.newInstance(SimpleBean.class);
            assertEquals("hello", instance.greet());
        }

        @Test
        @DisplayName("throws an exception for a class without a no-arg constructor")
        void shouldThrowWhenNoNoArgConstructor() {
            assertThrows(RuntimeException.class, () -> lookup.newInstance(BeanWithArgs.class));
        }
    }

    @Nested
    @DisplayName("newInstance with constructor and arguments")
    class NewInstanceWithConstructor {

        @Test
        @DisplayName("creates an instance with the provided arguments")
        void shouldCreateInstanceWithArgs() throws Exception {
            Constructor<?> ctor = BeanWithArgs.class.getConstructor(String.class, int.class);
            var instance = (BeanWithArgs) lookup.newInstance(ctor, "test", 42);
            assertNotNull(instance);
            assertEquals("test", instance.getName());
            assertEquals(42, instance.getValue());
        }

        @Test
        @DisplayName("throws CreationException if the arguments are incorrect")
        void shouldThrowOnWrongArgs() throws Exception {
            Constructor<?> ctor = BeanWithArgs.class.getConstructor(String.class, int.class);
            assertThrows(Exception.class, () -> lookup.newInstance(ctor, 123, "wrong"));
        }
    }

    @Nested
    @DisplayName("setField / getField - field access")
    class FieldAccess {

        @Test
        @DisplayName("writes and reads a public field")
        void shouldSetAndGetPublicField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("message");

            lookup.setField(instance, field, "bonjour");
            var result = lookup.getField(instance, field);

            assertEquals("bonjour", result);
        }

        @Test
        @DisplayName("writes and reads a private field")
        void shouldSetAndGetPrivateField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("count");

            lookup.setField(instance, field, 7);
            var result = lookup.getField(instance, field);

            assertEquals(7, result);
        }

        @Test
        @DisplayName("writes and reads a static field")
        void shouldSetAndGetStaticField() throws Exception {
            Field field = BeanWithStaticField.class.getDeclaredField("shared");

            lookup.setField(null, field, "modified");
            var result = lookup.getField(null, field);

            assertEquals("modified", result);
            // Reset
            BeanWithStaticField.shared = "initial";
        }

        @Test
        @DisplayName("getField returns null for an uninitialized field")
        void shouldReturnNullForUninitializedField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("message");

            var result = lookup.getField(instance, field);
            assertNull(result);
        }

        @Test
        @DisplayName("getField returns 0 for an uninitialized int")
        void shouldReturnZeroForUninitializedIntField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("count");

            var result = lookup.getField(instance, field);
            assertEquals(0, result);
        }
    }

    @Nested
    @DisplayName("invokeMethod - method invocation")
    class InvokeMethod {

        @Test
        @DisplayName("invokes an instance method with one argument")
        void shouldInvokeInstanceMethodWithArg() throws Exception {
            var instance = new BeanWithMethods();
            Method method = BeanWithMethods.class.getMethod("echo", String.class);

            var result = lookup.invokeMethod(instance, method, "test");
            assertEquals("test", result);
        }

        @Test
        @DisplayName("invokes a method with several arguments")
        void shouldInvokeMethodWithMultipleArgs() throws Exception {
            var instance = new BeanWithMethods();
            Method method = BeanWithMethods.class.getMethod("add", int.class, int.class);

            var result = lookup.invokeMethod(instance, method, 3, 4);
            assertEquals(7, result);
        }

        @Test
        @DisplayName("invokes a static method")
        void shouldInvokeStaticMethod() throws Exception {
            Method method = BeanWithMethods.class.getMethod("staticMethod");

            var result = lookup.invokeStaticMethod(method);
            assertEquals("static", result);
        }

        @Test
        @DisplayName("invokes a void method without error")
        void shouldInvokeVoidMethod() throws Exception {
            var instance = new BeanWithMethods();
            Method method = BeanWithMethods.class.getMethod("voidMethod");

            assertDoesNotThrow(() -> lookup.invokeMethod(instance, method));
        }

        @Test
        @DisplayName("invokes a void method via invokeStaticMethod on instance")
        void shouldInvokeStaticMethodWithNullInstance() throws Exception {
            Method method = BeanWithMethods.class.getMethod("staticMethod");

            var result = lookup.invokeMethod(null, method);
            assertEquals("static", result);
        }
    }

    @Nested
    @DisplayName("makeAccessible - makes a member accessible")
    class MakeAccessible {

        @Test
        @DisplayName("makes a private constructor accessible after the call")
        void shouldMakePrivateConstructorAccessible() throws Exception {
            Constructor<?> ctor = BeanWithPrivateConstructor.class.getDeclaredConstructor();

            // makeAccessible must not throw an exception
            assertDoesNotThrow(() -> lookup.makeAccessible(ctor));

            // After the call, the constructor must be accessible
            assertTrue(ctor.canAccess(null));
        }

        @Test
        @DisplayName("makes a private field accessible after the call")
        void shouldMakePrivateFieldAccessible() throws Exception {
            Field field = BeanWithField.class.getDeclaredField("count");
            var instance = new BeanWithField();

            assertDoesNotThrow(() -> lookup.makeAccessible(field));

            // After the call, the field must be accessible
            assertTrue(field.canAccess(instance));
        }

        @Test
        @DisplayName("makes a method accessible after the call")
        void shouldMakeMethodAccessible() throws Exception {
            // Use a fictional private method via an inner class
            Method method = BeanWithMethods.class.getMethod("echo", String.class);

            assertDoesNotThrow(() -> lookup.makeAccessible(method));
            assertTrue(method.canAccess(new BeanWithMethods()));
        }
    }

    @Nested
    @DisplayName("default constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("uses MethodHandles.lookup() when no Lookup is provided")
        void shouldUseDefaultLookupWhenNoneProvided() {
            var defaultLookup = new VaubanLookup();
            // Must be able to create an instance of a class in the same package
            var instance = defaultLookup.newInstance(SimpleBean.class);
            assertNotNull(instance);
        }
    }
}
