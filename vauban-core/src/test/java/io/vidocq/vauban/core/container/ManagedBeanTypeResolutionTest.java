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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bean types a {@link ManagedBean} hands out: a synthetic bean resolves its descriptor's types to Java types,
 * dropping what its class loader cannot load; a producer reads its produced type from the producer member, declared or
 * inherited, and falls back on the descriptor when that member cannot be found.
 */
@DisplayName("ManagedBean: bean types from the descriptor and from the producer member")
class ManagedBeanTypeResolutionTest {

    private static final String MISSING_CLASS = "io.vidocq.vauban.core.container.DoesNotExist";
    private static final String STRING = "java.lang.String";
    private static final String LIST = "java.util.List";

    public static class Owner {
    }

    /** A producer declaring its products for the subclass to inherit. */
    public static class BaseProducer {

        @Produces
        public List<String> names() {
            return List.of("a");
        }

        @Produces
        public CharSequence text = "inherited";
    }

    public static class SubProducer extends BaseProducer {

        /** Shares the name of the inherited producer method without being one. */
        public String names(int index) {
            return "name" + index;
        }

        @Produces
        public int[] numbers() {
            return new int[0];
        }

        @Produces
        @SuppressWarnings("unchecked")
        public List<String>[] lists() {
            return new List[0];
        }

        @Produces
        @Typed(List.class)
        public ArrayList<String> typedList() {
            return new ArrayList<>();
        }
    }

    private static ManagedBean<Object> bean(BeanDescriptor.BeanKind kind, String id, Set<TypeInfo> types) {
        var descriptor = new BeanDescriptor(new BeanId(id), DotName.of(Owner.class.getName()), kind, types,
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.DEPENDENT, false, 0, List.of(), null);
        return new ManagedBean<>(descriptor, Owner::new, ManagedBeanTypeResolutionTest.class.getClassLoader(), null);
    }

    private static Set<Type> syntheticTypes(TypeInfo... types) {
        return bean(BeanDescriptor.BeanKind.SYNTHETIC, "synthetic-bean", Set.of(types)).getTypes();
    }

    private static TypeInfo cls(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    private static Type onlyTypeBesidesObject(Set<Type> types) {
        var others = types.stream().filter(t -> t != Object.class).toList();
        assertEquals(1, others.size(), "one bean type besides Object: " + types);
        return others.getFirst();
    }

    @Test
    @DisplayName("a synthetic bean drops the class types and parameterized types its loader cannot load")
    void syntheticTypesDropWhatCannotLoad() {
        var types = syntheticTypes(cls(MISSING_CLASS),
                new TypeInfo.ParameterizedType(DotName.of(MISSING_CLASS), List.of(cls(STRING))),
                cls(STRING));

        assertEquals(Set.of(String.class, Object.class), types);
    }

    @Test
    @DisplayName("a synthetic bean keeps a primitive type")
    void syntheticPrimitiveType() {
        var types = syntheticTypes(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.LONG));

        assertTrue(types.contains(long.class), types::toString);
    }

    @Test
    @DisplayName("nested type arguments are rebuilt: parameterized, array, primitive, and Object for anything else")
    void nestedTypeArguments() {
        var parameterized = new TypeInfo.ParameterizedType(DotName.of("java.util.Map"), List.of(
                new TypeInfo.ParameterizedType(DotName.of(LIST), List.of(cls(STRING))),
                new TypeInfo.ArrayType(cls(STRING), 1)));
        var withPrimitiveAndVariable = new TypeInfo.ParameterizedType(DotName.of("java.util.function.BiFunction"),
                List.of(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT),
                        new TypeInfo.TypeVariable("T", List.of()),
                        new TypeInfo.ArrayType(new TypeInfo.TypeVariable("U", List.of()), 1)));

        var mapType = assertInstanceOf(ParameterizedType.class, onlyTypeBesidesObject(syntheticTypes(parameterized)));
        var listArg = assertInstanceOf(ParameterizedType.class, mapType.getActualTypeArguments()[0]);
        assertEquals(List.class, listArg.getRawType());
        assertNull(listArg.getOwnerType());
        assertNull(mapType.getOwnerType());
        assertArrayEquals(new Type[]{String.class}, listArg.getActualTypeArguments());
        assertEquals(String[].class, mapType.getActualTypeArguments()[1]);

