package fr.vidocq.vauban.core.types;

import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import fr.vidocq.vauban.indexer.model.TypeInfo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AssignabilityRules - regles d'assignabilite CDI 4.1")
class AssignabilityRulesTest {

    private AssignabilityRules rules;

    static ClassInfo makeClass(String name, String superName, String... interfaces) {
        return new ClassInfo(
                DotName.of(name),
                superName != null ? DotName.of(superName) : null,
                List.of(interfaces).stream().map(DotName::of).toList(),
                0x0001, List.of(), List.of(), List.of(),
                ClassKind.CLASS
        );
    }

    static ClassInfo makeInterface(String name, String... superInterfaces) {
        return new ClassInfo(
                DotName.of(name),
                null,
                List.of(superInterfaces).stream().map(DotName::of).toList(),
                0x0601, List.of(), List.of(), List.of(),
                ClassKind.INTERFACE
        );
    }

    @BeforeEach
    void setUp() {
        var builder = new IndexBuilder();
        builder.add(makeClass("java.lang.Object", null));
        builder.add(makeClass("java.lang.Number", "java.lang.Object"));
        builder.add(makeClass("java.lang.Integer", "java.lang.Number", "java.lang.Comparable"));
        builder.add(makeClass("java.lang.String", "java.lang.Object", "java.lang.Comparable", "java.io.Serializable"));
        builder.add(makeInterface("java.lang.Comparable"));
        builder.add(makeInterface("java.io.Serializable"));
        builder.add(makeInterface("java.util.Collection", "java.lang.Iterable"));
        builder.add(makeInterface("java.util.List", "java.util.Collection"));
        builder.add(makeInterface("java.lang.Iterable"));
        rules = new AssignabilityRules(builder.build());
    }

    @Nested
    @DisplayName("types simples (ClassType)")
    class SimpleTypes {

        @Test
        @DisplayName("un type est assignable a lui-meme")
        void sameType() {
            var string = new ClassType(DotName.of("java.lang.String"));
            assertTrue(rules.isAssignable(string, string));
        }

        @Test
        @DisplayName("un sous-type est assignable a son super-type")
        void subtypeAssignable() {
            var integer = new ClassType(DotName.of("java.lang.Integer"));
            var number = new ClassType(DotName.of("java.lang.Number"));
            assertTrue(rules.isAssignable(integer, number));
        }

        @Test
        @DisplayName("un super-type n'est PAS assignable a son sous-type")
        void supertypeNotAssignable() {
            var number = new ClassType(DotName.of("java.lang.Number"));
            var integer = new ClassType(DotName.of("java.lang.Integer"));
            assertFalse(rules.isAssignable(number, integer));
        }

        @Test
        @DisplayName("une classe est assignable a une interface implementee")
        void classToInterface() {
            var integer = new ClassType(DotName.of("java.lang.Integer"));
            var comparable = new ClassType(DotName.of("java.lang.Comparable"));
            assertTrue(rules.isAssignable(integer, comparable));
        }

        @Test
        @DisplayName("tout est assignable a Object")
        void assignableToObject() {
            var string = new ClassType(DotName.of("java.lang.String"));
            var object = new ClassType(DotName.of("java.lang.Object"));
            assertTrue(rules.isAssignable(string, object));
        }

        @Test
        @DisplayName("sous-interface assignable a super-interface")
        void subInterfaceToSuperInterface() {
            var list = new ClassType(DotName.of("java.util.List"));
            var collection = new ClassType(DotName.of("java.util.Collection"));
            var iterable = new ClassType(DotName.of("java.lang.Iterable"));
            assertTrue(rules.isAssignable(list, collection));
            assertTrue(rules.isAssignable(list, iterable));
        }
    }

    @Nested
    @DisplayName("types parametres (ParameterizedType)")
    class ParameterizedTypes {

        @Test
        @DisplayName("List<String> est assignable a List<String>")
        void sameParameterizedType() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            assertTrue(rules.isAssignable(listString, listString));
        }

