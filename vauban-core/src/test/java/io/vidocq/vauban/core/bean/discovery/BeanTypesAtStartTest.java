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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.types.TypeHierarchyResolver;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bean types the container computes at start: the type variables of the classes and interfaces above a bean
 * resolved as the Java compiler resolves them (JLS 4.10.2), inside a wildcard, an array and the type that encloses an
 * inner class too, and only the legal bean types (CDI 4.1, 2.2.1), the same in the descriptor the resolution reads and
 * in {@code Bean.getTypes()} (BUG-20261117-14).
 */
@DisplayName("Bean types at start: type variables resolved everywhere, legal bean types only")
class BeanTypesAtStartTest {

    private static final String OBJECT = "java.lang.Object";
    private static final String STRING = "java.lang.String";
    private static final String NUMBER = "java.lang.Number";

    public interface Holder<T> {
    }

    public abstract static class WildShelf<S> implements Holder<Map<? super S, ? extends S>> {
    }

    @Dependent
    public static class WildLibrary extends WildShelf<String> {
    }

    public abstract static class ArrayShelf<S> implements Holder<S[]> {
    }

    @Dependent
    public static class ArrayLibrary extends ArrayShelf<String> {
    }

    public abstract static class ListArrayShelf<S> implements Holder<List<S>[]> {
    }

    public abstract static class WildDirect implements Holder<Map<? super String, ? extends String>> {
    }

    public abstract static class ListArrayDirect implements Holder<List<String>[]> {
    }

    @Dependent
    public static class ListArrayLibrary extends ListArrayShelf<String> {
    }

    public static class Outer<O> {
        public class Inner<I> implements Holder<O> {
        }
    }

    @Dependent
    public static class InnerUser extends Outer<String>.Inner<Integer> {
        public InnerUser() {
            new Outer<String>().super();
        }
    }

    public abstract static class EnclosedShelf<S> implements Holder<Outer<S>.Inner<Integer>> {
    }

    @Dependent
    public static class EnclosedLibrary extends EnclosedShelf<String> {
    }

    public interface Base<B> extends Holder<B> {
    }

    @Dependent
    @SuppressWarnings("rawtypes")
    public static class RawUser implements Base {
    }

    @Dependent
    public static class Ranked<T extends Number> implements Holder<T> {
    }

    @Dependent
    @Typed(TypedRanked.class)
    public static class TypedRanked<T extends Number> implements Holder<T> {
    }

    public interface Wild extends Holder<List<? extends Number>> {
    }

    @Dependent
    public static class WildFactory {
        @Produces
        Wild wild() {
            return new Wild() {
            };
        }
    }

    /** The shape of the CDI TCK's {@code Bird}: a type variable in a wildcard, in a type argument. */
    public static class Perch<P> implements Holder<List<? extends P>> {
    }

    @Dependent
    public static class Eyrie<P> extends Perch<P> {
    }

    @Dependent
    @SuppressWarnings("rawtypes")
    public static class RawEyrie<P> extends Perch {
    }

    @Dependent
    public static class EyrieFactory {
        @Produces
        <F> Eyrie<F> eyrie() {
            return new Eyrie<>();
        }
    }

    @Dependent
    public static class ArrayReader {
        @Inject
        Holder<String[]> holder;
    }

    @Dependent
    public static class ListBaseFactory {
        @Produces
        Base<List<String>> base() {
            return new Base<>() {
            };
        }
    }

    @Dependent
    public static class ListHolderReader {
        @Inject
        Holder<List<String>> holder;
    }

    @Dependent
    public static class ObjectHolderReader {
        @Inject
        Holder<Object> holder;
    }

    @Dependent
    @SuppressWarnings("rawtypes")
    public static class LegacyNames extends ArrayList {
        private static final long serialVersionUID = 1L;
    }

    @Dependent
    @SuppressWarnings("rawtypes")
    public static class Legacy<E> extends ArrayList {
        private static final long serialVersionUID = 1L;
    }

    @Dependent
    @Typed(Holder.class)
    @SuppressWarnings("rawtypes")
    public static class TypedRawUser implements Base {
    }

    @Dependent
    public static class LegacyFactory {
        @Produces
        LegacyNames names() {
            return new LegacyNames();
        }
    }

    @Dependent
    public static class NumberFactory {
        @Produces
        <N extends Number> Base<N> numbers() {
            return new Base<>() {
            };
        }
    }

    @Dependent
    public static class ListArrayFactory {
        @Produces
        Base<List<String>[]> lists() {
            return new Base<>() {
            };
        }
    }

