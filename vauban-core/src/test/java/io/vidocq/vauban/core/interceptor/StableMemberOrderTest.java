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
package io.vidocq.vauban.core.interceptor;

import io.vidocq.vauban.core.interceptor.fixtures.OrderedBean;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The run-time generators list a bean's methods in a documented order, not in reflection order,
 * which the JDK leaves unspecified and HotSpot makes depend on what the JVM loaded before: the
 * bytes {@code vauban:generate} writes must not change from one build to the next
 * (BUG-20261007-02). Each group of methods is sorted by name, then parameter types.
 */
@DisplayName("Generated subclasses and proxies list methods in a stable order")
class StableMemberOrderTest {

    private static final List<String> DECLARED = List.of("soAlpha()", "soCharlie(java.lang.String)", "soEcho()",
            "soGolf()", "soIndia()", "soKilo()", "soMike()", "soMike(int)", "soSierra()", "soXray()", "soZulu()");

    /**
     * Loads {@code OrderedBeanPrimer} before the bean, by name so that nothing else loads the bean
     * first: HotSpot lists a class's methods in the order of their name symbols in memory, so on
     * this load history reflection lists the bean's methods in reverse name order.
     */
    @BeforeAll
    static void primeTheLoadHistory() throws ClassNotFoundException {
        Class.forName("io.vidocq.vauban.core.interceptor.fixtures.OrderedBeanPrimer", false,
                StableMemberOrderTest.class.getClassLoader());
    }

    @Test
    @DisplayName("the intercepted subclass: declared, then inherited public, then inherited non-public methods")
    void interceptedSubclassOrder() {
        var expected = new ArrayList<>(DECLARED);
        expected.addAll(List.of("soBravo()", "soHotel(int)", "soYankee()", "soDelta()", "soPapa()"));

        var shape = InterceptorSubclassGenerator.fromClass(OrderedBean.class);
        assertEquals(expected, shape.methods().stream()
                .map(m -> key(m.name(), m.params().stream().map(TypeRef::sourceName).toList()))
                .toList());

        // The class file lists its overrides in that order too. (No method name of the fixtures
        // appears alone as a constant here: loading this class would create its symbol first.)
        var bytes = InterceptorSubclassGenerator.generate(OrderedBean.class).bytecode();
        var names = expected.stream().map(StableMemberOrderTest::nameOf).collect(Collectors.toSet());
        assertEquals(collapse(expected.stream().map(StableMemberOrderTest::nameOf).toList()),
                methodNamesIn(bytes, names));
    }

    @Test
    @DisplayName("the client proxy: the bean's methods, then each superclass's, each group sorted")
    void clientProxyOrder() {
        var expected = new ArrayList<>(DECLARED);
        expected.addAll(List.of("soBravo()", "soDelta()", "soHotel(int)", "soPapa()", "soYankee()"));
        var names = expected.stream().map(StableMemberOrderTest::nameOf).collect(Collectors.toSet());

        var shape = RuntimeClientProxyGenerator.shapeOf(OrderedBean.class);
        assertEquals(expected, shape.methods().stream()
                .filter(m -> names.contains(m.name()))
                .map(m -> key(m.name(), m.params().stream().map(TypeRef::sourceName).toList()))
                .toList());
    }

    private static String nameOf(String key) {
        return key.substring(0, key.indexOf('('));
    }

    /** {@code names} without consecutive repeats: an overloaded name appears once per run. */
    private static List<String> collapse(List<String> names) {
        var result = new ArrayList<String>();
        for (var name : names) {
            if (result.isEmpty() || !result.getLast().equals(name)) result.add(name);
        }
        return result;
    }

    private static String key(String name, List<String> params) {
        return name + "(" + String.join(",", params) + ")";
    }

    /** The names of the methods of {@code bytes} in {@code names}, in class-file order, once each. */
    private static List<String> methodNamesIn(byte[] bytes, Set<String> names) {
        var result = new ArrayList<String>();
        var previous = "";
        for (var method : ClassFile.of().parse(bytes).methods()) {
            var name = method.methodName().stringValue();
            if (names.contains(name) && !name.equals(previous)) result.add(name);
            if (names.contains(name)) previous = name;
        }
        return result;
    }
}
