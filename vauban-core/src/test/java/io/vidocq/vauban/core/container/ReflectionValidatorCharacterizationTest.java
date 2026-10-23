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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the definition errors {@link ReflectionValidator} reports, message by message, so that restructuring it keeps
 * what it reports. Messages are compared sorted: the order of {@code getDeclaredMethods()} is not specified.
 */
@DisplayName("ReflectionValidator — the definition errors it reports")
class ReflectionValidatorCharacterizationTest {

    @Stereotype
    @Named("custom")
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface NamedStereotype {
    }

    @NamedStereotype
    static class StereotypeNamedBean {
    }

    @ApplicationScoped
    static class GenericScoped<T> {
    }

    @Dependent
    static class GenericDependent<T> {
    }

    @Dependent
    static class NamedParameters {
        String value;

        @Inject
        NamedParameters(@Named String constructorValue) {
            this.value = constructorValue;
        }

        @Inject
        void init(@Named String initValue) {
            this.value = initValue;
        }

        @Produces
        Integer produce(@Named String producerValue) {
            return producerValue.length();
        }

        void observe(@Observes Object event, @Named String observerValue) {
            this.value = observerValue + event;
        }

        void dispose(@Disposes Integer produced, @Named String disposerValue) {
            this.value = disposerValue + produced;
        }
    }

    @ApplicationScoped
    @SuppressWarnings("java:S1104")
    static class PublicFieldBean {
        public static final String CONSTANT = "constant";
        public String exposed;
    }

    @Typed(Runnable.class)
    static class TypedBean {
        @Produces
        @Typed({Runnable.class, Object.class})
        String typedField = "";

        @Produces
        @Typed(Runnable.class)
        String produceTyped() {
            return typedField;
        }
    }

    @SuppressWarnings({"rawtypes", "java:S3740"})
    static class RawInjection {
        @Inject
        Event rawEvent;
        @Inject
        Instance rawInstance;

        @Inject
        RawInjection(Event event) {
            this.rawEvent = event;
        }

        @Inject
        void init(Instance instance) {
            this.rawInstance = instance;
        }

        void observe(@Observes String event, Event raw) {
            raw.fire(event);
        }

        void dispose(@Disposes String produced, Instance raw, InjectionPoint injectionPoint) {
            this.rawInstance = raw;
            this.rawEvent.fire(produced + injectionPoint);
        }

        @Produces
        String produce(Event raw) {
            return String.valueOf(raw);
        }
    }

    static class ProducerMethods<T> {
        @Produces
        @ApplicationScoped
        T typeVariable(T value) {
            return value;
        }

        @Produces
        List<?> wildcard() {
            return List.of();
        }

        @Produces
        @ApplicationScoped
        @Singleton
        String twoScopes() {
            return "";
        }

        @Produces
        T[] typeVariableArray(T[] values) {
            return values;
        }

        @Produces
        List<?>[] wildcardArray() {
            return new List<?>[0];
        }

        @Inject
        <U> void genericInitializer(U value) {
            // fixture: only the signature matters
        }
    }

    static class ProducerFields<T> {
        @Produces
        @ApplicationScoped
        T typeVariable;

        @Produces
        @ApplicationScoped
        @Singleton
        String twoScopes = "";
    }

    @Stereotype
    @Priority(1)
    @RequestScoped
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface FirstStereotype {
    }

    @Stereotype
    @Priority(2)
    @ApplicationScoped
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface SecondStereotype {
    }

    @FirstStereotype
    @SecondStereotype
    static class ConflictingStereotypes {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Level {
        int value();
    }

    @Stereotype
    @Level(1)
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface LevelOneStereotype {
    }

    @Stereotype
    @Level(2)
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface LevelTwoStereotype {
    }

    @LevelOneStereotype
    @LevelTwoStereotype
    @Dependent
    static class ConflictingBindings {
    }

    private static List<String> errorsOf(Class<?> beanClass) {
        return ReflectionValidator.validateWithReflection(List.of(beanClass)).stream().sorted().toList();
    }

    private static List<String> sorted(String... messages) {
        return Arrays.stream(messages).sorted().toList();
    }

    @Test
    @DisplayName("interfaces are skipped")
    void interfaceIsSkipped() {
        assertEquals(List.of(), errorsOf(Runnable.class));
    }

    @Test
    @DisplayName("a stereotype naming its beans with a value")
    void stereotypeWithNamedValue() {
        assertEquals(List.of("Stereotype " + NamedStereotype.class.getName()
                + " has @Named with non-empty value 'custom'"), errorsOf(StereotypeNamedBean.class));
    }