    @Dependent
    public static class RawRankedFactory {
        @Produces
        @SuppressWarnings("rawtypes")
        Ranked ranked() {
            return new Ranked<Integer>();
        }
    }

    private static TypeInfo cls(Class<?> type) {
        return cls(type.getName());
    }

    private static TypeInfo cls(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    private static TypeInfo parameterized(Class<?> raw, TypeInfo... arguments) {
        return new TypeInfo.ParameterizedType(DotName.of(raw.getName()), List.of(arguments));
    }

    private static TypeInfo array(TypeInfo component) {
        return new TypeInfo.ArrayType(component, 1);
    }

    /** The bean types the descriptor of {@code bean} holds, which the resolution reads at start. */
    private static Set<TypeInfo> descriptorTypes(Class<?> bean) {
        var info = scan(bean);
        var builder = new IndexBuilder();
        builder.add(info);
        return new BeanDiscovery(builder.build()).computeBeanTypes(info);
    }

    private static io.vidocq.vauban.indexer.model.ClassInfo scan(Class<?> type) {
        var resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return ClassFileScanner.scan(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code Bean.getTypes()} of the bean of class {@code bean}, in the index's terms. */
    private static Set<TypeInfo> containerTypes(Class<?> bean) {
        try (var container = VaubanContainer.builder().addBeanClass(bean).build()) {
            return container.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE).stream()
                    .filter(candidate -> candidate.getBeanClass() == bean).findFirst().orElseThrow().getTypes()
                    .stream().map(BeanDiscovery::reflectTypeToTypeInfo).collect(Collectors.toSet());
        }
    }

    /** {@code Bean.getTypes()} of the producer {@code factory} declares, in the index's terms. */
    private static Set<TypeInfo> producerTypes(Class<?> factory) {
        try (var container = VaubanContainer.builder().addBeanClass(factory).build()) {
            return container.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE).stream()
                    .filter(candidate -> candidate.getBeanClass() == factory
                            && !candidate.getTypes().contains(factory))
                    .findFirst().orElseThrow().getTypes()
                    .stream().map(BeanDiscovery::reflectTypeToTypeInfo).collect(Collectors.toSet());
        }
    }

    private static Set<TypeInfo> with(Set<TypeInfo> types, TypeInfo type) {
        var all = new HashSet<>(types);
        all.add(type);
        return all;
    }

    /** The type argument of the {@code Holder} {@code TypeHierarchyResolver} gives above {@code type}. */
    private static java.lang.reflect.Type holderArgument(Class<?> type) {
        return TypeHierarchyResolver.resolveAllSupertypes(type).stream()
                .filter(supertype -> supertype instanceof java.lang.reflect.ParameterizedType parameterized
                        && parameterized.getRawType() == Holder.class)
                .map(supertype -> ((java.lang.reflect.ParameterizedType) supertype).getActualTypeArguments()[0])
                .findFirst().orElseThrow();
    }

    private static void assertSameType(java.lang.reflect.Type jdk, java.lang.reflect.Type substituted) {
        assertEquals(jdk, substituted);
        assertEquals(substituted, jdk);
        assertEquals(jdk.hashCode(), substituted.hashCode(), substituted::getTypeName);
        assertEquals(jdk.getTypeName(), substituted.getTypeName());
    }

