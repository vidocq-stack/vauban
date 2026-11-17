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

import io.vidocq.vauban.core.bean.validation.IndexClassValidator;
import io.vidocq.vauban.core.container.marked.MarkedTypeVariableObserver;
import io.vidocq.vauban.core.container.vetoed.PackageVetoedTypeVariableObserver;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-PERF-008: the definition rules read from the index report what the reflective rules report, class by class;
 * a class the index cannot decide is left to reflection.
 */
@DisplayName("VAU-PERF-008 - definition rules on the index")
class IndexClassValidatorTest {

    @Stereotype @Named("named") @Retention(RetentionPolicy.RUNTIME)
    @interface NamedStereotype {}

    @Stereotype @RequestScoped @Retention(RetentionPolicy.RUNTIME)
    @interface RequestStereotype {}

    @Stereotype @ApplicationScoped @Retention(RetentionPolicy.RUNTIME)
    @interface ApplicationStereotype {}

    @Dependent
    public static class Valid {
        @Inject Event<String> event;
        @Inject Instance<List<String>> instances;
        @Produces List<String> names() { return List.of(); }
    }

    @Dependent
    public static class RawEvent {
        @Inject Event event;
        @Inject void init(Instance instance) { /* no behaviour needed */ }
    }

    @Dependent
    public static class WildcardProducer {
        @Produces List<?> any() { return List.of(); }
        @Produces <T> T[] array() { return null; }
    }

    @Dependent
    public static class GenericInitializer {
        @Inject <T> void init(List<T> values) { /* no behaviour needed */ }
    }

    @ApplicationScoped
    public static class PublicField {
        public String value;
    }

    @Dependent @Typed(Runnable.class)
    public static class NotASupertype {}

    @Dependent
    public static class DisposerWithInjectionPoint {
        @Produces String make() { return ""; }
        void dispose(@Disposes String value, InjectionPoint point) { /* no behaviour needed */ }
    }

    @NamedStereotype
    public static class StereotypeWithName {}

    @RequestStereotype @ApplicationStereotype
    public static class ConflictingScopes {}

    @ApplicationScoped
    public static class GenericScoped<T> {}

    @Dependent
    public static class NamedWithoutValue {
        @Inject void init(@Named String value) { /* no behaviour needed */ }
    }

    @Dependent
    public static class TypeVariableObserverParameter<T> {
        void on(@Observes String event, T value) { /* no behaviour needed */ }
    }

    @Dependent
    public static class TypeVariableDisposerParameter<T> {
        @Produces String make() { return ""; }
        void dispose(@Disposes String value, T other) { /* no behaviour needed */ }
    }

    @Dependent
    public static class TypeVariableProducerParameter<T> {
        @Produces String make(T seed) { return String.valueOf(seed); }
    }

    @jakarta.enterprise.inject.Vetoed
    public static class VetoedTypeVariableProducerParameter<T> {
        @Produces String make(T seed) { return String.valueOf(seed); }
    }

    @jakarta.enterprise.inject.Vetoed
    public static class VetoedTypeVariableObserverParameter<T> {
        void on(@Observes String event, T value) { /* no behaviour needed */ }
    }

    public abstract static class AbstractTypeVariableObserverParameter<T> {
        void on(@Observes String event, T value) { /* no behaviour needed */ }
    }

    @Dependent
    public static class TypeVariableInitializerParameter<T> {
        @Inject void set(T value) { /* no behaviour needed */ }
    }

    @Dependent
    public static class TypeVariableConstructorParameter<T> {
        @Inject TypeVariableConstructorParameter(T value) { /* no behaviour needed */ }
    }

    @jakarta.enterprise.inject.Vetoed
    public static class VetoedTypeVariableInitializerParameter<T> {
        @Inject void set(T value) { /* no behaviour needed */ }
    }

    public abstract static class AbstractTypeVariableInitializerParameter<T> {
        @Inject void set(T value) { /* no behaviour needed */ }
    }

    /**
     * No bean-defining annotation: no bean in the annotated discovery mode, so no injection point nor observer of its
     * own; a subclass inherits them (BUG-20261117-30, review round 1).
     */
    public static class NoBeanTypeVariableInitializerParameter<T> {
        @Inject void set(T value) { /* no behaviour needed */ }
    }

    public static class NoBeanTypeVariableObserverParameter<T> {
        void on(@Observes String event, T value) { /* no behaviour needed */ }
    }

    /** Leaves the initializer it inherits from a vetoed class a type variable (BUG-20261117-30). */
    @Dependent
    public static class GenericInitializerOfVetoed<T> extends VetoedTypeVariableInitializerParameter<T> {}

