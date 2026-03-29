package fr.vidocq.vauban.indexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Smoke test - verification de l'infrastructure")
class SmokeTest {

    sealed interface Shape permits Circle, Square {}
    record Circle(double radius) implements Shape {}
    record Square(double side) implements Shape {}

    @Test
    @DisplayName("JUnit 6 fonctionne")
    void junitWorks() {
        assertTrue(true);
    }

    @Test
    @DisplayName("JDK 25 Class-File API est disponible")
    void classFileApiAvailable() throws Exception {
        var clazz = Class.forName("java.lang.classfile.ClassFile");
        assertEquals("java.lang.classfile.ClassFile", clazz.getName());
    }

    @Test
    @DisplayName("les records Java fonctionnent")
    void recordsWork() {
        record Point(int x, int y) {}
        var p = new Point(1, 2);
        assertEquals(1, p.x());
        assertEquals(2, p.y());
    }

    @Test
    @DisplayName("les sealed interfaces fonctionnent avec pattern matching")
    void sealedInterfacesWork() {
        Shape shape = new Circle(5.0);
        var result = switch (shape) {
            case Circle c -> "circle: " + c.radius();
            case Square s -> "square: " + s.side();
        };
        assertEquals("circle: 5.0", result);
    }
}