    /** What {@code TypeHierarchyResolver} gives above {@code type}, in the index's terms. */
    private static Set<TypeInfo> hierarchy(java.lang.reflect.Type type) {
        return TypeHierarchyResolver.resolveAllSupertypes(type).stream().map(BeanDiscovery::reflectTypeToTypeInfo)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a type variable resolves inside a wildcard, at either bound")
    void aTypeVariableResolvesInsideAWildcard() {
        var string = cls(STRING);

        assertTrue(hierarchy(WildLibrary.class).contains(parameterized(Holder.class, parameterized(Map.class,
                        new TypeInfo.WildcardType(null, string), new TypeInfo.WildcardType(string, null)))),
                () -> hierarchy(WildLibrary.class).toString());
    }

    @Test
    @DisplayName("a type variable resolves inside an array, of a class or of a parameterized type")
    void aTypeVariableResolvesInsideAnArray() {
        assertTrue(hierarchy(ArrayLibrary.class).contains(parameterized(Holder.class, array(cls(STRING)))),
                () -> hierarchy(ArrayLibrary.class).toString());
        assertTrue(hierarchy(ListArrayLibrary.class).contains(parameterized(Holder.class,
                array(parameterized(List.class, cls(STRING))))), () -> hierarchy(ListArrayLibrary.class).toString());
    }

    @Test
    @DisplayName("a type variable of the class that encloses an inner class resolves, above it and inside its type")
    void aTypeVariableOfTheEnclosingClassResolves() {
        assertTrue(hierarchy(InnerUser.class).contains(parameterized(Holder.class, cls(STRING))),
                () -> hierarchy(InnerUser.class).toString());

        var enclosed = TypeHierarchyResolver.resolveAllSupertypes(EnclosedLibrary.class).stream()
                .filter(type -> type instanceof java.lang.reflect.ParameterizedType parameterized
                        && parameterized.getRawType() == Holder.class)
                .map(type -> (java.lang.reflect.ParameterizedType) ((java.lang.reflect.ParameterizedType) type)
                        .getActualTypeArguments()[0])
                .findFirst().orElseThrow();
        var owner = (java.lang.reflect.ParameterizedType) enclosed.getOwnerType();
        assertSame(String.class, owner.getActualTypeArguments()[0], enclosed::toString);
    }

    /**
     * The descriptor and {@code Bean.getTypes()} hold the types above a bean class, their type variables resolved, the
     * legal ones only (CDI 4.1, 2.2.1): {@code WildLibrary} is no
     * {@code Holder<Map<? super String, ? extends String>>}, nor a {@code Holder}. A raw supertype erases the types
     * above it (JLS 4.8): {@code RawUser implements Base} is a {@code Holder}, not a {@code Holder<B>}.
     */
    @Test
    @DisplayName("the descriptor and Bean.getTypes() hold the resolved legal types, and the raw types a raw type gives")
    void theDescriptorAndTheBeanHoldTheResolvedLegalTypes() {
        var expected = Map.of(
                WildLibrary.class, Set.of(cls(WildLibrary.class), parameterized(WildShelf.class, cls(STRING)),
                        cls(OBJECT)),
                ArrayLibrary.class, Set.of(cls(ArrayLibrary.class), parameterized(ArrayShelf.class, cls(STRING)),
                        parameterized(Holder.class, array(cls(STRING))), cls(OBJECT)),
                ListArrayLibrary.class, Set.of(cls(ListArrayLibrary.class), parameterized(ListArrayShelf.class,
                        cls(STRING)), parameterized(Holder.class, array(parameterized(List.class, cls(STRING)))),
                        cls(OBJECT)),
                // Outer<String>.Inner<Integer>, its owner part of the type (BUG-20261117-21)
                InnerUser.class, Set.of(cls(InnerUser.class), new TypeInfo.ParameterizedType(
                                DotName.of(Outer.Inner.class.getName()), List.of(cls("java.lang.Integer")),
                                parameterized(Outer.class, cls(STRING))),
                        parameterized(Holder.class, cls(STRING)), cls(OBJECT)),
                RawUser.class, Set.of(cls(RawUser.class), cls(Base.class), cls(Holder.class), cls(OBJECT)));

        var descriptors = new HashSet<String>();
        var beans = new HashSet<String>();
        expected.forEach((bean, types) -> {
            if (!types.equals(descriptorTypes(bean))) {
                descriptors.add(bean.getSimpleName() + " " + descriptorTypes(bean));
            }
            if (!types.equals(containerTypes(bean))) {
                beans.add(bean.getSimpleName() + " " + containerTypes(bean));
            }
        });
        assertEquals(Set.of(), descriptors, "the descriptor types");
        assertEquals(Set.of(), beans, "Bean.getTypes()");
    }

    /**
     * A bean keeps the types its own type variables are in, its class's among them: the bean types of a managed bean
     * include the bean class (CDI 4.1, 3.1.2), with its type parameters, {@code Ranked<T extends Number>}, in the
     * descriptor as in {@code Bean.getTypes()}, and so does a {@code @Typed} that names the class. The descriptor
     * named the class raw, {@code Ranked}, which satisfied an injection point of any parameterization of it, of
     * {@code Ranked<Integer>} among them, only through the rule BUG-20261117-16 removes.
     */
    @Test
    @DisplayName("a bean keeps the types its own type variables are in, its class's among them")
    void aBeanKeepsTheTypesItsOwnTypeVariablesAreIn() {
        var t = new TypeInfo.TypeVariable("T", List.of(cls(NUMBER)));
        var ranked = Set.of(parameterized(Ranked.class, t), parameterized(Holder.class, t), cls(OBJECT));
        var typedRanked = Set.of(parameterized(TypedRanked.class, t), cls(OBJECT));

        assertAll(() -> assertEquals(ranked, descriptorTypes(Ranked.class), "descriptor of Ranked"),
                () -> assertEquals(ranked, containerTypes(Ranked.class), "Bean.getTypes() of Ranked"),
                () -> assertEquals(typedRanked, descriptorTypes(TypedRanked.class), "descriptor of TypedRanked"),
                () -> assertEquals(typedRanked, containerTypes(TypedRanked.class), "Bean.getTypes() of TypedRanked"));
    }

    @Test
    @DisplayName("a producer's types hold no type with a wildcard type parameter")
    void aProducersTypesHoldNoTypeWithAWildcardTypeParameter() {
        var builder = new IndexBuilder();
        builder.add(scan(WildFactory.class));

        assertEquals(Set.of(cls(Wild.class), cls(OBJECT)),
                new BeanDiscovery(builder.build()).computeProducerTypes(cls(Wild.class)));
    }

    /**
     * A type argument of a produced type that is not a class keeps its shape in the types above it: a parameterized
     * type, an array of primitives, an array of a parameterized type, a type variable, and a wildcard, whose types
     * are not legal bean types. Each was {@code Object} (BUG-20261117-17). A class argument is the class.
     */
    @Test
    @DisplayName("a type argument of a produced type keeps its shape in the types above it")
    void aTypeArgumentOfAProducedTypeKeepsItsShapeInTheTypesAboveIt() {
        var discovery = new BeanDiscovery(new IndexBuilder().build());
        var listOfString = parameterized(List.class, cls(STRING));
        var ints = new TypeInfo.ArrayType(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT), 2);
        var number = new TypeInfo.TypeVariable("N", List.of(cls(NUMBER)));
        var lists = new TypeInfo.ArrayType(listOfString, 1);

        assertAll(
                () -> assertEquals(Set.of(parameterized(Base.class, cls(STRING)), parameterized(Holder.class,
                                cls(STRING)), cls(OBJECT)),
                        discovery.computeProducerTypes(parameterized(Base.class, cls(STRING)))),
                () -> assertEquals(Set.of(parameterized(Base.class, listOfString),
                                parameterized(Holder.class, listOfString), cls(OBJECT)),
                        discovery.computeProducerTypes(parameterized(Base.class, listOfString))),
                () -> assertEquals(Set.of(parameterized(ArrayShelf.class, ints), parameterized(Holder.class,
                                new TypeInfo.ArrayType(ints.componentType(), 3)), cls(OBJECT)),
                        discovery.computeProducerTypes(parameterized(ArrayShelf.class, ints))),
                () -> assertEquals(Set.of(parameterized(ListArrayShelf.class, number),
                                parameterized(Holder.class, array(parameterized(List.class, number))), cls(OBJECT)),
                        discovery.computeProducerTypes(parameterized(ListArrayShelf.class, number))),
                () -> assertEquals(Set.of(parameterized(Base.class, lists), parameterized(Holder.class, lists),
                                cls(OBJECT)),
                        discovery.computeProducerTypes(parameterized(Base.class, lists))),
                () -> assertEquals(Set.of(cls(OBJECT)), discovery.computeProducerTypes(parameterized(Base.class,
                        new TypeInfo.WildcardType(cls(NUMBER), null)))));
    }

