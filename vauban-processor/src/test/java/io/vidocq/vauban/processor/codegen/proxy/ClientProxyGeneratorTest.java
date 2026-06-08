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
package io.vidocq.vauban.processor.codegen.proxy;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import io.vidocq.vauban.processor.codegen.GeneratedClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClientProxyGenerator")
class ClientProxyGeneratorTest {

    // Test bean with public methods
    public static class GreetingService {
        public GreetingService() {}

        public String greet(String name) { return "Hello, " + name + "!"; }

        public int add(int a, int b) { return a + b; }

        public void doNothing() {}
    }

    static ClassInfo scanClass(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
            return ClassFileScanner.scan(is.readAllBytes());
        }
    }

    static class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        ByteArrayClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var bytes = classes.get(name);
            if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
            throw new ClassNotFoundException(name);
        }
    }

    @Nested
    @DisplayName("proxy generation")
    class ProxyGeneration {

        @Test
        @DisplayName("generates a valid proxy")
        void shouldGenerateValidProxy() throws IOException {
            var classInfo = scanClass(GreetingService.class);
            var generated = ClientProxyGenerator.generate(classInfo);

            assertNotNull(generated);
            assertTrue(generated.className().endsWith("_ClientProxy"));
            assertTrue(generated.bytecode().length > 0);
        }

        @Test
        @DisplayName("the proxy can be loaded")
        void shouldLoadProxy() throws Exception {
            var classInfo = scanClass(GreetingService.class);
            var generated = ClientProxyGenerator.generate(classInfo);

            var cl = new ByteArrayClassLoader(getClass().getClassLoader(),
                    Map.of(generated.className(), generated.bytecode()));
            var proxyClass = cl.loadClass(generated.className());

            assertNotNull(proxyClass);
            assertTrue(GreetingService.class.isAssignableFrom(proxyClass));
        }

        @Test
        @DisplayName("the proxy delegates method calls to the supplier")
        void shouldDelegateMethodCalls() throws Exception {
            var classInfo = scanClass(GreetingService.class);
            var generated = ClientProxyGenerator.generate(classInfo);

            var cl = new ByteArrayClassLoader(getClass().getClassLoader(),
                    Map.of(generated.className(), generated.bytecode()));
            var proxyClass = cl.loadClass(generated.className());

            var realInstance = new GreetingService();
            Supplier<GreetingService> supplier = () -> realInstance;

            var proxy = (GreetingService) proxyClass.getDeclaredConstructor().newInstance();
            proxyClass.getMethod("$$setDelegate", Supplier.class).invoke(proxy, supplier);

            assertEquals("Hello, World!", proxy.greet("World"));
            assertEquals(5, proxy.add(2, 3));
        }

        @Test
        @DisplayName("the proxy delegates void methods")
        void shouldDelegateVoidMethods() throws Exception {
            var classInfo = scanClass(GreetingService.class);
            var generated = ClientProxyGenerator.generate(classInfo);

            var cl = new ByteArrayClassLoader(getClass().getClassLoader(),
                    Map.of(generated.className(), generated.bytecode()));
            var proxyClass = cl.loadClass(generated.className());

            var realInstance = new GreetingService();
            var proxy = (GreetingService) proxyClass.getDeclaredConstructor().newInstance();
            proxyClass.getMethod("$$setDelegate", Supplier.class)
                    .invoke(proxy, (Supplier<GreetingService>) () -> realInstance);

            assertDoesNotThrow(() -> proxy.doNothing());
        }
    }
}
