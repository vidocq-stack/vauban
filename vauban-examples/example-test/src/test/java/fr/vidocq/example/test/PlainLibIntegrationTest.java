package fr.vidocq.example.test;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.indexer.scanner.JarScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates loading example-lib from its actual JAR file.
 * Scans the JAR, discovers beans, and verifies injection works.
 */
class PlainLibIntegrationTest {

    private VaubanContainer container;

    @AfterEach
    void tearDown() {
        if (container != null) container.close();
    }

    @Test
    void loadBeansFromJar() throws Exception {
        var jarPath = TestJarHelper.plainLibJar();
        var cl = TestJarHelper.buildClassLoader(jarPath);

        // Discover beans from the JAR (same as vauban-maven-plugin would do)
        var classInfos = JarScanner.scan(jarPath);
        assertFalse(classInfos.isEmpty(), "JAR should contain classes");

        // Build container with discovered beans
        var builder = VaubanContainer.builder().classLoader(cl);
        for (var ci : classInfos) {
            var cls = Class.forName(ci.name().value(), false, cl);
            if (!cls.isInterface() && !cls.isAnnotation()) {
                builder.addBeanClass(cls);
            }
        }
        container = builder.build();

        // Verify beans via their types
        var greetingClass = cl.loadClass("fr.vidocq.example.lib.GreetingService");
        var greeting = container.select(greetingClass);
        assertNotNull(greeting);
        var result = greetingClass.getMethod("greet", String.class).invoke(greeting, "Vauban");
        assertEquals("Bonjour, Vauban !", result);

        var timeClass = cl.loadClass("fr.vidocq.example.lib.TimeService");
        var time = container.select(timeClass);
        var now = (String) timeClass.getMethod("now").invoke(time);
        assertNotNull(now);
        assertTrue(now.matches("\\d{2}:\\d{2}:\\d{2}"));
    }
}
