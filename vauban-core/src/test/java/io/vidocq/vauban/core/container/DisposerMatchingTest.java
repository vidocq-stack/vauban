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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.DisposerDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.bean.resolution.QualifierMatcher;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which producer a disposer method disposes, and the definition errors of disposer methods, checked on descriptors
 * alone: the disposed type against the producer's bean types, the disposed parameter's qualifiers against the
 * producer member's, and the {@code Bean} parameter a disposer may declare.
 */
@DisplayName("DisposerInvoker: matching a disposer to its producer, and disposer definition errors")
class DisposerMatchingTest {

    private static final String MISSING_CLASS = "io.vidocq.vauban.core.container.NoSuchShop";
    private static final String DISPOSE = "dispose";

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Flavor {
        String value();
    }

    public static class Candy {
    }

    public static class Lollipop extends Candy {
    }

    public static class Shop {

        @Produces
        @Flavor("sweet")
        public Candy sweet() {
            return new Candy();
        }

        @Produces
        @Flavor("sweet")
        public Candy stock = new Candy();

        public void dispose(@Disposes @Flavor("sweet") Candy candy) {
            // disposes the sweet candy
        }

        public void disposeSour(@Disposes @Flavor("sour") Candy candy) {
            // disposes a sour candy
        }

        public void disposeDefault(@Disposes @Default Candy candy) {
            // disposes the default candy
        }

        public void disposeAny(@Disposes @Any Candy candy) {
            // disposes any candy
        }

        public void disposeWithBean(@Disposes Candy candy, Bean<?> bean) {
            // receives the bean of the disposed instance
        }

        public void disposeWithTypedBean(@Disposes Candy candy, Bean<Candy> bean) {
            // a concrete Bean parameterization: a definition error
        }

        @SuppressWarnings("rawtypes")
        public void disposeWithRawBean(@Disposes Candy candy, Bean bean) {
            // a raw Bean parameter
        }
    }

    private static final DisposerInvoker INVOKER = new DisposerInvoker(null, null);

    private static TypeInfo cls(Class<?> type) {
        return cls(type.getName());
    }

    private static TypeInfo cls(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    private static DisposerDescriptor disposer(String declaringClass, String method, TypeInfo disposedType) {
        return new DisposerDescriptor(DotName.of(declaringClass), method, disposedType, Set.of(), 0, List.of());
    }

    private static DisposerDescriptor shopDisposer(String method) {
        return disposer(Shop.class.getName(), method, cls(Candy.class));
    }

    private static BeanDescriptor producer(BeanDescriptor.BeanKind kind, String id, String beanClass, TypeInfo... types) {
        return new BeanDescriptor(new BeanId(id), DotName.of(beanClass), kind, Set.of(types),
                Set.of(QualifierInstance.ANY), ScopeInfo.DEPENDENT, false, 0, List.of(), null);
    }

    private static BeanDescriptor sweetMethod() {
        return producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, Shop.class.getName() + "#sweet", Shop.class.getName(),
                cls(Candy.class));
    }