        var fnType = assertInstanceOf(ParameterizedType.class, onlyTypeBesidesObject(syntheticTypes(withPrimitiveAndVariable)));
        assertArrayEquals(new Type[]{int.class, Object.class, Object.class}, fnType.getActualTypeArguments());
    }

    @Test
    @DisplayName("a type argument whose class cannot be loaded drops the whole parameterized type")
    void missingTypeArgument() {
        var types = syntheticTypes(new TypeInfo.ParameterizedType(DotName.of(LIST),
                List.of(new TypeInfo.ParameterizedType(DotName.of(MISSING_CLASS), List.of()))));

        assertEquals(Set.of(Object.class), types);
    }

    @ParameterizedTest(name = "a {1}-dimension array of the index class type ''{0}''")
    @CsvSource({
            "boolean, 1, [Z", "byte, 1, [B", "char, 2, [[C", "short, 1, [S", "int, 1, [I",
            "long, 1, [J", "float, 1, [F", "double, 1, [D", "java.lang.String, 1, [Ljava.lang.String;"})
    @DisplayName("an array whose component the index names as a class type resolves to its array class")
    void arrayOfIndexClassType(String component, int dimensions, String expectedName) throws ClassNotFoundException {
        var types = syntheticTypes(new TypeInfo.ArrayType(cls(component), dimensions));

        assertTrue(types.contains(Class.forName(expectedName)), types::toString);
    }

    @ParameterizedTest
    @EnumSource(TypeInfo.PrimitiveType.Kind.class)
    @DisplayName("an array of a primitive component resolves to its primitive array class")
    void arrayOfPrimitive(TypeInfo.PrimitiveType.Kind kind) {
        var types = syntheticTypes(new TypeInfo.ArrayType(new TypeInfo.PrimitiveType(kind), 1));

        var arrayClass = assertInstanceOf(Class.class, onlyTypeBesidesObject(types));
        assertTrue(arrayClass.isArray());
        assertTrue(arrayClass.getComponentType().isPrimitive(), arrayClass::getName);
        assertEquals(kind.name().toLowerCase(java.util.Locale.ROOT), arrayClass.getComponentType().getName());
    }

    @Test
    @DisplayName("an array of a parameterized component erases to the raw array class")
    void arrayOfParameterizedComponent() {
        var types = syntheticTypes(new TypeInfo.ArrayType(
                new TypeInfo.ParameterizedType(DotName.of("java.lang.Class"), List.of(cls(STRING))), 2));

        assertTrue(types.contains(Class[][].class), types::toString);
    }

    @Test
    @DisplayName("an array whose component cannot be named or loaded is dropped")
    void unresolvableArrays() {
        var types = syntheticTypes(
                new TypeInfo.ArrayType(new TypeInfo.TypeVariable("T", List.of()), 1),
                new TypeInfo.ArrayType(cls(MISSING_CLASS), 1));

        assertEquals(Set.of(Object.class), types);
    }

    @Test
    @DisplayName("a producer method inherited from the superclass gives the produced type")
    void inheritedProducerMethod() {
        var types = bean(BeanDescriptor.BeanKind.PRODUCER_METHOD, SubProducer.class.getName() + "#names", Set.of())
                .getTypes();

        assertTrue(types.stream().anyMatch(t -> t instanceof ParameterizedType pt && pt.getRawType() == List.class),
                types::toString);
    }

    @Test
    @DisplayName("a producer method that does not exist falls back on the descriptor's types")
    void missingProducerMethod() {
        var types = bean(BeanDescriptor.BeanKind.PRODUCER_METHOD, SubProducer.class.getName() + "#absent",
                Set.of(cls(STRING))).getTypes();

        assertEquals(Set.of(String.class, Object.class), types);
    }

    @Test
    @DisplayName("a producer field inherited from the superclass gives the produced type")
    void inheritedProducerField() {
        var types = bean(BeanDescriptor.BeanKind.PRODUCER_FIELD, SubProducer.class.getName() + ".text", Set.of())
                .getTypes();

        assertTrue(types.contains(CharSequence.class), types::toString);
    }

    @Test
    @DisplayName("a producer field that does not exist falls back on the descriptor's types")
    void missingProducerField() {
        var types = bean(BeanDescriptor.BeanKind.PRODUCER_FIELD, SubProducer.class.getName() + ".absent",
                Set.of(cls(STRING))).getTypes();

        assertEquals(Set.of(String.class, Object.class), types);
    }

    @Test
    @DisplayName("an array producer has only its array type and Object, plain or generic")
    void arrayProducers() {
        var plain = bean(BeanDescriptor.BeanKind.PRODUCER_METHOD, SubProducer.class.getName() + "#numbers", Set.of())
                .getTypes();
        var generic = bean(BeanDescriptor.BeanKind.PRODUCER_METHOD, SubProducer.class.getName() + "#lists", Set.of())
                .getTypes();

        assertEquals(Set.of(int[].class, Object.class), plain);
        assertInstanceOf(GenericArrayType.class, onlyTypeBesidesObject(generic));
    }

    @Test
    @DisplayName("@Typed on a producer method keeps the parameterized type of the listed class and Object")
    void typedProducer() {
        var types = bean(BeanDescriptor.BeanKind.PRODUCER_METHOD, SubProducer.class.getName() + "#typedList", Set.of())
                .getTypes();

        var listType = assertInstanceOf(ParameterizedType.class, onlyTypeBesidesObject(types));
        assertEquals(List.class, listType.getRawType());
        assertFalse(types.contains(ArrayList.class));
    }

    @Test
    @DisplayName("a scope annotation that cannot be loaded makes the bean @Dependent")
    void unloadableScopeIsDependent() {
        var descriptor = new BeanDescriptor(new BeanId("scoped"), DotName.of(Owner.class.getName()),
                BeanDescriptor.BeanKind.MANAGED, Set.of(cls(Owner.class.getName())),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                new ScopeInfo(DotName.of(MISSING_CLASS), true), false, 0, List.of(), null);
        var managed = new ManagedBean<>(descriptor, Owner::new, ManagedBeanTypeResolutionTest.class.getClassLoader(), null);

        assertEquals(jakarta.enterprise.context.Dependent.class, managed.getScope());
    }

    @Test
    @DisplayName("a disposer that throws is suppressed: destroying the instance still completes")
    void throwingDestroyerIsSuppressed() {
        var managed = bean(BeanDescriptor.BeanKind.MANAGED, Owner.class.getName(), Set.of(cls(Owner.class.getName())));
        var called = new AtomicBoolean();
        managed.setDestroyer((instance, ctx) -> {
            called.set(true);
            throw new IllegalStateException("disposer failure");
        });
        var ctx = new CreationalContextImpl<Object>();

        managed.destroy(new Owner(), ctx);

        assertTrue(called.get(), "the disposer must have run");
    }
}