    @Dependent
    public static class TextInitializerOfVetoed extends VetoedTypeVariableInitializerParameter<String> {}

    @Dependent
    public static class TextInitializerOfNoBean extends NoBeanTypeVariableInitializerParameter<String> {}

    /** Overrides the initializer it would inherit, without {@code @Inject}: no initializer. */
    @Dependent
    public static class OverridingInitializerOfVetoed<T> extends VetoedTypeVariableInitializerParameter<T> {
        @Override void set(T value) { /* no behaviour needed */ }
    }

    @Dependent
    public static class TypeVariableInjectField<T> {
        @Inject T value;
    }

    @jakarta.enterprise.inject.Vetoed
    public static class VetoedTypeVariableInjectField<T> {
        @Inject T value;
    }

    public abstract static class AbstractTypeVariableInjectField<T> {
        @Inject T value;
    }

    @Dependent
    public static class GenericInjectFieldOfVetoed<T> extends VetoedTypeVariableInjectField<T> {}

    @Dependent
    public static class TextInjectFieldOfVetoed extends VetoedTypeVariableInjectField<String> {}

    @Dependent @SuppressWarnings("rawtypes")
    public static class RawInjectFieldOfVetoed extends VetoedTypeVariableInjectField {}

    public abstract static class MiddleInjectField<U> extends AbstractTypeVariableInjectField<U> {}

    @Dependent
    public static class DeepInjectField<V> extends MiddleInjectField<V> {}

    /** No bean-defining annotation: no bean, its field is its subclasses' (BUG-20261117-29, review round 1). */
    public static class NoBeanTypeVariableInjectField<T> {
        @Inject T value;
    }

    @Dependent
    public static class TextInjectFieldOfNoBean extends NoBeanTypeVariableInjectField<String> {}

    /** Its superclass is not in the index: the index cannot tell which annotations it inherits. */
    @Dependent
    public static class OutsideTheIndex extends java.util.AbstractList<String> {
        @Override public String get(int index) { return null; }
        @Override public int size() { return 0; }
    }

    private static final List<Class<?>> CLASSES = List.of(NamedStereotype.class, RequestStereotype.class,
            ApplicationStereotype.class, Valid.class, RawEvent.class, WildcardProducer.class,
            GenericInitializer.class, PublicField.class, NotASupertype.class, DisposerWithInjectionPoint.class,
            StereotypeWithName.class, ConflictingScopes.class, GenericScoped.class, NamedWithoutValue.class,
            TypeVariableObserverParameter.class, TypeVariableDisposerParameter.class,
            TypeVariableProducerParameter.class, VetoedTypeVariableProducerParameter.class,
            VetoedTypeVariableObserverParameter.class, AbstractTypeVariableObserverParameter.class,
            OutsideTheIndex.class, PackageVetoedTypeVariableObserver.class, MarkedTypeVariableObserver.class,
            TypeVariableInitializerParameter.class, TypeVariableConstructorParameter.class,
            VetoedTypeVariableInitializerParameter.class, AbstractTypeVariableInitializerParameter.class,
            NoBeanTypeVariableInitializerParameter.class, NoBeanTypeVariableObserverParameter.class,
            GenericInitializerOfVetoed.class, TextInitializerOfVetoed.class, TextInitializerOfNoBean.class,
            OverridingInitializerOfVetoed.class,
            TypeVariableInjectField.class, VetoedTypeVariableInjectField.class, AbstractTypeVariableInjectField.class,
            GenericInjectFieldOfVetoed.class, TextInjectFieldOfVetoed.class, RawInjectFieldOfVetoed.class,
            MiddleInjectField.class, DeepInjectField.class, NoBeanTypeVariableInjectField.class,
            TextInjectFieldOfNoBean.class);

    /**
     * The {@code package-info} classes the index reads whether a package is vetoed from: one {@code @Vetoed}, one
     * annotated {@code @PackageMark} alone.
     */
    private static final List<String> PACKAGE_INFOS = List.of(
            PackageVetoedTypeVariableObserver.class.getPackageName() + ".package-info",
            MarkedTypeVariableObserver.class.getPackageName() + ".package-info");

