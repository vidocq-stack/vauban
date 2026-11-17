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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BceTypeMatcher} matching {@code @Enhancement}/{@code @Registration} filters against members, the index's
 * class hierarchy and bean types — including classes whose members name a type absent at run time.
 */
class BceTypeMatcherMembersTest {

    private static final ClassLoader LOADER = BceTypeMatcherMembersTest.class.getClassLoader();

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marked {
    }

    @SuppressWarnings("unused")
    public static class MarkedField {
        @Marked
        String field;
    }

    public static class MarkedConstructor {
        @Marked
        public MarkedConstructor() {
            // the constructor carries the annotation
        }
    }

    public static class Plain {
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation>[] marked() {
        return new Class[] {Marked.class};
    }

    private static ClassInfo indexed(String name, String superName) {
        return new ClassInfo(DotName.of(name), superName == null ? null : DotName.of(superName), List.of(), 0x0001,
                List.of(), List.of(), List.of(), ClassInfo.ClassKind.CLASS);
    }

    @Test
    void anAnnotationOnAFieldOrAConstructorMatches() {
        assertTrue(BceTypeMatcher.matchesAnnotations(marked(), MarkedField.class));
        assertTrue(BceTypeMatcher.matchesAnnotations(marked(), MarkedConstructor.class));
        assertFalse(BceTypeMatcher.matchesAnnotations(marked(), Plain.class));
    }

    @Test
    void aClassWhoseMembersNameAnAbsentTypeMatchesThroughItsOtherMembersOnly() {
        assertFalse(BceTypeMatcher.matchesAnnotations(marked(), classNamingAnAbsentType()));
    }

    @Test
    void aSupertypeIsFoundUpTheIndexedHierarchy() {
        var child = indexed("com.acme.Child", Middle.class.getName());
        var lookup = new IndexLookup(new IndexBuilder()
                .add(child)
                .add(indexed(Middle.class.getName(), Root.class.getName()))
                .add(indexed(Root.class.getName(), null))
                .build());

        assertTrue(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {Root.class}, true, child, lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {Plain.class}, true, child, lookup, LOADER));
    }

    /** The top of the indexed hierarchy above; only its name takes part. */
    private static final class Root {
    }

    private static ClassInfo indexed(String name, String superName, Class<?>... interfaces) {
        return new ClassInfo(DotName.of(name), DotName.of(superName),
                java.util.Arrays.stream(interfaces).map(type -> DotName.of(type.getName())).toList(), 0x0001,
                List.of(), List.of(), List.of(), ClassInfo.ClassKind.CLASS);
    }

    private static InterceptorDescriptor interceptor(String name) {
        return new InterceptorDescriptor(DotName.of(name), Set.of(), "wrap", 1);
    }

