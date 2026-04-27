package io.vidocq.vauban.indexer;

import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VaubanIndex")
class VaubanIndexTest {

    private VaubanIndex index;

    private static final DotName DEPRECATED = DotName.of("java.lang.Deprecated");
    private static final DotName MY_INTERFACE = DotName.of("com.example.MyInterface");
    private static final DotName BASE_CLASS = DotName.of("com.example.BaseClass");

    private static ClassInfo classWithAnnotation(String name, DotName annotationName) {
        return new ClassInfo(
                DotName.of(name),
                DotName.of("java.lang.Object"),
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(new AnnotationInfo(annotationName, Map.of())),
                ClassKind.CLASS
        );
    }

    private static ClassInfo classImplementing(String name, DotName interfaceName) {
        return new ClassInfo(
                DotName.of(name),
                DotName.of("java.lang.Object"),
                List.of(interfaceName),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                ClassKind.CLASS
        );
    }

    private static ClassInfo classExtending(String name, DotName superName) {
        return new ClassInfo(
                DotName.of(name),
                superName,
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                ClassKind.CLASS
        );
    }

    @BeforeEach
    void setUp() {
        var builder = new IndexBuilder();
        builder.add(classWithAnnotation("com.example.AnnotatedFoo", DEPRECATED));
        builder.add(classImplementing("com.example.ImplA", MY_INTERFACE));
        builder.add(classImplementing("com.example.ImplB", MY_INTERFACE));
        builder.add(classExtending("com.example.ChildA", BASE_CLASS));
        builder.add(classExtending("com.example.ChildB", BASE_CLASS));
        index = builder.build();
    }

    @Test
    @DisplayName("trouve une classe par nom")
    void shouldFindClassByName() {
        var result = index.getClassByName(DotName.of("com.example.AnnotatedFoo"));
        assertTrue(result.isPresent());
        assertEquals("com.example.AnnotatedFoo", result.get().name().value());
    }

    @Test
    @DisplayName("retourne empty pour une classe inconnue")
    void shouldReturnEmptyForUnknown() {
        assertTrue(index.getClassByName(DotName.of("com.example.Unknown")).isEmpty());
    }

    @Test
    @DisplayName("trouve les classes avec une annotation")
    void shouldFindClassesWithAnnotation() {
        var result = index.getClassesWithAnnotation(DEPRECATED);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("trouve les implementeurs directs")
    void shouldFindDirectImplementors() {
        var result = index.getImplementors(MY_INTERFACE);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("trouve les sous-classes directes")
    void shouldFindDirectSubclasses() {
        var result = index.getSubclasses(BASE_CLASS);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("retourne toutes les classes connues")
    void shouldReturnKnownClasses() {
        assertEquals(5, index.getKnownClasses().size());
    }

    @Test
    @DisplayName("retourne la taille")
    void shouldReturnSize() {
        assertEquals(5, index.size());
    }

    @Test
    @DisplayName("verifie la presence")
    void shouldCheckContains() {
        assertTrue(index.containsClass(DotName.of("com.example.ImplA")));
        assertFalse(index.containsClass(DotName.of("com.example.Unknown")));
    }
}