    @Test
    @DisplayName("an injection point of a type argument array resolves at start")
    void anInjectionPointOfATypeArgumentArrayResolvesAtStart() {
        try (var container = VaubanContainer.builder().addBeanClass(ArrayLibrary.class)
                .addBeanClass(ArrayReader.class).build()) {
            var manager = container.getBeanManager();
            var reader = manager.resolve(manager.getBeans(ArrayReader.class));
            var instance = (ArrayReader) manager.getReference(reader, ArrayReader.class,
                    manager.createCreationalContext(reader));

            assertEquals(ArrayLibrary.class, instance.holder.getClass());
        }
    }

    /**
     * A producer of {@code Base<List<String>>} is a {@code Holder<List<String>>} at start, as at build
     * (BUG-20261117-17).
     */
    @Test
    @DisplayName("a producer of a type with a parameterized type argument satisfies that parameterization above it")
    void aProducerOfATypeWithAParameterizedTypeArgumentSatisfiesThatParameterizationAboveIt() {
        try (var container = VaubanContainer.builder().addBeanClass(ListBaseFactory.class)
                .addBeanClass(ListHolderReader.class).build()) {
            var manager = container.getBeanManager();
            var reader = manager.resolve(manager.getBeans(ListHolderReader.class));
            var instance = (ListHolderReader) manager.getReference(reader, ListHolderReader.class,
                    manager.createCreationalContext(reader));

            assertInstanceOf(Base.class, instance.holder);
        }
    }