    /**
     * Annotation processing compiles the interceptor and the class it extends, so neither loads: the index describes
     * the interceptor, and the processor the class it extends, which no annotation brought into the index
     * (BUG-20261117-02).
     */
    @Test
    void anInterceptorThatDoesNotLoadMatchesTheTypesTheIndexAndTheProcessorDescribe() {
        var lookup = new IndexLookup(new IndexBuilder()
                .add(indexed("com.acme.Logger", "com.acme.Wrapping"))
                .build());
        var wrapping = indexed("com.acme.Wrapping", Object.class.getName(), Runnable.class);
        var logger = interceptor("com.acme.Logger");

        assertTrue(BceTypeMatcher.matchesInterceptorTypes(new Class<?>[] {Object.class}, logger, lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesInterceptorTypes(new Class<?>[] {Runnable.class}, logger, lookup, LOADER),
                "the index alone does not know the class it extends");
        assertTrue(IndexLookup.whereUnindexedDescribedBy(
                name -> name.equals(wrapping.name()) ? java.util.Optional.of(wrapping) : java.util.Optional.empty(),
                () -> BceTypeMatcher.matchesInterceptorTypes(new Class<?>[] {Runnable.class}, logger, lookup,
                        LOADER)));
        assertFalse(BceTypeMatcher.matchesInterceptorTypes(new Class<?>[] {Plain.class}, logger, lookup, LOADER));
    }

    private static io.vidocq.vauban.core.bean.model.ObserverDescriptor observerOf(TypeInfo eventType) {
        return new io.vidocq.vauban.core.bean.model.ObserverDescriptor(DotName.of("com.acme.Listener"), "on", eventType,
                List.of(), false, 0);
    }

    /** An observer matches through the type of the event it observes, not the class that declares it (BUG-20261117-05). */
    @Test
    void anObserverMatchesTheTypesOfTheEventItObserves() {
        var lookup = new IndexLookup(new IndexBuilder().add(indexed("com.acme.Listener", Plain.class.getName())).build());
        var lists = observerOf(new TypeInfo.ParameterizedType(DotName.of(java.util.List.class.getName()), List.of()));
        var arrays = observerOf(new TypeInfo.ArrayType(TypeInfo.PrimitiveType.fromDescriptor('I'), 1));

        assertTrue(BceTypeMatcher.matchesObserverTypes(new Class<?>[] {java.util.Collection.class}, lists, lookup,
                LOADER));
        assertFalse(BceTypeMatcher.matchesObserverTypes(new Class<?>[] {Plain.class}, lists, lookup, LOADER),
                "the class that declares the observer");
        assertTrue(BceTypeMatcher.matchesObserverTypes(new Class<?>[] {Object.class}, arrays, lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesObserverTypes(new Class<?>[] {Plain.class}, arrays, lookup, LOADER));
    }

    private static boolean observes(TypeInfo eventType, IndexLookup lookup, Class<?>... types) {
        return BceTypeMatcher.matchesObserverTypes(types, observerOf(eventType), lookup, LOADER);
    }

    private static TypeInfo.ArrayType arrayOf(TypeInfo component, int dimensions) {
        return new TypeInfo.ArrayType(component, dimensions);
    }

    private static TypeInfo.ClassType classType(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    /**
     * An array is assignable to {@code Object}, {@code Cloneable} and {@code Serializable}, and to an array of as many
     * dimensions of a supertype of its component, or of fewer dimensions of a type every array has. Its component is
     * read as a class the observer observes is: through the index (BUG-20261117-05).
     */
    @Test
    void anArrayEventMatchesTheTypesOfEveryArrayAndTheArraysOfTheSupertypesOfItsComponent() {
        var lookup = new IndexLookup(new IndexBuilder()
                .add(indexed("com.acme.Logger", java.util.AbstractList.class.getName()))
                .build());
        var strings = arrayOf(classType(String.class.getName()), 1);
        var matrix = arrayOf(classType(String.class.getName()), 2);
        var ints = arrayOf(TypeInfo.PrimitiveType.fromDescriptor('I'), 1);
        var intMatrix = arrayOf(TypeInfo.PrimitiveType.fromDescriptor('I'), 2);

        assertTrue(observes(strings, lookup, java.io.Serializable.class));
        assertTrue(observes(strings, lookup, Plain.class, Cloneable.class));
        assertTrue(observes(strings, lookup, Object[].class));
        assertTrue(observes(strings, lookup, CharSequence[].class));
        assertFalse(observes(strings, lookup, Number[].class, Object[][].class, String.class, int[].class));
        assertTrue(observes(matrix, lookup, Object[].class));
        assertTrue(observes(matrix, lookup, java.io.Serializable[].class));
        assertTrue(observes(matrix, lookup, CharSequence[][].class));
        assertFalse(observes(matrix, lookup, CharSequence[].class, Object[][][].class));
        assertTrue(observes(ints, lookup, int[].class));
        assertFalse(observes(ints, lookup, Object[].class, long[].class, Integer[].class));
        assertTrue(observes(intMatrix, lookup, Object[].class));
        assertFalse(observes(intMatrix, lookup, long[][].class));
        assertTrue(observes(arrayOf(classType("com.acme.Logger"), 1), lookup, java.util.Collection[].class));
    }

    /**
     * A type variable is assignable to what one of its bounds is assignable to, and one without a bound to
     * {@code Object} only; a primitive type to what its wrapper is (BUG-20261117-05).
     */
    @Test
    void aTypeVariableEventMatchesTheTypesOfItsBoundsAndAPrimitiveThoseOfItsWrapper() {
        var lookup = new IndexLookup(new IndexBuilder().build());
        var numbers = new TypeInfo.TypeVariable("T", List.of(classType(Number.class.getName())));
        var any = new TypeInfo.TypeVariable("U", List.of());
        var sameAsNumbers = new TypeInfo.TypeVariable("V", List.of(numbers));
        var both = new TypeInfo.TypeVariable("W", List.of(classType(Number.class.getName()),
                classType(Runnable.class.getName())));

        assertTrue(observes(numbers, lookup, Number.class));
        assertTrue(observes(numbers, lookup, java.io.Serializable.class));
        assertFalse(observes(numbers, lookup, Integer.class, Plain.class));
        assertTrue(observes(any, lookup, Object.class));
        assertFalse(observes(any, lookup, java.io.Serializable.class));
        assertTrue(observes(sameAsNumbers, lookup, Number.class));
        assertTrue(observes(both, lookup, Runnable.class));
        assertTrue(observes(arrayOf(numbers, 1), lookup, Number[].class));
        assertFalse(observes(arrayOf(any, 1), lookup, Number[].class));
        assertTrue(observes(TypeInfo.PrimitiveType.fromDescriptor('I'), lookup, Number.class));
        assertTrue(observes(TypeInfo.PrimitiveType.fromDescriptor('Z'), lookup, java.io.Serializable.class));
        assertFalse(observes(TypeInfo.PrimitiveType.fromDescriptor('C'), lookup, Number.class));
    }

    /**
     * The container reads the event type of an observer it loads by reflection as a class type named after a
     * primitive, {@code int}, where the processor reads the primitive type: both match alike, as the component of an
     * array too (BUG-20261117-05).
     */
    @Test
    void aClassTypeNamedAfterAPrimitiveMatchesAsThatPrimitive() {
        var lookup = new IndexLookup(new IndexBuilder().build());
        var reflectedInt = classType("int");
        var reflectedInts = arrayOf(classType("int"), 1);

        assertTrue(observes(reflectedInt, lookup, Number.class));
        assertTrue(observes(reflectedInt, lookup, Integer.class));
        assertFalse(observes(reflectedInt, lookup, Long.class, Plain.class));
        assertTrue(observes(classType("boolean"), lookup, java.io.Serializable.class));
        assertTrue(observes(reflectedInts, lookup, int[].class));
        assertTrue(observes(reflectedInts, lookup, Cloneable.class));
        assertFalse(observes(reflectedInts, lookup, Object[].class, Integer[].class, long[].class));
        assertTrue(observes(arrayOf(classType("int"), 2), lookup, Object[].class));
    }

    /**
     * {@code @Enhancement(withSubtypes = true)} matches a class whose expected type is an interface of a class it
     * extends, or an interface an interface it implements extends: the walk goes through every class and interface
     * above it (BUG-20261117-07).
     */
    @Test
    void anIndexedClassMatchesTheInterfacesOfItsSuperclassesAndOfItsInterfaces() {
        var impl = indexed("com.acme.Impl", "com.acme.Base");
        var other = indexed("com.acme.Other", Object.class.getName(), java.util.Collection.class);
        var lookup = new IndexLookup(new IndexBuilder()
                .add(impl)
                .add(indexed("com.acme.Base", Object.class.getName(), java.util.EventListener.class))
                .add(other)
                .build());

        assertTrue(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {java.util.EventListener.class}, true, impl,
                lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {java.util.EventListener.class}, false, impl,
                lookup, LOADER), "without its subtypes, only the expected type itself");
        assertTrue(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {Plain.class, Iterable.class}, true, other,
                lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {Iterable.class}, false, other, lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {java.util.List.class}, true, other, lookup,
                LOADER));
    }

    /** A supertype neither the index nor the processor describes, one of the JDK, say, is loaded. */
    @Test
    void anInterceptorMatchesTheTypesOfAClassItExtendsThatOnlyLoads() {
        var lookup = new IndexLookup(new IndexBuilder()
                .add(indexed("com.acme.Logger", java.util.AbstractList.class.getName(), java.io.Serializable.class))
                .build());
        var logger = interceptor("com.acme.Logger");

        assertTrue(BceTypeMatcher.matchesInterceptorTypes(new Class<?>[] {Iterable.class}, logger, lookup, LOADER));
        assertTrue(BceTypeMatcher.matchesInterceptorTypes(
                new Class<?>[] {Plain.class, java.io.Serializable.class}, logger, lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesInterceptorTypes(new Class<?>[] {Runnable.class}, logger, lookup, LOADER));
        assertFalse(BceTypeMatcher.matchesInterceptorTypes(new Class<?>[] {Runnable.class},
                interceptor("com.acme.Unknown"), lookup, LOADER), "a class nothing describes and that does not load");
    }

    /** The middle of the indexed hierarchy above; only its name takes part. */
    private static final class Middle {
    }

    @Test
    void beanTypesThatNameNoClassNeverMatch() {
        var bean = new BeanDescriptor(BeanDescriptor.minimal(DotName.of("com.acme.Bean")).id(), DotName.of("com.acme.Bean"),
                BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.TypeVariable("T", List.of()), new TypeInfo.ArrayType(TypeInfo.PrimitiveType.fromDescriptor('I'), 1)),
                Set.of(), null, false, 0, List.of(), null);

        assertFalse(BceTypeMatcher.matchesTypes(new Class<?>[] {Object.class}, bean, LOADER));
    }

    @Test
    void aParameterizedBeanTypeMatchesByItsRawType() {
        var bean = new BeanDescriptor(BeanDescriptor.minimal(DotName.of("com.acme.Bean")).id(), DotName.of("com.acme.Bean"),
                BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.ParameterizedType(DotName.of("java.util.ArrayList"), List.of())),
                Set.of(), null, false, 0, List.of(), null);

        assertTrue(BceTypeMatcher.matchesTypes(new Class<?>[] {java.util.List.class}, bean, LOADER));
    }

    /**
     * A class, built here, whose only field and only constructor name {@code com.acme.Absent}: reading either
     * throws {@link NoClassDefFoundError}.
     */
    private static Class<?> classNamingAnAbsentType() {
        var absent = ClassDesc.of("com.acme.Absent");
        var self = ClassDesc.of("gen.NamesAbsent");
        byte[] bytes = ClassFile.of().build(self, clb -> clb
                .withFlags(AccessFlag.PUBLIC)
                .withField("absent", absent, ClassFile.ACC_PUBLIC)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, absent),
                        ClassFile.ACC_PUBLIC, code -> code
                                .aload(0)
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_()));
        var loader = new ClassLoader(LOADER) {
            Class<?> define() {
                return defineClass("gen.NamesAbsent", bytes, 0, bytes.length);
            }
        };
        return loader.define();
    }
}
