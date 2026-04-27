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
    @DisplayName("generation de proxy")
    class ProxyGeneration {

        @Test
        @DisplayName("genere un proxy valide")
        void shouldGenerateValidProxy() throws IOException {
            var classInfo = scanClass(GreetingService.class);
            var generated = ClientProxyGenerator.generate(classInfo);

            assertNotNull(generated);
            assertTrue(generated.className().endsWith("_ClientProxy"));
            assertTrue(generated.bytecode().length > 0);
        }

        @Test
        @DisplayName("le proxy peut etre charge")
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
        @DisplayName("le proxy delegue les appels de methode au supplier")
        void shouldDelegateMethodCalls() throws Exception {
            var classInfo = scanClass(GreetingService.class);
            var generated = ClientProxyGenerator.generate(classInfo);

            var cl = new ByteArrayClassLoader(getClass().getClassLoader(),
                    Map.of(generated.className(), generated.bytecode()));
            var proxyClass = cl.loadClass(generated.className());

            var realInstance = new GreetingService();
            Supplier<GreetingService> supplier = () -> realInstance;

            var proxy = (GreetingService) proxyClass
                    .getDeclaredConstructor(Supplier.class)
                    .newInstance(supplier);

            assertEquals("Hello, World!", proxy.greet("World"));
            assertEquals(5, proxy.add(2, 3));
        }

        @Test
        @DisplayName("le proxy delegue les methodes void")
        void shouldDelegateVoidMethods() throws Exception {
            var classInfo = scanClass(GreetingService.class);
            var generated = ClientProxyGenerator.generate(classInfo);

            var cl = new ByteArrayClassLoader(getClass().getClassLoader(),
                    Map.of(generated.className(), generated.bytecode()));
            var proxyClass = cl.loadClass(generated.className());

            var realInstance = new GreetingService();
            var proxy = (GreetingService) proxyClass
                    .getDeclaredConstructor(Supplier.class)
                    .newInstance((Supplier<GreetingService>) () -> realInstance);

            assertDoesNotThrow(() -> proxy.doNothing());
        }
    }
}