        @Test
        @DisplayName("List<String> n'est PAS assignable a List<Integer>")
        void differentTypeArgs() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listInteger = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Integer"))));
            assertFalse(rules.isAssignable(listString, listInteger));
        }

        @Test
        @DisplayName("List<String> n'est PAS assignable a List<Object> (invariance)")
        void genericInvariance() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listObject = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Object"))));
            assertFalse(rules.isAssignable(listString, listObject));
        }
    }

    @Nested
    @DisplayName("wildcards")
    class Wildcards {

        @Test
        @DisplayName("List<String> est assignable a List<? extends Object>")
        void extendsWildcard() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listExtendsObject = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(
                            new ClassType(DotName.of("java.lang.Object")), null)));
            assertTrue(rules.isAssignable(listString, listExtendsObject));
        }

        @Test
        @DisplayName("List<Integer> est assignable a List<? extends Number>")
        void extendsWildcardNumber() {
            var listInteger = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Integer"))));
            var listExtendsNumber = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(
                            new ClassType(DotName.of("java.lang.Number")), null)));
            assertTrue(rules.isAssignable(listInteger, listExtendsNumber));
        }

        @Test
        @DisplayName("List<Number> est assignable a List<? super Integer>")
        void superWildcard() {
            var listNumber = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.Number"))));
            var listSuperInteger = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(
                            null, new ClassType(DotName.of("java.lang.Integer")))));
            assertTrue(rules.isAssignable(listNumber, listSuperInteger));
        }

        @Test
        @DisplayName("List<String> est assignable a List<?>")
        void unboundedWildcard() {
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            var listWildcard = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new WildcardType(null, null)));
            assertTrue(rules.isAssignable(listString, listWildcard));
        }
    }

    @Nested
    @DisplayName("raw types et tableaux")
    class RawAndArrayTypes {

        @Test
        @DisplayName("raw List est assignable a List<String> (CDI autorise)")
        void rawToParameterized() {
            var rawList = new ClassType(DotName.of("java.util.List"));
            var listString = new ParameterizedType(
                    DotName.of("java.util.List"),
                    List.of(new ClassType(DotName.of("java.lang.String"))));
            assertTrue(rules.isAssignable(rawList, listString));
        }

        @Test
        @DisplayName("int[] est assignable a int[]")
        void sameArrayType() {
            var intArr = new ArrayType(new PrimitiveType(PrimitiveType.Kind.INT), 1);
            assertTrue(rules.isAssignable(intArr, intArr));
        }

        @Test
        @DisplayName("int[] n'est PAS assignable a long[]")
        void differentArrayComponentType() {
            var intArr = new ArrayType(new PrimitiveType(PrimitiveType.Kind.INT), 1);
            var longArr = new ArrayType(new PrimitiveType(PrimitiveType.Kind.LONG), 1);
            assertFalse(rules.isAssignable(intArr, longArr));
        }
    }

    @Nested
    @DisplayName("hierarchie de sous-types (isSubtypeOf)")
    class SubtypeHierarchy {

        @Test
        @DisplayName("Integer -> Number -> Object")
        void integerHierarchy() {
            assertTrue(rules.isSubtypeOf(DotName.of("java.lang.Integer"), DotName.of("java.lang.Number")));
            assertTrue(rules.isSubtypeOf(DotName.of("java.lang.Integer"), DotName.of("java.lang.Object")));
            assertTrue(rules.isSubtypeOf(DotName.of("java.lang.Integer"), DotName.of("java.lang.Comparable")));
        }

        @Test
        @DisplayName("List -> Collection -> Iterable")
        void listHierarchy() {
            assertTrue(rules.isSubtypeOf(DotName.of("java.util.List"), DotName.of("java.util.Collection")));
            assertTrue(rules.isSubtypeOf(DotName.of("java.util.List"), DotName.of("java.lang.Iterable")));
        }
    }
}
