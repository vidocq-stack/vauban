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
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for VAU-BCE-001 — the BCE pipeline running through
 * {@link SeContainerInitializer#addBeanClasses(Class[])} for an extension
 * that uses {@code @Registration} (with {@code BeanInfo.injectionPoints()})
 * and {@code @Synthesis} (with parameterized bean types and qualifier members).
 *
 * <p>This is the runtime API surface used by Ravel's {@code ConfigCdiExtension}
 * (MicroProfile Config 3.1) — historically blocked by four cumulative defects:
 * <ol>
 *   <li>{@code VaubanBceBeanInfo.injectionPoints()} stubbed to {@code List.of()};</li>
 *   <li>{@code VaubanAnnotationInfo} not overriding {@code name()} (the spec's
 *       default delegated to {@code declaration()} which required the
 *       annotation class to be in the index);</li>
 *   <li>{@code VaubanClassType.declaration()} crashing on JDK / external types
 *       absent from the index (e.g. {@code java.lang.String});</li>
 *   <li>{@code VaubanSyntheticBeanBuilder.type(Type)} being a no-op — synthetic
 *       beans declared with a parameterized type ({@code Optional<String>},
 *       {@code List<T>}, …) silently dropped that type.</li>
 * </ol>
 */
@DisplayName("VAU-BCE-001 — @Registration + @Synthesis through SeContainerInitializer")
class BceRegistrationSynthesisTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
    @jakarta.inject.Qualifier
    public @interface Tagged {
        String value() default "";
    }

    @Dependent
    public static class TargetBean {
        @Inject @Tagged("scalar")
        public String scalar;

        @Inject @Tagged("optional")
        public Optional<String> opt;
    }

    /** Synthetic creator producing a constant value the test can assert on. */
    public static class ConstantStringCreator implements SyntheticBeanCreator<String> {
        @Override
        public String create(jakarta.enterprise.inject.Instance<Object> lookup,
                              jakarta.enterprise.inject.build.compatible.spi.Parameters params) {
            return "VAU-BCE-001-OK";
        }
    }

    public static class ConstantOptionalCreator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(jakarta.enterprise.inject.Instance<Object> lookup,
                              jakarta.enterprise.inject.build.compatible.spi.Parameters params) {
            return Optional.of("VAU-BCE-001-OPT");
        }
    }

    /**
     * Records what the BCE saw during {@code @Registration} so the test can
     * assert that injection points were exposed with their full type and
     * qualifier-with-members information.
     */
    static final class Recorded {
        static final AtomicReference<List<RecordedIp>> ips = new AtomicReference<>(List.of());
    }

    record RecordedIp(String typeString, String firstQualifierName, String qualifierValueMember) {}

    public static class TestBce implements BuildCompatibleExtension {

        @Registration(types = Object.class)
        public void recordInjectionPoints(BeanInfo bean) {
            if (!bean.declaringClass().name().equals(TargetBean.class.getName())) return;
            var snapshot = new java.util.ArrayList<RecordedIp>();
            for (var ip : bean.injectionPoints()) {
                AnnotationInfo first = null;
                for (var q : ip.qualifiers()) {
                    if (q.name().equals(Tagged.class.getName())) {
                        first = q;
                        break;
                    }
                }
                String value = null;
                if (first != null && first.hasMember("value")) {
                    AnnotationMember m = first.member("value");
                    if (m != null && m.isString()) value = m.asString();
                }
                snapshot.add(new RecordedIp(
                        ip.type().toString(),
                        first != null ? first.name() : null,
                        value));
            }
            Recorded.ips.set(List.copyOf(snapshot));
        }

        @Synthesis
        public void synthesize(SyntheticComponents components,
                                jakarta.enterprise.inject.build.compatible.spi.Types types) {
            // Scalar @Tagged("scalar") String — exercises type(Class<?>) path
            components.addBean(String.class)
                    .type(String.class)
                    .qualifier(taggedLiteral("scalar"))
                    .scope(Dependent.class)
                    .createWith(ConstantStringCreator.class);

            // Optional<String> with @Tagged("optional") — exercises type(Type) path
            components.addBean(Object.class)
                    .type(types.parameterized(Optional.class, types.of(String.class)))
                    .qualifier(taggedLiteral("optional"))
                    .scope(Dependent.class)
                    .createWith(ConstantOptionalCreator.class);
        }

        private static Tagged taggedLiteral(String value) {
            return new Tagged() {
                @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return Tagged.class; }
                @Override public String value() { return value; }
                @Override public int hashCode() { return ("value".hashCode() * 127) ^ value.hashCode(); }
                @Override public boolean equals(Object o) {
                    return o instanceof Tagged t && value.equals(t.value());
                }
                @Override public String toString() { return "@Tagged(\"" + value + "\")"; }
            };
        }
    }

    @BeforeEach
    void resetRecording() {
        Recorded.ips.set(List.of());
    }

    @AfterEach
    void cleanup() {
        // Nothing — SeContainer is closed inside each test
    }

    @Test
    @DisplayName("@Registration sees BeanInfo.injectionPoints() with type + qualifier members")
    void registrationExposesInjectionPoints() {
        var initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(TargetBean.class, TestBce.class);

        try (SeContainer container = initializer.initialize()) {
            assertNotNull(container);
        }

        var ips = Recorded.ips.get();
        assertEquals(2, ips.size(), "BCE @Registration must expose all 2 injection points of TargetBean");

        // Find each by type
        RecordedIp scalarIp = ips.stream().filter(i -> i.typeString.contains("String") && !i.typeString.contains("Optional")).findFirst().orElseThrow();
        RecordedIp optIp = ips.stream().filter(i -> i.typeString.contains("Optional")).findFirst().orElseThrow();

        assertEquals(Tagged.class.getName(), scalarIp.firstQualifierName, "qualifier FQN must be readable from name() without forcing the annotation class into the index");
        assertEquals("scalar", scalarIp.qualifierValueMember, "qualifier @Tagged member 'value' must be preserved");
        assertEquals("optional", optIp.qualifierValueMember, "parameterized Optional<String> IP must also expose qualifier members");
    }

    @Test
    @DisplayName("@Synthesis-registered SyntheticBean<String> resolves @Tagged(\"scalar\") String")
    void syntheticScalarBeanResolves() {
        var initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(TargetBean.class, TestBce.class);

        try (SeContainer container = initializer.initialize()) {
            TargetBean bean = container.select(TargetBean.class).get();
            assertNotNull(bean.scalar, "scalar field must be injected by the synthetic bean");
            assertEquals("VAU-BCE-001-OK", bean.scalar);
        }
    }

    @Test
    @DisplayName("@Synthesis-registered SyntheticBean<Optional<String>> resolves Optional<String>")
    void syntheticParameterizedBeanResolves() {
        var initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(TargetBean.class, TestBce.class);

        try (SeContainer container = initializer.initialize()) {
            TargetBean bean = container.select(TargetBean.class).get();
            assertNotNull(bean.opt, "Optional<String> field must be injected by the synthetic bean (parameterized type)");
            assertTrue(bean.opt.isPresent(), "synthetic creator returns Optional.of(...)");
            assertEquals("VAU-BCE-001-OPT", bean.opt.get());
        }
    }
}
