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

import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.EventMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BUG-20261118-11 - the injection points of a static or an inherited observer")
class ObserverInjectionPointsTest {

    private static final TypeInfo INTEGER = new TypeInfo.ClassType(DotName.of(Integer.class.getName()));

    @SuppressWarnings("java:S1172") // the parameters are the shapes under test
    public abstract static class Base<X> {
        void on(@Observes String event, List<? extends X> upper, X[] array, Map<X, ? super X> lower,
                EventMetadata metadata) {
            // no behaviour needed
        }
    }

    @Dependent
    public static class Sub extends Base<Integer> {
    }

    @Dependent
    public static class GenericSub<Y> extends Base<Y> {
    }

    /** Declares the instance observer {@code on}, which its own injection points hold: the inherited one is it. */
    @Dependent
    public static class Overriding extends Base<Integer> {
        @Override
        void on(@Observes String event, List<? extends Integer> upper, Integer[] array,
                Map<Integer, ? super Integer> lower, EventMetadata metadata) {
            // no behaviour needed
        }
    }

    /**
     * Declares {@code on} with the inherited observer's erased parameters, observing nothing: the start finds no
     * observer there, the inherited one overridden (review round 2).
     */
    @Dependent
    public static class NotObservingOverride extends Base<Integer> {
        @Override
        void on(String event, List<? extends Integer> upper, Integer[] array, Map<Integer, ? super Integer> lower,
                EventMetadata metadata) {
            // no behaviour needed
        }
    }

    /** An observer of the same name and other parameters: an overload, the inherited observer still one. */
    @Dependent
    @SuppressWarnings("java:S1172") // the parameter is the shape under test
    public static class OverloadingSub extends Base<Integer> {
        void on(@Observes Integer event) {
            // no behaviour needed
        }
    }

    @SuppressWarnings({"java:S1172", "unused"}) // the shape under test: a private observer no subclass inherits
    public abstract static class PrivateBase {
        private void on(@Observes String event, Integer count) {
            // no behaviour needed
        }
    }

    @Dependent
    public static class PrivateSub extends PrivateBase {
    }

    @Dependent
    @SuppressWarnings("java:S1172") // the parameter is the shape under test
    public static class StaticOwn {
        static void on(@Observes String event, Integer count) {
            // no behaviour needed
        }
    }

    private static ClassInfo scan(Class<?> clazz) throws IOException {
        try (var in = clazz.getResourceAsStream("/" + clazz.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            return ClassFileScanner.scan(in.readAllBytes());
        }
    }

    private static List<TypeInfo> points(Class<?> beanClass, boolean baseIndexed) throws IOException {
        return points(beanClass, Base.class, baseIndexed);
    }

    private static List<TypeInfo> points(Class<?> beanClass, Class<?> superClass, boolean baseIndexed)
            throws IOException {
        var builder = new IndexBuilder().add(scan(beanClass));
        var base = scan(superClass);
        if (baseIndexed) {
            builder.add(base);
        }
        var discovery = new BeanDiscovery(builder.build());
        var outside = baseIndexed ? Optional.<ClassInfo>empty() : Optional.of(base);
        return discovery.otherObserverInjectionPoints(scan(beanClass), name -> outside)
                .stream().map(InjectionPointInfo::requiredType).toList();
    }

    @Test
    @DisplayName("an inherited observer's parameters are read as the bean class gives its superclass's variables")
    void inheritedParametersAreReadAsTheBeanClassGivesThem() throws IOException {
        var expected = List.of(
                new TypeInfo.ParameterizedType(DotName.of(List.class.getName()),
                        List.of(new TypeInfo.WildcardType(INTEGER, null))),
                new TypeInfo.ArrayType(INTEGER, 1),
                new TypeInfo.ParameterizedType(DotName.of(Map.class.getName()),
                        List.of(INTEGER, new TypeInfo.WildcardType(null, INTEGER))));
        assertEquals(expected, points(Sub.class, true), "indexed superclass");
        assertEquals(expected, points(Sub.class, false), "superclass described from outside the index");
    }

    @Test
    @DisplayName("a parameter left a type variable, or an observer a class below declares, is none")
    void aLeftTypeVariableOrAnOverriddenObserverIsNone() throws IOException {
        assertTrue(points(GenericSub.class, true).isEmpty(), "type variables left");
        assertTrue(points(Overriding.class, true).isEmpty(), "overridden observer");
    }

    /**
     * The start's rules ({@code ObserverDisposerDiscovery.getAllInheritedMethods}): a private superclass method is not
     * inherited, and a method the bean class declares with the same name and erased parameters overrides it, an
     * observer or not; one of other parameters does not (BUG-20261118-11, review round 2).
     */
    @Test
    @DisplayName("an observer is inherited as the start finds it: not private, not overridden by name and parameters")
    void anObserverIsInheritedAsTheStartFindsIt() {
        assertAll(() -> assertTrue(points(NotObservingOverride.class, true).isEmpty(), "overridden without @Observes"),
                () -> assertEquals(3, points(OverloadingSub.class, true).size(), "an overload of other parameters"),
                () -> assertTrue(points(PrivateSub.class, PrivateBase.class, true).isEmpty(),
                        "a private superclass observer"));
    }

    @Test
    @DisplayName("a static observer's parameters are injection points")
    void aStaticObserversParametersAreInjectionPoints() throws IOException {
        assertEquals(List.of(INTEGER), points(StaticOwn.class, true));
    }
}
