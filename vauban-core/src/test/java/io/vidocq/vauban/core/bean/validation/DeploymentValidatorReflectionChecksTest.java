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
package io.vidocq.vauban.core.bean.validation;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployment checks {@link DeploymentValidator} makes by reflection on classes the index does not describe:
 * normal-scoped producers of primitive types, intercepted bean classes, normal-scoped beans that cannot be proxied,
 * interceptor constructors, and the bean metadata an interceptor may inject.
 */
@DisplayName("DeploymentValidator: checks made on loaded classes and on descriptors")
class DeploymentValidatorReflectionChecksTest {

    private static final String MISSING_CLASS = "io.vidocq.vauban.core.bean.validation.NoSuchClass";
    private static final String OBJECT = "java.lang.Object";
    private static final String BEAN = "jakarta.enterprise.inject.spi.Bean";
    private static final String BINDING = "io.vidocq.vauban.core.bean.validation.Binding";

    public static class WithFinalMethod {
        public final String fixed() {
            return "fixed";
        }
    }

    public static class InheritsFinalMethod extends WithFinalMethod {
    }

    /** Only private or static final methods: still subclassable. */
    public static class WithHarmlessFinals {
        private final String hidden() {
            return "hidden";
        }

        public static final String shared() {
            return "shared";
        }

        public String use() {
            return hidden();
        }
    }

    public static final class FinalClass {
    }

    public static class PrivateConstructor {
        private PrivateConstructor() {
        }

        public static PrivateConstructor create() {
            return new PrivateConstructor();
        }
    }

    public static class ArgumentConstructor {
        public ArgumentConstructor(String argument) {
            argument.length();
        }
    }

    /** No no-arg constructor, but a bean constructor: CDI 4.1 "Declaring a bean constructor". */
    public static class InjectConstructor {
        @Inject
        InjectConstructor(String argument) {
            argument.length();
        }
    }

    public static class TwoInjectConstructors {
        @Inject
        public TwoInjectConstructors(String argument) {
            argument.length();
        }

        @Inject
        public TwoInjectConstructors(Integer argument) {
            argument.intValue();
        }
    }

    @Interceptor
    public static class SomeInterceptor {
    }

    private static TypeInfo cls(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    private static TypeInfo cls(Class<?> type) {
        return cls(type.getName());
    }

    private static BeanDescriptor bean(String id, Class<?> beanClass, BeanDescriptor.BeanKind kind, ScopeInfo scope,
                                       Set<TypeInfo> types, List<InjectionPointInfo> points, Set<DotName> bindings) {
        return new BeanDescriptor(new BeanId(id), DotName.of(beanClass.getName()), kind, types,
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), scope, false, 0, points, null, bindings);
    }

    private static BeanDescriptor managed(Class<?> beanClass, ScopeInfo scope, Set<DotName> bindings) {
        return bean(beanClass.getName(), beanClass, BeanDescriptor.BeanKind.MANAGED, scope,
                Set.of(cls(beanClass), cls(OBJECT)), List.of(), bindings);
    }

    private static BeanDescriptor named(String id, String name, boolean alternative, int priority) {
        return new BeanDescriptor(new BeanId(id), DotName.of(WithHarmlessFinals.class.getName()),
                BeanDescriptor.BeanKind.MANAGED, Set.of(cls(OBJECT)),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.DEPENDENT, alternative, priority,
                List.of(), name);
    }

    private static List<String> messages(List<BeanDescriptor> beans) {
        return messages(beans, List.of());
    }

    private static List<String> messages(List<BeanDescriptor> beans, List<InterceptorDescriptor> interceptors) {
        var resolver = new BeanResolver(beans, interceptors, new AssignabilityRules(new IndexBuilder().build()));
        return new DeploymentValidator(beans, resolver).validate().stream()
                .map(DeploymentValidator.ValidationError::message).toList();
    }

    @Test
    @DisplayName("a normal-scoped producer of a primitive type, as the index names it, is a deployment error")
    void normalScopedPrimitiveProducer() {
        var indexNamed = bean("Host#count", WithHarmlessFinals.class, BeanDescriptor.BeanKind.PRODUCER_METHOD,
                ScopeInfo.APPLICATION, Set.of(cls("int"), cls(OBJECT)), List.of(), Set.of());
        var primitive = bean("Host#size", WithHarmlessFinals.class, BeanDescriptor.BeanKind.PRODUCER_METHOD,
                ScopeInfo.APPLICATION, Set.of(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.LONG)),
                List.of(), Set.of());
        var plain = bean("Host#text", WithHarmlessFinals.class, BeanDescriptor.BeanKind.PRODUCER_FIELD,
                ScopeInfo.APPLICATION, Set.of(cls("java.lang.String")), List.of(), Set.of());

