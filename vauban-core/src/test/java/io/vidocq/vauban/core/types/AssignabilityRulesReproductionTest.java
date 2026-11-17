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
package io.vidocq.vauban.core.types;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo.*;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignabilityRulesReproductionTest {

    public interface Foo<T> {
    }

    @Dependent
    public static class RawFooFactory {
        @Produces
        @SuppressWarnings("rawtypes")
        Foo foo() {
            return new Foo() {
            };
        }
    }

    @Dependent
    public static class FooOfIntegerUser {
        @Inject
        Foo<Integer> foo;
    }

    private AssignabilityRules rules;

    @BeforeEach
    void setUp() {
        var builder = new IndexBuilder();
        builder.add(AssignabilityRulesTest.makeClass("java.lang.Object", null));
        builder.add(AssignabilityRulesTest.makeClass("java.lang.Number", "java.lang.Object"));
        builder.add(AssignabilityRulesTest.makeClass("java.lang.Integer", "java.lang.Number"));
        rules = new AssignabilityRules(builder.build());
    }

    @Test
    @DisplayName("List<? extends Integer> must be assignable to List<? extends Number>")
    void wildcardToWildcardAssignability() {
        var listExtendsInteger = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(new WildcardType(new ClassType(DotName.of("java.lang.Integer")), null)));
        
        var listExtendsNumber = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(new WildcardType(new ClassType(DotName.of("java.lang.Number")), null)));

        // BUG: isTypeArgumentAssignable does not handle Wildcard vs Wildcard
        assertTrue(rules.isAssignable(listExtendsInteger, listExtendsNumber),
                "List<? extends Integer> should be assignable to List<? extends Number>");
    }

    @Test
    @DisplayName("List<T extends Integer> must be assignable to List<? extends Number>")
    void typeVariableToWildcardAssignability() {
        var typeVarT = new TypeVariable("T", List.of(new ClassType(DotName.of("java.lang.Integer"))));
        var listT = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(typeVarT));

        var listExtendsNumber = new ParameterizedType(
                DotName.of("java.util.List"),
                List.of(new WildcardType(new ClassType(DotName.of("java.lang.Number")), null)));

        // BUG: isTypeArgumentAssignable does not handle TypeVariable vs Wildcard
        assertTrue(rules.isAssignable(listT, listExtendsNumber),
                "List<T extends Integer> should be assignable to List<? extends Number>");
    }

    /**
     * A raw bean type is assignable to a parameterized required type only when each type argument of the required type
     * is {@code Object} or an unbounded type variable (CDI 4.1, 5.2.4): a raw {@code Foo} is no {@code Foo<Integer>}.
     * {@code BeanManager.getBeans} follows that rule; the resolution of an injection point took a raw bean type for any
     * parameterization of it, and resolved {@code @Inject Foo<Integer>} to the producer of a raw {@code Foo}
     * (BUG-20261117-16).
     */
    @Disabled("BUG-20261117-16: OPEN, a raw bean type matches every parameterization in the resolution of an injection"
            + " point")
    @Test
    @DisplayName("a raw bean type does not satisfy an injection point of a parameterization other than Object")
    void aRawBeanTypeDoesNotSatisfyAnotherParameterization() {
        try (var container = VaubanContainer.builder().addBeanClass(RawFooFactory.class).build()) {
            assertEquals(Set.of(), container.getBeanManager().getBeans(new TypeLiteral<Foo<Integer>>() { }.getType()),
                    "BeanManager.getBeans");
        }
        var builder = VaubanContainer.builder().addBeanClass(RawFooFactory.class).addBeanClass(FooOfIntegerUser.class);

        assertThrows(RuntimeException.class, builder::build, "the injection point resolved");
    }
}