    /**
     * A producer of {@code Base<List<String>>} is not a {@code Holder<Object>}, which it was at start
     * (BUG-20261117-17).
     */
    @Test
    @DisplayName("a producer of a type with a parameterized type argument satisfies no other parameterization above it")
    void aProducerOfATypeWithAParameterizedTypeArgumentSatisfiesNoOtherParameterizationAboveIt() {
        var objectReader = VaubanContainer.builder().addBeanClass(ListBaseFactory.class)
                .addBeanClass(ObjectHolderReader.class);

        assertThrows(DeploymentException.class, objectReader::build);
    }

    /**
     * A class that extends a generic class raw has the raw types above it, which Java erases there (JLS 4.8), in
     * the descriptor and in {@code Bean.getTypes()}, as a bean and as a produced type: {@code LegacyNames extends
     * ArrayList} is a {@code List}. So is {@code Legacy<E> extends ArrayList}, whose {@code E} is not the {@code E}
     * of {@code List<E>}, and a {@code @Typed(Holder.class)} class that implements {@code Base} raw is a
     * {@code Holder}. The legal bean type rule (CDI 4.1, 2.2.1) dropped {@code List<E>} and {@code Holder<B>}, and
     * nothing was left of them; the descriptor kept {@code Legacy}'s {@code List<E>}, its {@code E} taken for its own.
     */
    @Test
    @DisplayName("a class that extends a generic class raw has the raw types above it")
    void aClassThatExtendsAGenericClassRawHasTheRawTypesAboveIt() {
        var aboveArrayList = Set.of(cls(ArrayList.class), cls(AbstractList.class), cls(AbstractCollection.class),
                cls(List.class), cls(Collection.class), cls(SequencedCollection.class), cls(Iterable.class),
                cls(RandomAccess.class), cls(Cloneable.class), cls(Serializable.class), cls(OBJECT));
        var names = with(aboveArrayList, cls(LegacyNames.class));
        var holder = Set.of(cls(Holder.class), cls(OBJECT));

        assertAll(
                () -> assertEquals(names, descriptorTypes(LegacyNames.class), "descriptor of LegacyNames"),
                () -> assertEquals(names, containerTypes(LegacyNames.class), "Bean.getTypes() of LegacyNames"),
                () -> assertEquals(with(aboveArrayList, parameterized(Legacy.class,
                        new TypeInfo.TypeVariable("E", List.of()))), descriptorTypes(Legacy.class),
                        "descriptor of Legacy"),
                () -> assertEquals(with(aboveArrayList, parameterized(Legacy.class,
                        new TypeInfo.TypeVariable("E", List.of()))), containerTypes(Legacy.class),
                        "Bean.getTypes() of Legacy"),
                () -> assertEquals(names, new BeanDiscovery(new IndexBuilder().build())
                        .computeProducerTypes(cls(LegacyNames.class)), "descriptor of a producer of LegacyNames"),
                () -> assertEquals(names, producerTypes(LegacyFactory.class),
                        "Bean.getTypes() of a producer of LegacyNames"),
                () -> assertEquals(holder, descriptorTypes(TypedRawUser.class), "descriptor of TypedRawUser"),
                () -> assertEquals(holder, containerTypes(TypedRawUser.class), "Bean.getTypes() of TypedRawUser"));
    }