    private static VaubanIndex index() {
        var builder = new IndexBuilder();
        var names = new java.util.ArrayList<>(CLASSES.stream().map(Class::getName).toList());
        names.addAll(PACKAGE_INFOS);
        for (var name : names) {
            try (var in = IndexClassValidatorTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                builder.add(ClassFileScanner.scan(in.readAllBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return builder.build();
    }

    private static IndexClassValidator validator(VaubanIndex index) {
        return new IndexClassValidator(index, name -> {
            try {
                return Class.forName(name, false, IndexClassValidatorTest.class.getClassLoader());
            } catch (ClassNotFoundException _) {
                return null;
            }
        }, name -> false);
    }

    @ParameterizedTest
    @ValueSource(classes = {Valid.class, RawEvent.class, WildcardProducer.class, GenericInitializer.class,
            PublicField.class, NotASupertype.class, DisposerWithInjectionPoint.class, StereotypeWithName.class,
            ConflictingScopes.class, GenericScoped.class, NamedWithoutValue.class,
            TypeVariableObserverParameter.class, TypeVariableDisposerParameter.class,
            TypeVariableProducerParameter.class, MarkedTypeVariableObserver.class,
            TypeVariableInitializerParameter.class, TypeVariableConstructorParameter.class,
            GenericInitializerOfVetoed.class,
            TypeVariableInjectField.class, GenericInjectFieldOfVetoed.class, DeepInjectField.class})
    @DisplayName("the index reports what reflection reports")
    void sameErrorsAsReflection(Class<?> beanClass) {
        var index = index();
        var fromIndex = validator(index).validate(index.getClassByName(DotName.of(beanClass.getName())).orElseThrow())
                .orElseThrow();
        Set<String> reflective = new TreeSet<>(ReflectionValidator.validateWithReflection(List.of(beanClass)));

        assertEquals(reflective, new TreeSet<>(fromIndex));
        assertEquals(beanClass != Valid.class, !fromIndex.isEmpty(), fromIndex::toString);
    }

    /**
     * An observer's parameter besides the event parameter, a disposer's besides the disposed one and a producer
     * method's are injection points, and an injection point of a type variable is a definition error (CDI 4.1,
     * 5.2.3), at build as at start (BUG-20261117-16, fix round 2).
     */
    @Test
    @DisplayName("an observer's, a disposer's or a producer's parameter of a type variable is a definition error")
    void typeVariableParameters() {
        var index = index();
        var validator = validator(index);

        assertEquals(List.of("Injection point has type variable type T: "
                        + TypeVariableObserverParameter.class.getName() + ".on observer param 1"),
                validator.validate(index.getClassByName(DotName.of(TypeVariableObserverParameter.class.getName()))
                        .orElseThrow()).orElseThrow());
        assertEquals(List.of("Injection point has type variable type T: "
                        + TypeVariableDisposerParameter.class.getName() + ".dispose disposer param 1"),
                validator.validate(index.getClassByName(DotName.of(TypeVariableDisposerParameter.class.getName()))
                        .orElseThrow()).orElseThrow());
        assertEquals(List.of("Injection point has type variable type T: "
                        + TypeVariableProducerParameter.class.getName() + ".make producer param 0"),
                validator.validate(index.getClassByName(DotName.of(TypeVariableProducerParameter.class.getName()))
                        .orElseThrow()).orElseThrow());
        // A package-info that does not veto its package leaves a class of it a bean
        assertEquals(List.of("Injection point has type variable type T: "
                        + MarkedTypeVariableObserver.class.getName() + ".on observer param 1"),
                validator.validate(index.getClassByName(DotName.of(MarkedTypeVariableObserver.class.getName()))
                        .orElseThrow()).orElseThrow());
    }

    /**
     * An initializer's and a bean constructor's parameters are injection points, and one of a type variable is a
     * definition error (CDI 4.1, 5.2.3), at build as at start. The start checked them on every class of the index,
     * a bean or not, and the build on none (BUG-20261117-30).
     */
    @Test
    @DisplayName("an initializer's or a bean constructor's parameter of a type variable is a definition error")
    void typeVariableInitializerAndConstructorParameters() {
        var index = index();
        var validator = validator(index);

        assertEquals(List.of("Injection point has type variable type T: "
                        + TypeVariableInitializerParameter.class.getName() + ".set initializer param 0"),
                validator.validate(index.getClassByName(DotName.of(TypeVariableInitializerParameter.class.getName()))
                        .orElseThrow()).orElseThrow());
        assertEquals(List.of("Injection point has type variable type T: "
                        + TypeVariableConstructorParameter.class.getName() + " constructor param 0"),
                validator.validate(index.getClassByName(DotName.of(TypeVariableConstructorParameter.class.getName()))
                        .orElseThrow()).orElseThrow());
    }

    /**
     * An {@code @Inject} field of a type variable is an injection point of a type variable, a definition error (CDI
     * 4.1, 5.2.3; TCK audit {@code legal_injection_point_types} c), at build as at start. No rule checked a field for
     * one, and the start looked it up by its erasure. A field inherited from a class that is no bean counts when the
     * bean class leaves it a type variable, through one superclass or more (BUG-20261117-29).
     */
    @Test
    @DisplayName("an @Inject field of a type variable is a definition error")
    void typeVariableInjectField() {
        var index = index();

        var validator = validator(index);

        assertEquals(List.of("Injection point has type variable type T: " + TypeVariableInjectField.class.getName()
                        + ".value"),
                validator.validate(index.getClassByName(DotName.of(TypeVariableInjectField.class.getName()))
                        .orElseThrow()).orElseThrow());
        assertEquals(List.of("Injection point has type variable type T: " + GenericInjectFieldOfVetoed.class.getName()
                        + ".value"),
                validator.validate(index.getClassByName(DotName.of(GenericInjectFieldOfVetoed.class.getName()))
                        .orElseThrow()).orElseThrow());
        assertEquals(List.of("Injection point has type variable type V: " + DeepInjectField.class.getName()
                        + ".value"),
                validator.validate(index.getClassByName(DotName.of(DeepInjectField.class.getName()))
                        .orElseThrow()).orElseThrow());
    }

    /**
     * A class that is no bean, vetoed, in a vetoed package or abstract, has no observer, initializer or injected field
     * of its own: a subclass inherits it and gives the type variable a class, as the CDI TCK's
     * {@code inheritance.generics.Bar} and {@code Foo} (BUG-20261117-16, BUG-20261117-30, BUG-20261117-29).
     */
    @ParameterizedTest
    @ValueSource(classes = {VetoedTypeVariableObserverParameter.class, AbstractTypeVariableObserverParameter.class,
            VetoedTypeVariableProducerParameter.class, PackageVetoedTypeVariableObserver.class,
            VetoedTypeVariableInitializerParameter.class, AbstractTypeVariableInitializerParameter.class,
            NoBeanTypeVariableInitializerParameter.class, NoBeanTypeVariableObserverParameter.class,
            TextInitializerOfVetoed.class, TextInitializerOfNoBean.class, OverridingInitializerOfVetoed.class,
            VetoedTypeVariableInjectField.class, AbstractTypeVariableInjectField.class, TextInjectFieldOfVetoed.class,
            RawInjectFieldOfVetoed.class, MiddleInjectField.class, NoBeanTypeVariableInjectField.class,
            TextInjectFieldOfNoBean.class})
    @DisplayName("an injection point of a type variable in a class that is no bean, or given a class, is no error")
    void typeVariableParametersOfAClassThatIsNoBean(Class<?> beanClass) {
        var index = index();

        assertEquals(List.of(), validator(index).validate(index.getClassByName(DotName.of(beanClass.getName()))
                .orElseThrow()).orElseThrow(), "from the index");
        assertEquals(List.of(), ReflectionValidator.validateWithReflection(List.of(beanClass)), "by reflection");
    }

    /**
     * A bean that inherits an initializer from a class that is no bean, here a vetoed one, and leaves its parameter a
     * type variable of its own has an injection point of a type variable (CDI 4.1, 5.2.3): it started and failed to
     * call the initializer at the first lookup (BUG-20261117-30, review round 1).
     */
    @Test
    @DisplayName("an inherited initializer's parameter the bean class leaves a type variable is a definition error")
    void inheritedInitializerLeftATypeVariable() {
        var index = index();
        var expected = List.of("Injection point has type variable type T: "
                + GenericInitializerOfVetoed.class.getName() + ".set initializer param 0");

        assertEquals(expected, validator(index).validate(index.getClassByName(
                DotName.of(GenericInitializerOfVetoed.class.getName())).orElseThrow()).orElseThrow(), "from the index");
        assertEquals(expected, ReflectionValidator.validateWithReflection(List.of(GenericInitializerOfVetoed.class)),
                "by reflection");
    }

    @Test
    @DisplayName("a class whose superclass is not in the index is left to reflection")
    void undecidableIsLeftToReflection() {
        var index = index();
        var info = index.getClassByName(DotName.of(OutsideTheIndex.class.getName())).orElseThrow();

        assertTrue(validator(index).validate(info).isEmpty());
    }

    @Test
    @DisplayName("an index with erased member types is left to reflection")
    void erasedIndexIsLeftToReflection() {
        var index = index();
        var scanned = index.getClassByName(DotName.of(RawEvent.class.getName())).orElseThrow();
        var erased = new io.vidocq.vauban.indexer.model.ClassInfo(scanned.name(), scanned.superName(),
                scanned.interfaces(), scanned.accessFlags(), scanned.fields(), scanned.methods(), scanned.annotations(),
                scanned.kind(), scanned.simpleName(), scanned.typeParameters());

        assertFalse(erased.genericMembers());
        assertTrue(validator(index).validate(erased).isEmpty());
    }
}
