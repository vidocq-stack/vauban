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
package io.vidocq.vauban.core.event;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The observed type and bean class a {@link VaubanObserverMethod} reports from its descriptor's index types. */
class VaubanObserverMethodObservedTypeTest {

    private static final String MISSING = "com.acme.Missing";
    private static final TypeInfo NUMBER = new TypeInfo.ClassType(DotName.of("java.lang.Number"));
    private static final TypeInfo OBJECT = new TypeInfo.ClassType(DotName.of("java.lang.Object"));

    private static VaubanObserverMethod<Object> observing(String declaringClass, TypeInfo eventType) {
        var descriptor = new ObserverDescriptor(DotName.of(declaringClass), "on", eventType, List.of(), false, 0);
        return new VaubanObserverMethod<>(descriptor, null, null,
                VaubanObserverMethodObservedTypeTest.class.getClassLoader());
    }

    private static Type observed(TypeInfo eventType) {
        return observing(VaubanObserverMethodObservedTypeTest.class.getName(), eventType).getObservedType();
    }

    @Test
    void anUnloadableDeclaringClassIsReportedAsObject() {
        assertEquals(Object.class, observing(MISSING, NUMBER).getBeanClass());
    }

    @Test
    void unloadableObservedTypesAreReportedAsObject() {
        assertEquals(Object.class, observed(new TypeInfo.ClassType(DotName.of(MISSING))));
        assertEquals(Object.class, observed(new TypeInfo.ParameterizedType(DotName.of(MISSING), List.of(NUMBER))));
    }

    @Test
    void aParameterizedObservedTypeResolvesItsArguments() {
        var type = assertInstanceOf(ParameterizedType.class,
                observed(new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(NUMBER))));

        assertEquals(List.class, type.getRawType());
        assertArrayEquals(new Type[] {Number.class}, type.getActualTypeArguments());
    }

    @Test
    void aTypeVariableKeepsItsClassBoundsOrObject() {
        var bounded = assertInstanceOf(TypeVariable.class, observed(new TypeInfo.TypeVariable("T", List.of(NUMBER))));
        var free = assertInstanceOf(TypeVariable.class, observed(new TypeInfo.TypeVariable("U", List.of(OBJECT))));

        assertEquals("T", bounded.getName());
        assertArrayEquals(new Type[] {Number.class}, bounded.getBounds());
        assertArrayEquals(new Type[] {Object.class}, free.getBounds());
    }

    @Test
    void aWildcardDefaultsItsUpperBoundToObjectAndHasALowerBoundOnlyWhenGiven() {
        var lower = assertInstanceOf(WildcardType.class, observed(new TypeInfo.WildcardType(null, NUMBER)));
        var upper = assertInstanceOf(WildcardType.class, observed(new TypeInfo.WildcardType(NUMBER, null)));

        assertArrayEquals(new Type[] {Object.class}, lower.getUpperBounds());
        assertArrayEquals(new Type[] {Number.class}, lower.getLowerBounds());
        assertArrayEquals(new Type[] {Number.class}, upper.getUpperBounds());
        assertArrayEquals(new Type[0], upper.getLowerBounds());
    }

    @Test
    void anArrayIsAClassArrayOrAGenericArray() {
        assertEquals(Number[].class, observed(new TypeInfo.ArrayType(NUMBER, 1)));

        var generic = assertInstanceOf(GenericArrayType.class,
                observed(new TypeInfo.ArrayType(new TypeInfo.TypeVariable("T", List.of()), 1)));
        assertInstanceOf(TypeVariable.class, generic.getGenericComponentType());
    }

    /** A generic class and its inner class, whose JDK types the observed types are compared with. */
    @SuppressWarnings("java:S2326") // the type parameters are what the tests compare
    static class Outer<O> {
        class Inner<I> {
        }
    }

    /** The JDK's own types of these fields are what the observed types must equal. */
    @SuppressWarnings("unused")
    static java.util.Map.Entry<String, Integer> entry;
    @SuppressWarnings("unused")
    static Outer<String>.Inner<Integer> inner;

    private static Type jdkType(String field) throws NoSuchFieldException {
        return VaubanObserverMethodObservedTypeTest.class.getDeclaredField(field).getGenericType();
    }

    /**
     * BUG-20261118-46: the owner of a member type is the JDK's, the declaring class {@code Map} for
     * {@code Map.Entry<String, Integer>}, so the observed type equals the JDK's and hashes alike; it was {@code null}.
     */
    @Test
    void aStaticMemberTypeHasItsDeclaringClassAsOwner() throws NoSuchFieldException {
        var string = new TypeInfo.ClassType(DotName.of("java.lang.String"));
        var integer = new TypeInfo.ClassType(DotName.of("java.lang.Integer"));
        var type = observed(new TypeInfo.ParameterizedType(DotName.of("java.util.Map$Entry"), List.of(string, integer)));

        assertEquals(jdkType("entry"), type);
        assertEquals(type, jdkType("entry"));
        assertEquals(jdkType("entry").hashCode(), type.hashCode());
    }

    /** BUG-20261117-21: the owner of an inner class of a generic class is its parameterized enclosing type. */
    @Test
    void anInnerClassOfAGenericClassHasItsEnclosingTypeAsOwner() throws NoSuchFieldException {
        var string = new TypeInfo.ClassType(DotName.of("java.lang.String"));
        var integer = new TypeInfo.ClassType(DotName.of("java.lang.Integer"));
        var owner = new TypeInfo.ParameterizedType(DotName.of(Outer.class.getName()), List.of(string));
        var type = observed(new TypeInfo.ParameterizedType(DotName.of(Outer.Inner.class.getName()), List.of(integer),
                owner));

        assertEquals(jdkType("inner"), type);
        assertEquals(type, jdkType("inner"));
        assertEquals(jdkType("inner").hashCode(), type.hashCode());
    }
}
