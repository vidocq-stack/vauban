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
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignabilityRulesReproductionTest {

    /** How the container names an unsatisfied field of a bean class nested in this one. */
    private static final String UNSATISFIED = "Unsatisfied dependency: field AssignabilityRulesReproductionTest$";
    /** How the definition validation names a parameter of a type variable {@code T}, its location after. */
    private static final String TYPE_VARIABLE_PARAMETER = "Injection point has type variable type T: ";

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

    @Dependent
    public static class FooOfObjectUser {
        @Inject
        Foo<Object> foo;
    }

    @Dependent
    public static class FooOfStringFactory {
        @Produces
        Foo<String> foo() {
            return new Foo<>() {
            };
        }
    }

    public interface Dao<D> {
    }

    @Dependent
    public static class GenericDao<T> implements Dao<T> {
    }

    @Dependent
    public static class DaoOfIntegerUser {
        @Inject
        Dao<? extends Integer> dao;
    }

    public interface Holder<H> {
    }

    @Dependent
    public static class Sorted<T extends Comparable<T>> implements Holder<T> {
    }

    @Dependent
    public static class HolderOfIntegerUser {
        @Inject
        Holder<Integer> holder;
    }

    @Dependent
    @SuppressWarnings("rawtypes")
    public static class RawHolderUser {
        @Inject
        Holder holder;
    }

    @Dependent
    public static class Counter<T extends Number> {
    }

    @Dependent
    public static class TextHolder implements Holder<String> {
    }

    public static class Ping {
    }

    public static class Ticket {
    }

    /** What the last observer and disposer of the parameter test were given, besides the event and the disposed. */
    static final List<Object> RECEIVED = new CopyOnWriteArrayList<>();

    @Dependent
    public static class ParameterizedListener {
        void on(@Observes Ping ping, Counter<Integer> counter, Holder<String> holder) {
            RECEIVED.add(counter);
            RECEIVED.add(holder);
        }
    }

    @Dependent
    public static class TicketFactory {
        @Produces
        Ticket ticket() {
            return new Ticket();
        }

        void dispose(@Disposes Ticket ticket, Counter<Integer> counter) {
            RECEIVED.add(ticket);
            RECEIVED.add(counter);
        }
    }

    @Dependent
    public static class Chained<U extends Number, T extends U> implements Holder<T> {
    }

    @Dependent
    public static class Linked<U, T extends U> implements Holder<T> {
    }

    @Dependent
    public static class Measured<T extends Number & Comparable<T>> implements Holder<T> {
    }

    @Dependent
    public static class SerializableHolderUser {
        @Inject
        Holder<? extends java.io.Serializable> holder;
    }

    public static class Pong {
    }

    @Dependent
    public static class IntegerHolderListener {
        void on(@Observes Pong pong, Holder<Integer> holder) {
            RECEIVED.add(holder);
        }
    }

    @Dependent
    public static class Gadget {
    }

    public static class Widget {
    }

    @Dependent
    public static class WidgetFactory<T extends Gadget> {
        @Produces
        Widget widget() {
            return new Widget();
        }

        void dispose(@Disposes Widget widget, T gadget) {
            RECEIVED.add(widget);
            RECEIVED.add(gadget);
        }
    }

    public static class Pang {
    }

    public abstract static class HolderListener<X> {
        void on(@Observes Pang pang, Holder<X> holder) {
            RECEIVED.add(holder);
        }
    }

    @Dependent
    public static class TextHolderListener extends HolderListener<String> {
    }

    public static class Pung {
    }

    public abstract static class IntegerHolderListenerBase {
        void on(@Observes Pung pung, Holder<Integer> holder) {
            RECEIVED.add(holder);
        }
    }

    @Dependent
    public static class InheritedIntegerHolderListener extends IntegerHolderListenerBase {
    }

    public static class Peng {
    }

    @Dependent
    public static class GadgetListener<T extends Gadget> {
        void on(@Observes Peng peng, T gadget) {
            RECEIVED.add(gadget);
        }
    }

    @Dependent
    public static class IntegerHolder implements Holder<Integer> {
    }

    @Dependent
    public static class OtherIntegerHolder implements Holder<Integer> {
    }

    @Dependent
    public static class IntegerCounter extends Counter<Integer> {
    }

    public interface Shape {
    }

    /** A shape no bean names: the index of a deployment of {@link ShapeHolder} does not hold it. */
    public static class Circle implements Shape {
    }

    @Dependent
    public static class ShapeHolder<T extends Shape> implements Holder<T> {
    }

    public static class Pyng {
    }

    public static class Sprocket {
    }

    @Dependent
    public static class SprocketFactory<T extends Gadget> {
        @Produces
        Sprocket sprocket(T gadget) {
            return new Sprocket();
        }
    }

    /** Not a bean, as the CDI TCK's {@code inheritance.generics.Bar}: its subclass gives {@code T} a class. */
    @jakarta.enterprise.inject.Vetoed
    public static class VetoedGadgetListener<T> {
        void on(@Observes Pyng pyng, T gadget) {
            RECEIVED.add(gadget);
        }
    }

    @Dependent
    public static class GadgetListenerOfVetoed extends VetoedGadgetListener<Gadget> {
    }

    /** Not a bean either: abstract. */
    public abstract static class AbstractGadgetListener<T> {
        void on(@Observes Pyng pyng, T gadget) {
            RECEIVED.add(gadget);
        }
    }

    @Dependent
    public static class GadgetListenerOfAbstract extends AbstractGadgetListener<Gadget> {
    }

    public static class Pist {
    }

    /** Not a bean: vetoed, with an observer of its type variable, which its subclass leaves a type variable. */
    @jakarta.enterprise.inject.Vetoed
    public static class VetoedTypeVariableListener<T> {
        void on(@Observes Pist pist, T value) {
            RECEIVED.add(value);
        }
    }

    @Dependent
    public static class GenericListenerOfVetoed<T> extends VetoedTypeVariableListener<T> {
    }

    /** Not a bean: an {@code InjectionTarget} injects its field. */
    public static class FooOfIntegerClient {
        @Inject
        Foo<Integer> foo;
    }

    /** Not a bean: {@code Unmanaged} calls its initializer. */
    public static class FooOfIntegerInitializedClient {
        Foo<Integer> foo;

        @Inject
        void init(Foo<Integer> foo) {
            this.foo = foo;
        }
    }

    /** A bean with an {@code @Inject} field of its type variable (BUG-20261117-29). */
    @Dependent
    public static class GadgetField<T> {
        @Inject
        T gadget;
    }

    /** Not a bean: abstract, with an initializer of its type variable that its subclass gives a class. */
    public abstract static class AbstractGadgetSetter<T> {
        T gadget;

        @Inject
        void set(T gadget) {
            this.gadget = gadget;
        }
    }

    @Dependent
    public static class GadgetSetterOfAbstract extends AbstractGadgetSetter<Gadget> {
    }

    /** Not a bean either: vetoed, with the same initializer. */
    @jakarta.enterprise.inject.Vetoed
    public static class VetoedGadgetSetter<T> {
        T gadget;

        @Inject
        void set(T gadget) {
            this.gadget = gadget;
        }
    }

    @Dependent
    public static class GadgetSetterOfVetoed extends VetoedGadgetSetter<Gadget> {
    }

    /** A {@code Bean} its caller makes, of the types {@code Integer} and {@code Object}: none of the container's. */
    private static final class CallersBean implements Bean<Object> {
        @Override
        public Class<?> getBeanClass() {
            return Integer.class;
        }

        @Override
        public Set<InjectionPoint> getInjectionPoints() {
            return Set.of();
        }

        @Override
        public Object create(CreationalContext<Object> context) {
            return 0;
        }

        @Override
        public void destroy(Object instance, CreationalContext<Object> context) {
            // An Integer holds nothing to release.
        }

        @Override
        public Set<java.lang.reflect.Type> getTypes() {
            return Set.of(Integer.class, Object.class);
        }

        @Override
        public Set<Annotation> getQualifiers() {
            return Set.of(Default.Literal.INSTANCE, Any.Literal.INSTANCE);
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return Dependent.class;
        }

        @Override
        public String getName() {
            return null;
        }

        @Override
        public Set<Class<? extends Annotation>> getStereotypes() {
            return Set.of();
        }

        @Override
        public boolean isAlternative() {
            return false;
        }
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
    @Test
    @DisplayName("a raw bean type does not satisfy an injection point of a parameterization other than Object")
    void aRawBeanTypeDoesNotSatisfyAnotherParameterization() {
        try (var container = VaubanContainer.builder().addBeanClass(RawFooFactory.class).build()) {
            assertEquals(Set.of(), container.getBeanManager().getBeans(new TypeLiteral<Foo<Integer>>() { }.getType()),
                    "BeanManager.getBeans");
        }
        var builder = VaubanContainer.builder().addBeanClass(RawFooFactory.class).addBeanClass(FooOfIntegerUser.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the injection point resolved");
        assertTrue(refused.getMessage().contains(UNSATISFIED + "FooOfIntegerUser.foo"),
                refused::getMessage);
    }

    /** A raw bean type satisfies an injection point whose type arguments are all {@code Object} (CDI 4.1, 5.2.4). */
    @Test
    @DisplayName("a raw bean type satisfies an injection point of its parameterization by Object")
    void aRawBeanTypeSatisfiesItsParameterizationByObject() {
        try (var container = VaubanContainer.builder().addBeanClass(RawFooFactory.class)
                .addBeanClass(FooOfObjectUser.class).build()) {
            assertEquals(1, container.getBeanManager().getBeans(new TypeLiteral<Foo<Object>>() { }.getType()).size(),
                    "BeanManager.getBeans");
            assertNotNull(container.select(FooOfObjectUser.class).foo, "the injected Foo<Object>");
        }
    }

    /** A parameterized bean type satisfies no other parameterization of its class, as before BUG-20261117-16. */
    @Test
    @DisplayName("a parameterized bean type does not satisfy another parameterization")
    void aParameterizedBeanTypeDoesNotSatisfyAnotherParameterization() {
        var builder = VaubanContainer.builder().addBeanClass(FooOfStringFactory.class)
                .addBeanClass(FooOfIntegerUser.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the injection point resolved");
        assertTrue(refused.getMessage().contains(UNSATISFIED + "FooOfIntegerUser.foo"),
                refused::getMessage);
    }

    /**
     * A wildcard type argument admits a bean's type variable whose upper bound is assignable to or from the wildcard's
     * (CDI 4.1, 5.2.4, rules da and db), {@code Object} when it declares none: {@code Dao<T>} satisfies
     * {@code @Inject Dao<? extends Integer>}, at start as {@code BeanManager.getBeans} says. The resolution of an
     * injection point checked the "assignable to" half only, and an unbounded type variable against no bound at all
     * (BUG-20261117-24).
     */
    @Test
    @DisplayName("a bean type variable satisfies a wildcard whose upper bound is below its own")
    void aBeanTypeVariableSatisfiesAWildcardWhoseUpperBoundIsBelowItsOwn() {
        var required = new TypeLiteral<Dao<? extends Integer>>() { }.getType();
        try (var container = VaubanContainer.builder().addBeanClass(GenericDao.class)
                .addBeanClass(DaoOfIntegerUser.class).build()) {
            assertEquals(1, container.getBeanManager().getBeans(required).size(), "BeanManager.getBeans");
            assertNotNull(container.select(DaoOfIntegerUser.class).dao, "the injected Dao<? extends Integer>");
        }
    }

    /**
     * An actual type argument admits a bean's type variable when it is assignable to each of its upper bounds (CDI 4.1,
     * 5.2.4, rule e), a parameterized one by its class, as {@code BeanManager.getBeans} and the Java compiler see it:
     * {@code Sorted<T extends Comparable<T>> implements Holder<T>} satisfies {@code @Inject Holder<Integer>}. The
     * resolution of an injection point compared the names of {@code Integer} and {@code Comparable} (BUG-20261117-24).
     */
    @Test
    @DisplayName("a bean type variable with a parameterized bound satisfies an actual type within it")
    void aBeanTypeVariableWithAParameterizedBoundSatisfiesAnActualTypeWithinIt() {
        var required = new TypeLiteral<Holder<Integer>>() { }.getType();
        try (var container = VaubanContainer.builder().addBeanClass(Sorted.class)
                .addBeanClass(HolderOfIntegerUser.class).build()) {
            assertEquals(1, container.getBeanManager().getBeans(required).size(), "BeanManager.getBeans");
            assertNotNull(container.select(HolderOfIntegerUser.class).holder, "the injected Holder<Integer>");
        }
    }

    /**
     * A parameterized bean type is assignable to its raw type only if each of its type arguments is {@code Object} or
     * an unbounded type variable (CDI 4.1, 5.2.4): {@code Holder<T extends Comparable<T>>} does not satisfy
     * {@code @Inject Holder}, as {@code BeanManager.getBeans} says. The resolution of an injection point took a type
     * variable bounded by a parameterized type for an unbounded one (BUG-20261117-24).
     */
    @Test
    @DisplayName("a bean type variable with a parameterized bound does not satisfy the raw type")
    void aBeanTypeVariableWithAParameterizedBoundDoesNotSatisfyTheRawType() {
        try (var container = VaubanContainer.builder().addBeanClass(Sorted.class).build()) {
            assertEquals(Set.of(), container.getBeanManager().getBeans(Holder.class), "BeanManager.getBeans");
        }
        var builder = VaubanContainer.builder().addBeanClass(Sorted.class).addBeanClass(RawHolderUser.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the injection point resolved");
        assertTrue(refused.getMessage().contains(UNSATISFIED + "RawHolderUser.holder"),
                refused::getMessage);
    }

    /**
     * The parameters of an observer method other than the event, and of a disposer method other than the disposed
     * one, are injection points: {@code Counter<Integer>} resolves to the bean of {@code Counter<T extends Number>},
     * and {@code Holder<String>} to the bean of {@code TextHolder implements Holder<String>}. The container checked
     * them as the raw class of the parameter, {@code Counter} and {@code Holder}, which neither bean type satisfies:
     * a parameterized bean type satisfies its raw type only when each type argument is {@code Object} or an unbounded
     * type variable (CDI 4.1, 5.2.4; BUG-20261117-25).
     */
    @Test
    @DisplayName("an observer's and a disposer's parameters of a parameterized type resolve as injection points")
    void anObserversAndADisposersParametersOfAParameterizedTypeResolveAsInjectionPoints() {
        var builder = VaubanContainer.builder().addBeanClass(Counter.class).addBeanClass(TextHolder.class)
                .addBeanClass(ParameterizedListener.class).addBeanClass(TicketFactory.class);

        RECEIVED.clear();
        try (var container = assertDoesNotThrow(builder::build, "the container refused the parameters")) {
            container.getBeanManager().getEvent().select(Ping.class).fire(new Ping());
            assertEquals(List.of(Counter.class, TextHolder.class), classesOf(RECEIVED), "the observer's arguments");

            RECEIVED.clear();
            var tickets = container.getBeanManager().createInstance().select(Ticket.class);
            tickets.destroy(tickets.get());
            assertEquals(List.of(Ticket.class, Counter.class), classesOf(RECEIVED), "the disposer's arguments");
        }
    }

    /**
     * A bean of {@code Chained<U extends Number, T extends U> implements Holder<T>} satisfies {@code @Inject
     * Holder<Integer>}: {@code Integer} is assignable to the uppermost bound of {@code T}, {@code Number} (CDI 4.1,
     * 5.2.4, rule e; Weld's {@code getUppermostTypeVariableBounds}). The validation read the uppermost bound and
     * passed; {@code BeanManager.getBeans}, which the injection reads at run time, read the declared one, {@code U},
     * and the injection failed (BUG-20261117-16).
     */
    @Test
    @DisplayName("a type variable bounded by another satisfies an actual type within the uppermost bound")
    void aTypeVariableBoundedByAnotherSatisfiesAnActualTypeWithinTheUppermostBound() {
        var builder = VaubanContainer.builder().addBeanClass(Chained.class).addBeanClass(HolderOfIntegerUser.class);

        try (var container = builder.build()) {
            assertEquals(Chained.class, container.select(HolderOfIntegerUser.class).holder.getClass(),
                    "the bean injected");
            var beanManager = container.getBeanManager();
            assertAll(
                    () -> assertEquals(1, beanManager.getBeans(new TypeLiteral<Holder<Integer>>() { }.getType()).size(),
                            "BeanManager.getBeans of Holder<Integer>"),
                    () -> assertEquals(Set.of(), beanManager.getBeans(new TypeLiteral<Holder<String>>() { }.getType()),
                            "BeanManager.getBeans of Holder<String>"));
        }
    }

    /**
     * {@code T extends U} is a bounded type variable, {@code U} unbounded or not: a variable declared with a bound is
     * bounded (JLS 4.4), and Weld's {@code isUnboundedTypeVariable} asks for {@code Object} alone. A bean of
     * {@code Linked<U, T extends U> implements Holder<T>} does not satisfy a raw {@code @Inject Holder} (CDI 4.1,
     * 5.2.4, rule a), nor {@code getBeans(Holder.class)} (BUG-20261117-16).
     */
    @Test
    @DisplayName("a type variable bounded by another type variable is bounded")
    void aTypeVariableBoundedByAnotherTypeVariableIsBounded() {
        try (var container = VaubanContainer.builder().addBeanClass(Linked.class).build()) {
            assertEquals(Set.of(), container.getBeanManager().getBeans(Holder.class), "BeanManager.getBeans");
        }
        var builder = VaubanContainer.builder().addBeanClass(Linked.class).addBeanClass(RawHolderUser.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the injection point resolved");
        assertTrue(refused.getMessage().contains(UNSATISFIED + "RawHolderUser.holder"), refused::getMessage);
    }

    /**
     * A bean of {@code Measured<T extends Number & Comparable<T>> implements Holder<T>} satisfies {@code @Inject
     * Holder<? extends Serializable>}: the upper bound of {@code T}, {@code Number & Comparable<T>}, is assignable to
     * {@code Serializable} (CDI 4.1, 5.2.4, rule db; Weld's {@code boundsMatch}). {@code BeanManager.getBeans} asked it
     * of each bound, and {@code Comparable} is no {@code Serializable} (BUG-20261117-16).
     */
    @Test
    @DisplayName("a type variable with two bounds satisfies a wildcard one of its bounds is within")
    void aTypeVariableWithTwoBoundsSatisfiesAWildcardOneOfItsBoundsIsWithin() {
        var builder = VaubanContainer.builder().addBeanClass(Measured.class).addBeanClass(SerializableHolderUser.class);

        try (var container = builder.build()) {
            assertEquals(Measured.class, container.select(SerializableHolderUser.class).holder.getClass(),
                    "the bean injected");
            assertEquals(1, container.getBeanManager().getBeans(
                    new TypeLiteral<Holder<? extends java.io.Serializable>>() { }.getType()).size(),
                    "BeanManager.getBeans");
        }
    }

    /**
     * The start names an observer's parameter by its type as declared, {@code Holder<java.lang.Integer>}, the type
     * it checks, where it named the erased class (BUG-20261117-25).
     */
    @Test
    @DisplayName("an unsatisfied observer parameter is named by its parameterized type")
    void anUnsatisfiedObserverParameterIsNamedByItsParameterizedType() {
        var builder = VaubanContainer.builder().addBeanClass(TextHolder.class)
                .addBeanClass(IntegerHolderListener.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the parameter resolved");
        assertTrue(refused.getMessage().contains(
                "unsatisfied dependency for parameter arg1 of type " + Holder.class.getName() + "<java.lang.Integer>"),
                refused::getMessage);
    }

    /**
     * A disposer's parameter besides the disposed one is an injection point, and an injection point of a type
     * variable is a definition error (CDI 4.1, "Declaring a disposer method" and 5.2.3; TCK audit, disposer methods h
     * and {@code legal_injection_point_types} c). The start looked {@code T extends Gadget} up by its erased class,
     * {@code Gadget} (BUG-20261117-16, fix round 2).
     */
    @Test
    @DisplayName("a disposer's parameter of a type variable is a definition error")
    void aDisposersParameterOfATypeVariableIsADefinitionError() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(WidgetFactory.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains(
                TYPE_VARIABLE_PARAMETER + WidgetFactory.class.getName() + ".dispose disposer param 1"),
                refused::getMessage);
    }

    /**
     * An observer's parameter besides the event parameter is an injection point, and an injection point of a type
     * variable is a definition error (CDI 4.1, "Declaring an observer method" and 5.2.3). The start checked none
     * (BUG-20261117-16, fix round 2).
     */
    @Test
    @DisplayName("an observer's parameter of a type variable is a definition error")
    void anObserversParameterOfATypeVariableIsADefinitionError() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(GadgetListener.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains(
                TYPE_VARIABLE_PARAMETER + GadgetListener.class.getName() + ".on observer param 1"),
                refused::getMessage);
    }

    /**
     * An observer a bean inherits from a class that is no bean, vetoed or abstract, has its parameter of a type
     * variable resolved by the bean class, {@code T} as {@code Gadget}: no definition error, as the CDI TCK's
     * {@code MemberLevelInheritanceTest} has it (BUG-20261117-16, fix round 2).
     */
    @Test
    @DisplayName("an inherited observer's parameter of a type variable its bean class gives a class is legal")
    void anInheritedObserversParameterOfATypeVariableItsBeanClassGivesAClassIsLegal() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(VetoedGadgetListener.class)
                .addBeanClass(GadgetListenerOfVetoed.class).addBeanClass(AbstractGadgetListener.class)
                .addBeanClass(GadgetListenerOfAbstract.class);

        RECEIVED.clear();
        try (var container = assertDoesNotThrow(builder::build, "the container refused the deployment")) {
            container.getBeanManager().getEvent().select(Pyng.class).fire(new Pyng());
            assertEquals(List.of(Gadget.class, Gadget.class), classesOf(RECEIVED), "what the two observers got");
        }
    }

    /**
     * A producer method's parameter is an injection point, and an injection point of a type variable is a
     * definition error (CDI 4.1, "Declaring a producer method" and 5.2.3; TCK audit {@code legal_injection_point_types}
     * c). The start checked none (BUG-20261117-16, fix round 2b).
     */
    @Test
    @DisplayName("a producer method's parameter of a type variable is a definition error")
    void aProducerMethodsParameterOfATypeVariableIsADefinitionError() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(SprocketFactory.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains(
                TYPE_VARIABLE_PARAMETER + SprocketFactory.class.getName() + ".sprocket producer param 0"),
                refused::getMessage);
    }

    /** The start names an ambiguous observer parameter by its type as declared, as an unsatisfied one. */
    @Test
    @DisplayName("an ambiguous observer parameter is named by its parameterized type")
    void anAmbiguousObserverParameterIsNamedByItsParameterizedType() {
        var builder = VaubanContainer.builder().addBeanClass(IntegerHolder.class)
                .addBeanClass(OtherIntegerHolder.class).addBeanClass(IntegerHolderListener.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the parameter resolved");
        assertTrue(refused.getMessage().contains(
                "ambiguous dependency for parameter arg1 of type " + Holder.class.getName() + "<java.lang.Integer>"),
                refused::getMessage);
    }

    /** The start names an ambiguous disposer parameter by its type as declared (BUG-20261117-25). */
    @Test
    @DisplayName("an ambiguous disposer parameter is named by its parameterized type")
    void anAmbiguousDisposerParameterIsNamedByItsParameterizedType() {
        var builder = VaubanContainer.builder().addBeanClass(Counter.class).addBeanClass(IntegerCounter.class)
                .addBeanClass(TicketFactory.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the parameter resolved");
        assertTrue(refused.getMessage().contains(
                "(): ambiguous dependency for parameter of type " + Counter.class.getName() + "<java.lang.Integer>"),
                refused::getMessage);
    }

    /**
     * {@code BeanManager.isMatchingBean} applies the rules of {@code getBeans}: the bean type
     * {@code Counter<T extends Number>} does not match the raw {@code Counter}, and {@code Dao<T>}, {@code T}
     * unbounded, matches the raw {@code Dao} (CDI 4.1, 5.2.4, rule a). It took any parameterization of the raw class
     * (BUG-20261117-16).
     */
    @Test
    @DisplayName("isMatchingBean applies the raw type rule of getBeans")
    void isMatchingBeanAppliesTheRawTypeRuleOfGetBeans() {
        try (var container = VaubanContainer.builder().addBeanClass(Counter.class).addBeanClass(GenericDao.class)
                .build()) {
            var beanManager = container.getBeanManager();
            var counterOfInteger = new TypeLiteral<Counter<Integer>>() { }.getType();
            var counter = beanManager.resolve(beanManager.getBeans(counterOfInteger));
            var dao = beanManager.resolve(beanManager.getBeans(new TypeLiteral<Dao<String>>() { }.getType()));

            assertAll(
                    () -> assertFalse(beanManager.isMatchingBean(counter.getTypes(), counter.getQualifiers(),
                            Counter.class, Set.of()), "Counter<T extends Number> against the raw Counter"),
                    () -> assertTrue(beanManager.isMatchingBean(counter.getTypes(), counter.getQualifiers(),
                            counterOfInteger, Set.of()), "Counter<T extends Number> against Counter<Integer>"),
                    () -> assertTrue(beanManager.isMatchingBean(dao.getTypes(), dao.getQualifiers(), Dao.class,
                            Set.of()), "Dao<T> against the raw Dao"));
        }
    }

    /**
     * {@code BeanManager.isMatchingEvent} compares a parameterized type argument of an event by the rules of event
     * types (CDI 4.1, 10.3.1): the same raw type, each argument within the observed one, a wildcard's bound included.
     */
    @Test
    @DisplayName("isMatchingEvent compares a parameterized type argument by the rules of event types")
    void isMatchingEventComparesAParameterizedTypeArgumentByTheRulesOfEventTypes() {
        try (var container = VaubanContainer.builder().addBeanClass(TextHolder.class).build()) {
            var beanManager = container.getBeanManager();
            var listsOfIntegers = new TypeLiteral<List<List<Integer>>>() { }.getType();

            assertAll(
                    () -> assertTrue(beanManager.isMatchingEvent(listsOfIntegers, Set.of(),
                            new TypeLiteral<List<List<? extends Number>>>() { }.getType(), Set.of())),
                    () -> assertTrue(beanManager.isMatchingEvent(listsOfIntegers, Set.of(),
                            new TypeLiteral<List<? extends List<? extends Number>>>() { }.getType(), Set.of())),
                    () -> assertFalse(beanManager.isMatchingEvent(listsOfIntegers, Set.of(),
                            new TypeLiteral<List<List<? extends CharSequence>>>() { }.getType(), Set.of())),
                    () -> assertFalse(beanManager.isMatchingEvent(listsOfIntegers, Set.of(),
                            new TypeLiteral<List<java.util.Collection<Integer>>>() { }.getType(), Set.of())));
        }
    }

    /**
     * {@code getBeans} reads a class outside the index through the container's class loader, whatever the context
     * class loader of the caller: {@code Circle}, which no bean names, is within the bound of
     * {@code ShapeHolder<T extends Shape>}. The rules loaded it through the caller's context loader, and a caller
     * whose context loader cannot see it found no bean; the reflective rules they replaced compared the classes
     * (BUG-20261117-16, fix round 2).
     */
    @Test
    @DisplayName("getBeans reads a class outside the index through the container's loader, whatever the caller's")
    void getBeansReadsAClassOutsideTheIndexThroughTheContainersLoader() throws IOException {
        // The platform loader would not do: it delegates a package of a named module to that module's loader.
        try (var container = VaubanContainer.builder().addBeanClass(ShapeHolder.class).build();
             var blind = new URLClassLoader(new URL[0], null)) {
            var beanManager = container.getBeanManager();
            var holderOfCircle = new TypeLiteral<Holder<Circle>>() { }.getType();
            var thread = Thread.currentThread();
            var caller = thread.getContextClassLoader();
            thread.setContextClassLoader(blind);
            int found;
            try {
                found = beanManager.getBeans(holderOfCircle).size();
            } finally {
                thread.setContextClassLoader(caller);
            }
            assertEquals(1, found, "the beans of Holder<Circle>, the caller's context loader seeing no test class");
        }
    }

    /**
     * {@code getReference}, on the path of every injection, matches the type it is given against a bean of the
     * container through the conversions {@code getBeans} keeps, each bean type converted once for the life of the
     * container, and keeps none of a {@code Bean} its caller makes: it converted every type at each call
     * (BUG-20261117-16, fix round 3).
     */
    @Test
    @DisplayName("getReference converts the types of a bean of the container once, and keeps none of a caller's bean")
    void getReferenceConvertsTheTypesOfABeanOfTheContainerOnce() throws ReflectiveOperationException {
        try (var container = VaubanContainer.builder().addBeanClass(Counter.class).build()) {
            var beanManager = container.getBeanManager();
            var counterOfInteger = new TypeLiteral<Counter<Integer>>() { }.getType();
            var counter = beanManager.resolve(beanManager.getBeans(counterOfInteger));
            var converted = convertedTypes(beanManager);
            converted.clear();

            beanManager.getReference(counter, counterOfInteger, beanManager.createCreationalContext(counter));
            var first = Map.copyOf(converted);
            beanManager.getReference(counter, counterOfInteger, beanManager.createCreationalContext(counter));
            var second = Map.copyOf(converted);
            converted.clear();
            var callers = new CallersBean();
            var callersContext = beanManager.createCreationalContext(callers);
            assertThrows(IllegalArgumentException.class, () -> beanManager.getReference(callers, Number.class,
                    callersContext), "Number is a type of the caller's bean");

            assertAll(
                    () -> assertFalse(first.isEmpty(), "no type of the container's bean was kept"),
                    () -> assertTrue(counter.getTypes().containsAll(first.keySet()), first.keySet()::toString),
                    () -> assertEquals(first.keySet(), second.keySet(), "what the second call kept"),
                    () -> first.forEach((type, info) -> assertSame(info, second.get(type), type::toString)),
                    () -> assertEquals(Map.of(), Map.copyOf(converted), "what the caller's bean left"));
        }
    }

    /**
     * A wildcard or a type variable is no bean type (CDI 4.1, 2.2.1), and matches only itself, as in Weld's
     * {@code BeanTypeAssignabilityRules}: {@code getBeans} of a wildcard finds no bean, and {@code getReference}
     * refuses a type variable.
     */
    @Test
    @DisplayName("getBeans and getReference match a wildcard or a type variable to no bean type")
    <T> void aWildcardOrATypeVariableMatchesNoBeanType() {
        try (var container = VaubanContainer.builder().addBeanClass(Counter.class).build()) {
            var beanManager = container.getBeanManager();
            var counter = beanManager.resolve(beanManager.getBeans(new TypeLiteral<Counter<Integer>>() { }.getType()));
            var wildcard = ((java.lang.reflect.ParameterizedType) new TypeLiteral<Counter<?>>() { }.getType())
                    .getActualTypeArguments()[0];
            var variable = new TypeLiteral<T>() { }.getType();
            var counterContext = beanManager.createCreationalContext(counter);

            assertAll(
                    () -> assertEquals(Set.of(), beanManager.getBeans(wildcard), "the beans of a wildcard"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> beanManager.getReference(counter, variable, counterContext), "a reference of T"));
        }
    }

    /**
     * An observed wildcard with a lower bound takes an event type argument its bound is assignable to, and an event
     * type argument of a wildcard is within an observed argument its upper bound is assignable to (CDI 4.1, 10.3.1).
     */
    @Test
    @DisplayName("isMatchingEvent reads the lower bound of an observed wildcard and the upper bound of an event's")
    void isMatchingEventReadsTheBoundsOfAWildcard() {
        try (var container = VaubanContainer.builder().addBeanClass(TextHolder.class).build()) {
            var beanManager = container.getBeanManager();
            var listOfNumbers = new TypeLiteral<List<Number>>() { }.getType();
            var listOfIntegers = new TypeLiteral<List<Integer>>() { }.getType();
            var listOfStrings = new TypeLiteral<List<String>>() { }.getType();
            var superInteger = new TypeLiteral<List<? super Integer>>() { }.getType();
            var extendsInteger = new TypeLiteral<List<? extends Integer>>() { }.getType();
            var extendsNumber = new TypeLiteral<List<? extends Number>>() { }.getType();

            assertAll(
                    () -> assertTrue(beanManager.isMatchingEvent(listOfNumbers, Set.of(), superInteger, Set.of()),
                            "List<Number> observed as List<? super Integer>"),
                    () -> assertFalse(beanManager.isMatchingEvent(listOfStrings, Set.of(), superInteger, Set.of()),
                            "List<String> observed as List<? super Integer>"),
                    () -> assertTrue(beanManager.isMatchingEvent(extendsInteger, Set.of(), listOfNumbers, Set.of()),
                            "List<? extends Integer> observed as List<Number>"),
                    () -> assertFalse(beanManager.isMatchingEvent(extendsNumber, Set.of(), listOfIntegers, Set.of()),
                            "List<? extends Number> observed as List<Integer>"));
        }
    }

    /**
     * An injection point of a non-contextual instance resolves by the rules of a bean's: a producer of a raw
     * {@code Foo} does not satisfy {@code @Inject Foo<Integer>} (CDI 4.1, 5.2.4). When {@code getBeans} finds no
     * bean, the injection falls back on the erased class, {@code container.select(Foo.class)}, which the raw producer
     * satisfies (BUG-20261117-28).
     */
    @Test
    @Disabled("BUG-20261117-28: a non-contextual injection point falls back on its erased class")
    @DisplayName("a non-contextual injection point is not satisfied by a bean the strict rule refuses")
    void aNonContextualInjectionPointIsNotSatisfiedByABeanTheStrictRuleRefuses() {
        try (var container = VaubanContainer.builder().addBeanClass(RawFooFactory.class).build()) {
            var beanManager = container.getBeanManager();
            var target = beanManager.getInjectionTargetFactory(beanManager.createAnnotatedType(
                    FooOfIntegerClient.class)).createInjectionTarget(null);
            var client = new FooOfIntegerClient();
            try {
                target.inject(client, beanManager.createCreationalContext(null));
            } catch (RuntimeException _) {
                // Refusing the injection point is what the specification asks.
            }
            assertNull(client.foo, "the raw Foo producer satisfied Foo<Integer>");
        }
    }

    /**
     * The parameter of an initializer of a non-contextual instance resolves by the same rules: when {@code getBeans}
     * finds no bean, {@code VaubanContainer.resolveParameter} falls back on the erased class,
     * {@code select(Foo.class)}, which the raw producer satisfies (BUG-20261117-28).
     */
    @Test
    @Disabled("BUG-20261117-28: a non-contextual injection point falls back on its erased class")
    @DisplayName("a non-contextual initializer parameter is not satisfied by a bean the strict rule refuses")
    void aNonContextualInitializerParameterIsNotSatisfiedByABeanTheStrictRuleRefuses() {
        try (var container = VaubanContainer.builder().addBeanClass(RawFooFactory.class).build()) {
            FooOfIntegerInitializedClient client = null;
            try {
                client = new jakarta.enterprise.inject.spi.Unmanaged<>(container.getBeanManager(),
                        FooOfIntegerInitializedClient.class).newInstance().produce().inject().get();
            } catch (RuntimeException _) {
                // Refusing the injection point is what the specification asks.
            }
            assertTrue(client == null || client.foo == null, "the raw Foo producer satisfied Foo<Integer>");
        }
    }

    /**
     * An observer inherited from {@code HolderListener<X>} by {@code TextHolderListener extends
     * HolderListener<String>} is given its {@code Holder<X>} parameter as a {@code Holder<String>}, which the bean of
     * {@code TextHolder} satisfies: the call resolves {@code X} ({@code EventDispatcher.resolveObserverParamType}).
     * The start does not check an inherited observer's parameters (BUG-20261117-26); once it does, it has to resolve
     * {@code X} too.
     */
    @Test
    @DisplayName("an inherited observer's parameter of its type variable resolves as the subclass gives it")
    void anInheritedObserversParameterOfItsTypeVariableResolvesAsTheSubclassGivesIt() {
        var builder = VaubanContainer.builder().addBeanClass(TextHolder.class).addBeanClass(TextHolderListener.class);

        RECEIVED.clear();
        try (var container = assertDoesNotThrow(builder::build, "the container refused the parameter")) {
            container.getBeanManager().getEvent().select(Pang.class).fire(new Pang());
            assertEquals(List.of(TextHolder.class), classesOf(RECEIVED), "the observer's arguments");
        }
    }

    /**
     * An observer's parameter that no bean satisfies fails the start, the observer inherited or not. The check reads
     * the observer from the methods the bean class declares ({@code VaubanContainer.observerMethodNamed}), and an
     * inherited one is not there: the start passes, and the call fails at the first event (BUG-20261117-26).
     */
    @Test
    @DisplayName("an inherited observer's unsatisfied parameter fails the start")
    void anInheritedObserversUnsatisfiedParameterFailsTheStart() {
        var builder = VaubanContainer.builder().addBeanClass(TextHolder.class)
                .addBeanClass(InheritedIntegerHolderListener.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the parameter resolved");
        assertTrue(refused.getMessage().contains("unsatisfied dependency for parameter arg1 of type "
                + Holder.class.getName() + "<java.lang.Integer>"), refused::getMessage);
    }

    /**
     * An observer a bean inherits keeps its parameter of a type variable when the bean class passes its own type
     * variable on, {@code GenericListenerOfVetoed<T> extends VetoedTypeVariableListener<T>}: an injection point of a
     * type variable on a class that may be a bean, a definition error (CDI 4.1, 5.2.3). The definition validation
     * reads the class that declares the observer, which is no bean, and the start's check of the observer's
     * parameters did not see the inherited observer (BUG-20261117-26).
     */
    @Test
    @DisplayName("an inherited observer's parameter its bean class leaves a type variable is a definition error")
    void anInheritedObserversParameterItsBeanClassLeavesATypeVariableIsADefinitionError() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class)
                .addBeanClass(VetoedTypeVariableListener.class).addBeanClass(GenericListenerOfVetoed.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains(
                TYPE_VARIABLE_PARAMETER + GenericListenerOfVetoed.class.getName() + ".on observer param 1"),
                refused::getMessage);
    }

    /**
     * An {@code @Inject} field of a type variable is an injection point of a type variable, a definition error (CDI
     * 4.1, 5.2.3; TCK audit {@code legal_injection_point_types} c). No validator checks a field for one, and the
     * start looks the field up by its erasure, {@code Object}: it reports an ambiguous dependency, a deployment
     * problem (BUG-20261117-29).
     */
    @Test
    @Disabled("BUG-20261117-29: an @Inject field of a type variable is not a definition error")
    @DisplayName("an @Inject field of a type variable is a definition error")
    void anInjectFieldOfATypeVariableIsADefinitionError() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(GadgetField.class);

        var refused = assertThrows(DefinitionException.class, builder::build, "no definition error");
        assertTrue(refused.getMessage().contains(TYPE_VARIABLE_PARAMETER)
                && refused.getMessage().contains(GadgetField.class.getName() + ".gadget"), refused::getMessage);
    }

    /**
     * An initializer a bean inherits from a class that is no bean, abstract or vetoed, has its parameter of a type
     * variable resolved by the bean class, {@code T} as {@code Gadget}, as an inherited observer has. The start
     * checked the parameters of every {@code @Inject} method of the index for a type variable, whatever the class
     * ({@code ClassValidator.validateInjectionParameters}), and refused the deployment (BUG-20261117-30).
     */
    @Test
    @DisplayName("an inherited initializer's parameter of a type variable its bean class gives a class is legal")
    void anInheritedInitializersParameterOfATypeVariableItsBeanClassGivesAClassIsLegal() {
        var builder = VaubanContainer.builder().addBeanClass(Gadget.class).addBeanClass(AbstractGadgetSetter.class)
                .addBeanClass(GadgetSetterOfAbstract.class).addBeanClass(VetoedGadgetSetter.class)
                .addBeanClass(GadgetSetterOfVetoed.class);

        try (var container = assertDoesNotThrow(builder::build, "the container refused the deployment")) {
            assertAll(
                    () -> assertInstanceOf(Gadget.class, container.select(GadgetSetterOfAbstract.class).gadget),
                    () -> assertInstanceOf(Gadget.class, container.select(GadgetSetterOfVetoed.class).gadget));
        }
    }

    private static List<Class<?>> classesOf(List<Object> instances) {
        return instances.stream().<Class<?>>map(Object::getClass).toList();
    }

    /** The bean types a bean manager keeps converted, read from its private cache. */
    @SuppressWarnings("unchecked")
    private static Map<java.lang.reflect.Type, Object> convertedTypes(Object beanManager)
            throws ReflectiveOperationException {
        var field = beanManager.getClass().getDeclaredField("convertedTypes");
        field.setAccessible(true);
        return (Map<java.lang.reflect.Type, Object>) field.get(beanManager);
    }
}