    /**
     * A type with a wildcard among its type arguments, at any depth, is no legal bean type (CDI 4.1, 2.2.1), and the
     * bean does not have its raw class either: {@code Eyrie<P> extends Perch<P>}, where {@code Perch<P> implements
     * Holder<List<? extends P>>}, is an {@code Eyrie<P>}, a {@code Perch<P>} and an {@code Object}, as a bean and as
     * a produced type. That is the shape of the CDI TCK's {@code BeanTypesWithIllegalTypeTest}, which wants three
     * types. A raw supertype erases the types above it (JLS 4.8), wildcards and all: {@code RawEyrie<P> extends
     * Perch}, raw, is a {@code Holder}, its own {@code P} not the {@code P} in {@code Holder<List<? extends P>>}.
     */
    @Test
    @DisplayName("a type with a wildcard type argument goes with its raw class, unless a raw supertype erased it")
    void aTypeWithAWildcardTypeArgumentGoesWithItsRawClassUnlessARawSupertypeErasedIt() {
        var p = new TypeInfo.TypeVariable("P", List.of());
        var f = new TypeInfo.TypeVariable("F", List.of());
        var producedEyrie = Set.of(parameterized(Eyrie.class, f), parameterized(Perch.class, f), cls(OBJECT));
        var aboveRawEyrie = Set.of(cls(Perch.class), cls(Holder.class), cls(OBJECT));

        assertAll(
                () -> assertEquals(Set.of(parameterized(Eyrie.class, p), parameterized(Perch.class, p), cls(OBJECT)),
                        descriptorTypes(Eyrie.class), "descriptor of Eyrie"),
                () -> assertEquals(Set.of(parameterized(Eyrie.class, p), parameterized(Perch.class, p), cls(OBJECT)),
                        containerTypes(Eyrie.class), "Bean.getTypes() of Eyrie"),
                () -> assertEquals(producedEyrie, new BeanDiscovery(new IndexBuilder().build())
                        .computeProducerTypes(parameterized(Eyrie.class, f)), "descriptor of a producer of Eyrie<F>"),
                () -> assertEquals(producedEyrie, producerTypes(EyrieFactory.class),
                        "Bean.getTypes() of a producer of Eyrie<F>"),
                () -> assertEquals(with(aboveRawEyrie, parameterized(RawEyrie.class, p)),
                        descriptorTypes(RawEyrie.class), "descriptor of RawEyrie"),
                () -> assertEquals(with(aboveRawEyrie, parameterized(RawEyrie.class, p)),
                        containerTypes(RawEyrie.class), "Bean.getTypes() of RawEyrie"));
    }

    /**
     * A producer keeps the type variables of the type it produces, a method's {@code N} say, in the types above it,
     * and has the raw class of a type with another one: a producer of {@code Ranked}, raw, where
     * {@code Ranked<T extends Number> implements Holder<T>}, is a {@code Holder}, not a {@code Holder<T>}, in the
     * descriptor as in {@code Bean.getTypes()}. A type variable is no bean type.
     */
    @Test
    @DisplayName("a producer keeps its produced type's type variables, and the raw class of a type with another")
    void aProducerKeepsTheTypeVariablesOfTheTypeItProduces() {
        var number = new TypeInfo.TypeVariable("N", List.of(cls(NUMBER)));
        var lists = array(parameterized(List.class, cls(STRING)));
        var ranked = Set.of(cls(Ranked.class), cls(Holder.class), cls(OBJECT));
        var discovery = new BeanDiscovery(new IndexBuilder().build());

        assertAll(
                () -> assertEquals(Set.of(parameterized(Base.class, number), parameterized(Holder.class, number),
                        cls(OBJECT)), producerTypes(NumberFactory.class), "Bean.getTypes() of a producer of Base<N>"),
                () -> assertEquals(Set.of(parameterized(Base.class, lists), parameterized(Holder.class, lists),
                        cls(OBJECT)), producerTypes(ListArrayFactory.class),
                        "Bean.getTypes() of a producer of Base<List<String>[]>"),
                () -> assertEquals(ranked, producerTypes(RawRankedFactory.class),
                        "Bean.getTypes() of a producer of Ranked"),
                () -> assertEquals(ranked, discovery.computeProducerTypes(cls(Ranked.class)),
                        "descriptor of a producer of Ranked"),
                () -> assertEquals(Set.of(cls(OBJECT)), discovery.computeProducerTypes(
                        new TypeInfo.TypeVariable("T", List.of())), "descriptor of a producer of T"));
    }

    /**
     * A wildcard and an array a substitution gives equal the JDK's, which reflection gives for the same type written
     * out: {@code Bean.getTypes()} and the resolution compare them with those of an injection point.
     */
    @Test
    @DisplayName("a substituted wildcard and a substituted array equal the JDK's")
    void aSubstitutedWildcardAndArrayEqualTheJdks() {
        var wildcards = ((java.lang.reflect.ParameterizedType) holderArgument(WildLibrary.class)).getActualTypeArguments();
        var jdkWildcards = ((java.lang.reflect.ParameterizedType) holderArgument(WildDirect.class))
                .getActualTypeArguments();

        assertSameType(jdkWildcards[0], wildcards[0]);
        assertSameType(jdkWildcards[1], wildcards[1]);
        assertSameType(holderArgument(ListArrayDirect.class), holderArgument(ListArrayLibrary.class));
    }
}
