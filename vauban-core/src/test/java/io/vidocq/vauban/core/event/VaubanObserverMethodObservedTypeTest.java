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
}