    @Test
    @DisplayName("a generic bean must be @Dependent")
    void genericBeanScope() {
        assertEquals(List.of("Managed bean " + GenericScoped.class.getName()
                + " has type parameters and is not @Dependent"), errorsOf(GenericScoped.class));
        assertEquals(List.of(), errorsOf(GenericDependent.class));
    }

    @Test
    @DisplayName("@Named without a value on parameters")
    void namedWithoutValueOnParameters() {
        String name = NamedParameters.class.getName();
        assertEquals(sorted(
                "@Named without value on initializer parameter: " + name + ".init",
                "@Named without value on constructor parameter: " + name,
                "@Named without value on producer method parameter: " + name + ".produce",
                "@Named without value on observer/disposer method parameter: " + name + ".observe",
                "@Named without value on observer/disposer method parameter: " + name + ".dispose"),
                errorsOf(NamedParameters.class));
    }

    @Test
    @DisplayName("a normal-scoped bean with a public instance field")
    void normalScopedPublicField() {
        assertEquals(List.of("Normal-scoped bean " + PublicFieldBean.class.getName()
                + " has non-static public field 'exposed'"), errorsOf(PublicFieldBean.class));
    }

    @Test
    @DisplayName("@Typed values that are not bean types")
    void typedValues() {
        String name = TypedBean.class.getName();
        assertEquals(sorted(
                "@Typed value java.lang.Runnable on " + name + " is not a legal bean type (not a supertype)",
                "@Typed value java.lang.Runnable on producer method " + name + ".produceTyped"
                        + " is not a legal bean type",
                "@Typed value java.lang.Runnable on producer field " + name + ".typedField"
                        + " is not a legal bean type"),
                errorsOf(TypedBean.class));
    }

    @Test
    @DisplayName("raw Event and Instance injection points, and InjectionPoint on a disposer")
    void rawInjectionPoints() {
        String name = RawInjection.class.getName();
        assertEquals(sorted(
                "Raw Event type injected at " + name + ".rawEvent — must be parameterized",
                "Raw Instance type injected at " + name + ".rawInstance — must be parameterized",
                "Raw Event type injected at " + name + " constructor param 0 — must be parameterized",
                "Raw Instance type injected at " + name + ".init param 0 — must be parameterized",
                "Raw Event type injected at " + name + ".observe observer param — must be parameterized",
                "Raw Instance type injected at " + name + ".dispose disposer param — must be parameterized",
                "Disposer method " + name + ".dispose must not have InjectionPoint parameter",
                "Raw Event type injected at " + name + ".produce producer param — must be parameterized"),
                errorsOf(RawInjection.class));
    }

    @Test
    @DisplayName("producer methods with illegal types or scopes, and a generic initializer")
    void producerMethods() {
        String name = ProducerMethods.class.getName();
        assertEquals(sorted(
                "Producer " + name + ".typeVariable has type variable return type",
                "Producer method " + name + ".typeVariable has TypeVariable return type and non-@Dependent scope",
                "Producer " + name + ".wildcard has parameterized type with wildcard argument",
                "Producer method " + name + ".wildcard has wildcard type parameter in return type",
                "Producer method " + name + ".twoScopes has multiple scope annotations",
                "Producer " + name + ".typeVariableArray has array type with type variable component",
                "Producer " + name + ".wildcardArray has array of parameterized type with wildcard",
                "Producer method " + name + ".wildcardArray has wildcard type parameter in return type",
                "Initializer method " + name + ".genericInitializer cannot declare type parameters"),
                errorsOf(ProducerMethods.class));
    }

    @Test
    @DisplayName("producer fields with illegal types or scopes")
    void producerFields() {
        String name = ProducerFields.class.getName();
        assertEquals(sorted(
                "Producer " + name + ".typeVariable has type variable return type",
                "Producer field " + name + ".typeVariable has TypeVariable type and non-@Dependent scope",
                "Producer field " + name + ".twoScopes has multiple scope annotations"),
                errorsOf(ProducerFields.class));
    }

    @Test
    @DisplayName("stereotypes with conflicting priorities and scopes")
    void conflictingStereotypes() {
        String name = ConflictingStereotypes.class.getName();
        assertEquals(sorted(
                "Bean " + name + " has conflicting stereotype priorities: [1, 2]",
                "Bean " + name + " has conflicting stereotype scopes: [" + RequestScoped.class + ", "
                        + ApplicationScoped.class + "]"),
                errorsOf(ConflictingStereotypes.class));
    }

    @Test
    @DisplayName("stereotypes with conflicting interceptor binding values")
    void conflictingBindings() {
        assertEquals(List.of("Bean " + ConflictingBindings.class.getName()
                + " has conflicting interceptor binding values for Level"), errorsOf(ConflictingBindings.class));
    }
}