    @Test
    @DisplayName("a disposer matches a producer whose bean type has the disposed class name or is assignable to it")
    void disposedTypeMatching() {
        var lollipops = producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, "p#l", Shop.class.getName(), cls(Lollipop.class));
        var listOfCandies = producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, "p#c", Shop.class.getName(),
                new TypeInfo.ParameterizedType(DotName.of(java.util.ArrayList.class.getName()), List.of(cls(Candy.class))));

        assertTrue(INVOKER.matchesDisposerType(sweetMethod(), shopDisposer(DISPOSE)));
        assertTrue(INVOKER.matchesDisposerType(lollipops, shopDisposer(DISPOSE)), "a Lollipop is a Candy");
        assertTrue(INVOKER.matchesDisposerType(listOfCandies,
                disposer(Shop.class.getName(), DISPOSE, cls(java.util.List.class))), "an ArrayList is a List");
    }

    @Test
    @DisplayName("a disposer does not match a producer of an unrelated, unloadable or variable type")
    void disposedTypeMismatch() {
        var variable = producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, "p#t", Shop.class.getName(),
                new TypeInfo.TypeVariable("T", List.of()));
        var parameterizedDisposed = disposer(Shop.class.getName(), DISPOSE,
                new TypeInfo.ParameterizedType(DotName.of(java.util.List.class.getName()), List.of(cls(Candy.class))));

        assertFalse(INVOKER.matchesDisposerType(variable, shopDisposer(DISPOSE)));
        assertFalse(INVOKER.matchesDisposerType(sweetMethod(), disposer(Shop.class.getName(), DISPOSE, cls(MISSING_CLASS))));
        assertFalse(INVOKER.matchesDisposerType(sweetMethod(), parameterizedDisposed));
    }

    @Test
    @DisplayName("an explicit @Any on the disposed parameter is seen; a missing method or class reads as none")
    void explicitAny() {
        assertTrue(INVOKER.hasExplicitAnyOnDisposerParam(shopDisposer("disposeAny")));
        assertFalse(INVOKER.hasExplicitAnyOnDisposerParam(shopDisposer(DISPOSE)));
        assertFalse(INVOKER.hasExplicitAnyOnDisposerParam(shopDisposer("absent")));
        assertFalse(INVOKER.hasExplicitAnyOnDisposerParam(disposer(MISSING_CLASS, DISPOSE, cls(Candy.class))));
    }

    @Test
    @DisplayName("the disposed parameter's qualifier values must equal the producer method's or field's")
    void qualifierValues() {
        var field = producer(BeanDescriptor.BeanKind.PRODUCER_FIELD, Shop.class.getName() + ".stock",
                Shop.class.getName(), cls(Candy.class));

        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(sweetMethod(), shopDisposer(DISPOSE)));
        assertFalse(INVOKER.verifyDisposerQualifiersViaReflection(sweetMethod(), shopDisposer("disposeSour")));
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(field, shopDisposer(DISPOSE)));
        assertFalse(INVOKER.verifyDisposerQualifiersViaReflection(field, shopDisposer("disposeSour")));
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(sweetMethod(), shopDisposer("disposeDefault")),
                "@Default is not an explicit qualifier to compare");
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(sweetMethod(), shopDisposer("disposeAny")),
                "@Any is not an explicit qualifier to compare");
    }

    @Test
    @DisplayName("qualifiers that cannot be compared are taken as matching")
    void uncomparableQualifiers() {
        var unknownField = producer(BeanDescriptor.BeanKind.PRODUCER_FIELD, Shop.class.getName() + ".absent",
                Shop.class.getName(), cls(Candy.class));
        var managed = producer(BeanDescriptor.BeanKind.MANAGED, Shop.class.getName(), Shop.class.getName(), cls(Shop.class));
        var unknownMethod = producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, Shop.class.getName() + "#absent",
                Shop.class.getName(), cls(Candy.class));
        var missingClass = producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, MISSING_CLASS + "#sweet", MISSING_CLASS,
                cls(Candy.class));

        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(unknownField, shopDisposer("disposeSour")));
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(managed, shopDisposer("disposeSour")));
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(unknownMethod, shopDisposer("disposeSour")));
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(sweetMethod(), shopDisposer("absent")));
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(missingClass, shopDisposer("disposeSour")));
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(
                producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, "sweet", Shop.class.getName(), cls(Candy.class)),
                shopDisposer("disposeSour")), "a producer method id without its class");
        assertTrue(INVOKER.verifyDisposerQualifiersViaReflection(
                producer(BeanDescriptor.BeanKind.PRODUCER_FIELD, "stock", Shop.class.getName(), cls(Candy.class)),
                shopDisposer("disposeSour")), "a producer field id without its class");
    }

    @Test
    @DisplayName("a disposer declared in a managed bean with no matching producer is a definition error")
    void disposerWithoutProducer() {
        var managed = producer(BeanDescriptor.BeanKind.MANAGED, Shop.class.getName(), Shop.class.getName(), cls(Shop.class));
        var orphan = disposer(Shop.class.getName(), DISPOSE, cls(Lollipop.class));
        var disposers = List.of(orphan);
        var descriptors = List.of(sweetMethod(), managed);

        var thrown = assertThrows(DefinitionException.class,
                () -> DisposerInvoker.validateDisposerDefinitions(disposers, descriptors));

        assertTrue(thrown.getMessage().contains("no matching producer found"), thrown.getMessage());
    }

    @Test
    @DisplayName("a producer declared in the disposer's class, or a disposer outside any managed bean, is accepted")
    void acceptedDisposerDefinitions() {
        var inherited = producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, Shop.class.getName() + "#sweet",
                "io.vidocq.vauban.core.container.SubShop", cls(Candy.class));
        var orphan = disposer(MISSING_CLASS, DISPOSE, cls(Lollipop.class));

        assertDoesNotThrow(() -> DisposerInvoker.validateDisposerDefinitions(List.of(shopDisposer(DISPOSE)), List.of(inherited)));
        assertDoesNotThrow(() -> DisposerInvoker.validateDisposerDefinitions(List.of(orphan), List.of(inherited)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disposeWithBean", "disposeWithRawBean", "absent"})
    @DisplayName("a disposer with a Bean<?> or raw Bean parameter, or one that cannot be read, passes parameter validation")
    void acceptedBeanParameters(String method) {
        var disposers = List.of(shopDisposer(method), disposer(MISSING_CLASS, DISPOSE, cls(Candy.class)));

        assertDoesNotThrow(() -> validateParameters(disposers));
    }

    @Test
    @DisplayName("a disposer with a concretely parameterized Bean parameter is a definition error")
    void typedBeanParameter() {
        var disposers = List.of(shopDisposer("disposeWithTypedBean"));

        var thrown = assertThrows(DefinitionException.class, () -> validateParameters(disposers));

        assertTrue(thrown.getMessage().contains("Bean<?>"), thrown.getMessage());
    }

    private static void validateParameters(List<DisposerDescriptor> disposers) {
        DisposerInvoker.validateDisposerParameters(disposers, List.of(sweetMethod()), new IndexBuilder().build(),
                QualifierMatcher.standalone());
    }

    @Test
    @DisplayName("a disposer matches a producer type by class name, never a different parameterized type")
    void producerTypeMatch() {
        var listOfCandies = new TypeInfo.ParameterizedType(DotName.of(java.util.List.class.getName()),
                List.of(cls(Candy.class)));
        var listProducer = producer(BeanDescriptor.BeanKind.PRODUCER_METHOD, "p#list", Shop.class.getName(),
                listOfCandies);

        assertTrue(DisposerInvoker.disposerMatchesProducerType(
                disposer(Shop.class.getName(), DISPOSE, listOfCandies), listProducer));
        assertTrue(DisposerInvoker.disposerMatchesProducerType(shopDisposer(DISPOSE), sweetMethod()));
        assertFalse(DisposerInvoker.disposerMatchesProducerType(
                disposer(Shop.class.getName(), DISPOSE, new TypeInfo.ParameterizedType(
                        DotName.of(java.util.List.class.getName()), List.of())), sweetMethod()));
    }
}
