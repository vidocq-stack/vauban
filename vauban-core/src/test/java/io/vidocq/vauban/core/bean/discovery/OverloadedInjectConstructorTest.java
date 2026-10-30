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

import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The injection points of an {@code @Inject} constructor are typed from that constructor, not from another one with
 * the same number of parameters. Discovery matched the reflected constructor on its arity alone: with an overload
 * {@code (Config, Part)} declared before {@code @Inject (Config, Instance<Part>)}, it typed the second parameter
 * {@code Part}, and two {@code Part} beans failed the deployment as ambiguous, although an {@code Instance} point is
 * never validated (VAU-DISC-003, Grimm's {@code GrimmModelCache}).
 */
@DisplayName("Bean discovery — overloaded @Inject constructor")
class OverloadedInjectConstructorTest {

    public interface Part {
        String id();
    }

    @Dependent
    public static class First implements Part {
        @Override
        public String id() {
            return "first";
        }
    }

    @Dependent
    public static class Second implements Part {
        @Override
        public String id() {
            return "second";
        }
    }

    @Dependent
    public static class Config {
    }

    @Dependent
    public static class Holder {
        final List<String> ids;

        /** Same arity as the injected constructor, declared first: the one discovery used to pick. */
        @SuppressWarnings("java:S1172") // the signature is the case under test
        public Holder(Config config, Part part) {
            this.ids = List.of(part.id());
        }

        @Inject
        @SuppressWarnings("java:S1172") // the signature is the case under test
        public Holder(Config config, Instance<Part> parts) {
            this.ids = parts.stream().map(Part::id).sorted().toList();
        }
    }

    /** An executable of {@code types}, as the indexer describes it. */
    private static MethodInfo indexed(TypeInfo... types) {
        var parameters = new java.util.ArrayList<ParameterInfo>();
        for (int i = 0; i < types.length; i++) {
            parameters.add(new ParameterInfo("p" + i, types[i], List.of()));
        }
        return new MethodInfo("m", new TypeInfo.VoidType(), parameters, List.of(), 0, List.of());
    }

    private static TypeInfo.ClassType type(Class<?> c) {
        return new TypeInfo.ClassType(DotName.of(c.getName()));
    }

    @SuppressWarnings("java:S1172") // the signatures are the cases under test
    static <T extends Number, U> void shapes(int count, String[][] grid, List<String> names, T number, U anything) {
        // Never called: only its parameter types are read.
    }

    @Test
    @DisplayName("every shape of parameter type is compared by its erasure")
    void parametersAreComparedByTheirErasure() throws NoSuchMethodException {
        Class<?>[] reflected = OverloadedInjectConstructorTest.class.getDeclaredMethod("shapes",
                int.class, String[][].class, List.class, Number.class, Object.class).getParameterTypes();
        var number = new TypeInfo.TypeVariable("T", List.of(type(Number.class)));
        var unbounded = new TypeInfo.TypeVariable("U", List.of());

        assertTrue(BeanDiscovery.sameErasedParameters(reflected, indexed(
                new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT),
                new TypeInfo.ArrayType(type(String.class), 2),
                new TypeInfo.ParameterizedType(DotName.of(List.class.getName()), List.of(type(String.class))),
                number, unbounded)));
        assertFalse(BeanDiscovery.sameErasedParameters(reflected, indexed(
                new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.LONG),
                new TypeInfo.ArrayType(type(String.class), 2),
                type(List.class), number, unbounded)), "a primitive of another kind");
        assertFalse(BeanDiscovery.sameErasedParameters(reflected, indexed(
                new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT),
                new TypeInfo.ArrayType(type(String.class), 1),
                type(List.class), number, unbounded)), "an array of another dimension");
        assertFalse(BeanDiscovery.sameErasedParameters(reflected, indexed(type(String.class))), "another arity");
        assertTrue(BeanDiscovery.sameErasedParameters(new Class<?>[] {Object.class},
                indexed(new TypeInfo.WildcardType(null, null))), "a type it cannot tell is not held against it");
    }

    @Test
    @DisplayName("an Instance parameter of the @Inject constructor is not validated as its element type")
    void theInjectConstructorTypesItsInjectionPoints() {
        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(First.class, Second.class, Config.class, Holder.class)
                .initialize()) {
            assertEquals(List.of("first", "second"), container.select(Holder.class).get().ids);
        }
    }
}