        var messages = messages(List.of(indexNamed, primitive, plain));

        assertEquals(2, messages.size(), messages::toString);
        assertTrue(messages.getFirst().contains("has primitive return type"), messages::toString);
    }

    @Test
    @DisplayName("an intercepted bean with an overridable final method, its own or inherited, is a deployment error")
    void interceptedBeansWithFinalMethods() {
        var bindings = Set.of(DotName.of(BINDING));

        var messages = messages(List.of(
                managed(WithFinalMethod.class, ScopeInfo.DEPENDENT, bindings),
                managed(InheritsFinalMethod.class, ScopeInfo.DEPENDENT, bindings),
                managed(WithHarmlessFinals.class, ScopeInfo.DEPENDENT, bindings)));

        assertEquals(2, messages.size(), messages::toString);
        assertTrue(messages.stream().allMatch(m -> m.contains("has final method fixed")), messages::toString);
    }

    @Test
    @DisplayName("interceptors, normal-scoped beans, producers and unloadable classes are not checked as intercepted classes")
    void interceptedChecksSkipped() {
        var bindings = Set.of(DotName.of(BINDING));
        var missing = new BeanDescriptor(new BeanId(MISSING_CLASS), DotName.of(MISSING_CLASS),
                BeanDescriptor.BeanKind.MANAGED, Set.of(cls(MISSING_CLASS)),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), ScopeInfo.DEPENDENT, false, 0, List.of(),
                null, bindings);

        var producer = bean("Host#bound", WithFinalMethod.class, BeanDescriptor.BeanKind.PRODUCER_METHOD,
                ScopeInfo.DEPENDENT, Set.of(cls("java.lang.String")), List.of(), bindings);

        var messages = messages(List.of(
                managed(SomeInterceptor.class, ScopeInfo.DEPENDENT, bindings),
                managed(WithFinalMethod.class, ScopeInfo.REQUEST, bindings),
                producer,
                missing));

        assertEquals(List.of(), messages);
    }

    @Test
    @DisplayName("a disabled alternative's name is no prefix of another; an enabled one's is")
    void namePrefixesOfAlternatives() {
        var disabled = messages(List.of(named("a", "shop", true, 0), named("b", "shop.cart", false, 0)));
        var enabled = messages(List.of(named("a", "shop", true, 10), named("b", "shop.cart", false, 0)));

        assertEquals(List.of(), disabled);
        assertEquals(1, enabled.size(), enabled::toString);
        assertTrue(enabled.getFirst().contains("is a prefix of"), enabled::toString);
    }

    @Test
    @DisplayName("an interceptor with neither a non-private no-arg nor an @Inject constructor is a definition error")
    void interceptorConstructors() {
        var interceptors = List.of(
                new InterceptorDescriptor(DotName.of(PrivateConstructor.class.getName()), Set.of(), "around", 10),
                new InterceptorDescriptor(DotName.of(ArgumentConstructor.class.getName()), Set.of(), "around", 10),
                new InterceptorDescriptor(DotName.of(WithHarmlessFinals.class.getName()), Set.of(), "around", 10),
                new InterceptorDescriptor(DotName.of(MISSING_CLASS), Set.of(), "around", 10));

        var messages = messages(List.of(), interceptors);

        assertEquals(2, messages.size(), messages::toString);
        assertTrue(messages.stream().allMatch(m -> m.contains("must have a non-private no-arg constructor")),
                messages::toString);
    }

    @Test
    @DisplayName("an interceptor may have one @Inject constructor instead of a no-arg one, but not two")
    void interceptorInjectConstructors() {
        var interceptors = List.of(
                new InterceptorDescriptor(DotName.of(InjectConstructor.class.getName()), Set.of(), "around", 10),
                new InterceptorDescriptor(DotName.of(TwoInjectConstructors.class.getName()), Set.of(), "around", 10));

        var messages = messages(List.of(), interceptors);

        assertEquals(1, messages.size(), messages::toString);
        assertTrue(messages.getFirst().contains(TwoInjectConstructors.class.getName()
                + " declares more than one @Inject constructor"), messages::toString);
    }

    @Test
    @DisplayName("a normal-scoped bean that is final, has no usable no-arg constructor, or a final method cannot be proxied")
    void unproxyableNormalScopedBeans() {
        var messages = messages(List.of(
                injecting(FinalClass.class), managed(FinalClass.class, ScopeInfo.APPLICATION, Set.of()),
                injecting(PrivateConstructor.class), managed(PrivateConstructor.class, ScopeInfo.APPLICATION, Set.of()),
                injecting(WithFinalMethod.class), managed(WithFinalMethod.class, ScopeInfo.APPLICATION, Set.of()),
                injecting(ArgumentConstructor.class), managed(ArgumentConstructor.class, ScopeInfo.APPLICATION, Set.of())));

        assertEquals(4, messages.size(), messages::toString);
        assertTrue(messages.stream().anyMatch(m -> m.contains("cannot be a final class")), messages::toString);
        assertTrue(messages.stream().anyMatch(m -> m.contains("has final method fixed")), messages::toString);
    }

    /** A bean of its own class injecting {@code target}; its id keeps it apart from the target's bean. */
    private static BeanDescriptor injecting(Class<?> target) {
        var point = new InjectionPointInfo(cls(target), Set.of(QualifierInstance.DEFAULT),
                InjectionPointInfo.InjectionKind.FIELD, "field Client." + target.getSimpleName());
        return bean("client-of-" + target.getSimpleName(), WithHarmlessFinals.class, BeanDescriptor.BeanKind.MANAGED,
                ScopeInfo.DEPENDENT, Set.of(cls("client.Of" + target.getSimpleName())), List.of(point), Set.of());
    }

    static Stream<Arguments> interceptedBeanArguments() {
        return Stream.of(
                Arguments.of(new TypeInfo.WildcardType(cls(OBJECT), null), false),
                Arguments.of(cls(OBJECT), false),
                Arguments.of(new TypeInfo.WildcardType(cls("java.lang.String"), null), true),
                Arguments.of(new TypeInfo.WildcardType(cls(OBJECT), cls("java.lang.String")), true),
                Arguments.of(cls("java.lang.String"), true),
                Arguments.of(new TypeInfo.WildcardType(
                        new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(cls(OBJECT))), null), true),
                Arguments.of(
                        new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(cls(OBJECT))), true));
    }

    @ParameterizedTest(name = "@Intercepted Bean<{0}> illegal: {1}")
    @MethodSource("interceptedBeanArguments")
    @DisplayName("an interceptor may inject @Intercepted Bean<?> or Bean<Object>, nothing narrower")
    void interceptedBeanMetadata(TypeInfo argument, boolean illegal) {
        var intercepted = new QualifierInstance(DotName.of("jakarta.enterprise.inject.Intercepted"), Map.of());
        var point = new InjectionPointInfo(new TypeInfo.ParameterizedType(DotName.of(BEAN), List.of(argument)),
                Set.of(intercepted), InjectionPointInfo.InjectionKind.FIELD, "field SomeInterceptor.bean");
        var interceptor = bean(SomeInterceptor.class.getName(), SomeInterceptor.class, BeanDescriptor.BeanKind.MANAGED,
                ScopeInfo.DEPENDENT, Set.of(cls(SomeInterceptor.class)), List.of(point), Set.of());

        var messages = messages(List.of(interceptor));

        assertEquals(illegal, messages.stream().anyMatch(m -> m.startsWith("Illegal injection of built-in metadata")),
                messages::toString);
    }

    @Test
    @DisplayName("Bean<X> parameterized with anything but the bean's own class is illegal")
    void beanMetadataOfAnotherType() {
        var point = new InjectionPointInfo(new TypeInfo.ParameterizedType(DotName.of(BEAN),
                List.of(new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(cls(OBJECT))))),
                Set.of(QualifierInstance.DEFAULT), InjectionPointInfo.InjectionKind.FIELD, "field Host.bean");
        var host = bean("host", WithHarmlessFinals.class, BeanDescriptor.BeanKind.MANAGED, ScopeInfo.DEPENDENT,
                Set.of(cls(WithHarmlessFinals.class)), List.of(point), Set.of());

        var messages = messages(List.of(host));

        assertEquals(1, messages.size(), messages::toString);
    }
}
