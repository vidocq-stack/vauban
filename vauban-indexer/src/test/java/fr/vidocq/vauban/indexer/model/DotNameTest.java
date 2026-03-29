package fr.vidocq.vauban.indexer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DotName")
class DotNameTest {

    @Test
    @DisplayName("cree depuis un nom qualifie")
    void shouldCreateFromFqcn() {
        var name = DotName.of("java.lang.String");
        assertEquals("java.lang.String", name.value());
    }

    @Test
    @DisplayName("convertit depuis la forme interne")
    void shouldConvertFromInternal() {
        var name = DotName.fromInternal("java/lang/String");
        assertEquals("java.lang.String", name.value());
    }

    @Test
    @DisplayName("convertit vers la forme interne")
    void shouldConvertToInternal() {
        assertEquals("java/lang/String", DotName.of("java.lang.String").toInternal());
    }

    @Test
    @DisplayName("convertit depuis un descripteur")
    void shouldConvertFromDescriptor() {
        var name = DotName.fromDescriptor("Ljava/lang/String;");
        assertEquals("java.lang.String", name.value());
    }

    @Test
    @DisplayName("convertit vers un descripteur")
    void shouldConvertToDescriptor() {
        assertEquals("Ljava/lang/String;", DotName.of("java.lang.String").toDescriptor());
    }

    @Test
    @DisplayName("retourne le nom simple")
    void shouldReturnSimpleName() {
        assertEquals("String", DotName.of("java.lang.String").simpleName());
    }

    @Test
    @DisplayName("retourne le nom du package")
    void shouldReturnPackageName() {
        assertEquals("java.lang", DotName.of("java.lang.String").packageName());
    }

    @Test
    @DisplayName("retourne un package vide pour une classe sans package")
    void shouldReturnEmptyPackageForDefaultPackage() {
        assertEquals("", DotName.of("MyClass").packageName());
        assertEquals("MyClass", DotName.of("MyClass").simpleName());
    }

    @Test
    @DisplayName("est comparable")
    void shouldBeComparable() {
        var a = DotName.of("a.B");
        var b = DotName.of("b.C");
        assertTrue(a.compareTo(b) < 0);
        assertEquals(0, a.compareTo(DotName.of("a.B")));
    }

    @Test
    @DisplayName("rejette null")
    void shouldRejectNull() {
        assertThrows(NullPointerException.class, () -> new DotName(null));
    }

    @Test
    @DisplayName("rejette une chaine vide")
    void shouldRejectEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new DotName(""));
    }

    @Test
    @DisplayName("toString retourne la valeur")
    void toStringShouldReturnValue() {
        assertEquals("java.lang.String", DotName.of("java.lang.String").toString());
    }
}
