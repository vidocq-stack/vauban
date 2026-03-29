package fr.vidocq.vauban.indexer;

import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IndexBuilder")
class IndexBuilderTest {

    private static ClassInfo dummyClass(String name) {
        return new ClassInfo(
                DotName.of(name),
                DotName.of("java.lang.Object"),
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                ClassKind.CLASS
        );
    }

    @Test
    @DisplayName("construit un index vide")
    void shouldBuildEmptyIndex() {
        var index = new IndexBuilder().build();
        assertEquals(0, index.size());
    }

    @Test
    @DisplayName("ajoute une classe")
    void shouldAddClassInfo() {
        var builder = new IndexBuilder();
        builder.add(dummyClass("com.example.Foo"));
        assertEquals(1, builder.size());
    }

    @Test
    @DisplayName("remplace une classe dupliquee")
    void shouldReplaceDuplicate() {
        var builder = new IndexBuilder();
        builder.add(dummyClass("com.example.Foo"));
        builder.add(dummyClass("com.example.Foo"));
        assertEquals(1, builder.size());
    }

    @Test
    @DisplayName("verifie la presence d'une classe")
    void shouldCheckContains() {
        var builder = new IndexBuilder();
        builder.add(dummyClass("com.example.Foo"));
        assertTrue(builder.contains(DotName.of("com.example.Foo")));
        assertFalse(builder.contains(DotName.of("com.example.Bar")));
    }

    @Test
    @DisplayName("ajoute plusieurs classes")
    void shouldAddAll() {
        var builder = new IndexBuilder();
        builder.addAll(List.of(dummyClass("com.example.A"), dummyClass("com.example.B")));
        assertEquals(2, builder.size());
    }
}
