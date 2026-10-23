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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-BCE-007 (vauban#207): {@code ClassInfo.typeParameters()} gives the type parameters a class declares, with their
 * bounds, to a build compatible extension, in {@code @Enhancement} and in {@code @Registration}.
 */
@DisplayName("VAU-BCE-007 - ClassInfo.typeParameters() in build compatible extensions")
class BceClassTypeParametersTest {

    /** The type parameters of {@link Holder}; {@code T} declares no bound, so its single bound is {@code Object}. */
    private static final String EXPECTED = "T extends java.lang.Object; "
            + "N extends java.lang.Number & java.lang.Comparable<N>; "
            + "L extends java.util.List<N>";

    @Dependent
    public static class Holder<T, N extends Number & Comparable<N>, L extends List<N>> {
    }

    @Dependent
    public static class Plain {
    }

    static final AtomicReference<String> enhancementSaw = new AtomicReference<>();
    static final AtomicReference<String> registrationSaw = new AtomicReference<>();
    static final AtomicReference<String> plainSaw = new AtomicReference<>();
    static final AtomicReference<Boolean> allTypeVariables = new AtomicReference<>();

    public static class TypeParametersExtension implements BuildCompatibleExtension {

        @Enhancement(types = Holder.class)
        public void enhance(ClassInfo clazz) {
            enhancementSaw.set(describe(clazz.typeParameters()));
            allTypeVariables.set(clazz.typeParameters().stream().allMatch(Type::isTypeVariable));
        }

        @Enhancement(types = Plain.class)
        public void enhancePlain(ClassInfo clazz) {
            plainSaw.set(describe(clazz.typeParameters()));
        }

        @Registration(types = Object.class)
        public void register(BeanInfo bean) {
            if (bean.declaringClass().name().equals(Holder.class.getName())) {
                registrationSaw.set(describe(bean.declaringClass().typeParameters()));
            }
        }
    }

    @BeforeEach
    void reset() {
        enhancementSaw.set(null);
        registrationSaw.set(null);
        plainSaw.set(null);
        allTypeVariables.set(null);
    }

    @Test
    @DisplayName("@Enhancement and @Registration see the declared type parameters and their bounds")
    void typeParametersAreExposedWithTheirBounds() {
        try (var _ = SeContainerInitializer.newInstance()
                .addBeanClasses(Holder.class, Plain.class, TypeParametersExtension.class)
                .initialize()) {
            assertEquals(EXPECTED, enhancementSaw.get());
            assertEquals(EXPECTED, registrationSaw.get());
            assertTrue(allTypeVariables.get());
            assertEquals("", plainSaw.get());
        }
    }

    private static String describe(List<TypeVariable> typeParameters) {
        return typeParameters.stream()
                .map(tv -> tv.name() + " extends " + tv.bounds().stream()
                        .map(BceClassTypeParametersTest::describe)
                        .collect(Collectors.joining(" & ")))
                .collect(Collectors.joining("; "));
    }

    private static String describe(Type type) {
        if (type.isClass()) {
            return type.asClass().declaration().name();
        }
        if (type.isParameterizedType()) {
            var parameterized = type.asParameterizedType();
            return parameterized.declaration().name() + parameterized.typeArguments().stream()
                    .map(BceClassTypeParametersTest::describe)
                    .collect(Collectors.joining(", ", "<", ">"));
        }
        if (type.isTypeVariable()) {
            return type.asTypeVariable().name();
        }
        return type.toString();
    }
}
