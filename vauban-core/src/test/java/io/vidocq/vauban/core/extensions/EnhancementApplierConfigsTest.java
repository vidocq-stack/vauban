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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.langmodel.BuiltAnnotationInfo;
import io.vidocq.vauban.core.langmodel.BuiltAnnotationMember;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link EnhancementApplier} makes of the configs an {@code @Enhancement} method left: added priorities on
 * interceptors, vetoes and removals on beans, and the qualifiers of observed parameters.
 */
class EnhancementApplierConfigsTest {

    private static final DotName LISTENER = DotName.of(Listener.class.getName());
    private static final DotName RED = DotName.of(Red.class.getName());
    private static final String ON_EVENT = "onEvent";
    private static final String VALUE = "value";

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Red {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audited {
    }

    @SuppressWarnings("unused")
    public static class Listener {
        public void onEvent(@Observes String event) {
            // observed through the descriptor only
        }

        public void onEvent() {
            // an overload without the event parameter
        }

        public void other(String text) {
            // never observes
        }

        @Inject
        public static void staticInit(String text) {
            // a static method: never an initializer
        }

        public void setup(String text) {
            // an initializer once an extension adds @Inject
        }
    }

    private static VaubanClassConfig listenerConfig() {
        try (var in = Listener.class.getResourceAsStream("/" + Listener.class.getName().replace('.', '/') + ".class")) {
            var scanned = ClassFileScanner.scan(in.readAllBytes());
            var lookup = new IndexLookup(new IndexBuilder().add(scanned).build());
            return new VaubanClassConfig(new VaubanClassInfo(scanned, lookup));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static VaubanMethodConfig method(VaubanClassConfig config, String name, int parameters) {
        return config.getMethodConfigs().stream()
                .filter(m -> m.info().name().equals(name) && m.info().parameters().size() == parameters)
                .findFirst().orElseThrow();
    }

    private static BuiltAnnotationInfo built(Class<? extends Annotation> type) {
        return new BuiltAnnotationInfo(type, Map.of());
    }

    private static BuiltAnnotationInfo built(Class<? extends Annotation> type, String member, AnnotationMember value) {
        return new BuiltAnnotationInfo(type, Map.of(member, value));
    }

    private static Set<DotName> names(Set<QualifierInstance> qualifiers) {
        return qualifiers.stream().map(QualifierInstance::annotationName).collect(java.util.stream.Collectors.toSet());
    }

    // ---- interceptors ----

    private static InterceptorDescriptor withPriorityConfig(VaubanClassConfig config) {
        var interceptor = new InterceptorDescriptor(LISTENER, Set.of(), "around", 0);
        return EnhancementApplier.applyInterceptorEnhancements(List.of(interceptor), Map.of(LISTENER, List.of(config)))
                .getFirst();
    }

    @Test
    void anInterceptorWithAnEmptyConfigListIsKept() {
        var interceptor = new InterceptorDescriptor(LISTENER, Set.of(), "around", 0);

        var result = EnhancementApplier.applyInterceptorEnhancements(List.of(interceptor), Map.of(LISTENER, List.of()));

        assertSame(interceptor, result.getFirst());
    }

    @Test
    void anAddedPriorityEnablesTheInterceptorWithItsIntValueOrZero() {
        var withValue = listenerConfig();
        withValue.addAnnotation(built(Priority.class, VALUE, BuiltAnnotationMember.ofInt(7)));
        var withoutValue = listenerConfig();
        withoutValue.addAnnotation(built(Priority.class));
        var withText = listenerConfig();
        withText.addAnnotation(built(Priority.class, VALUE, BuiltAnnotationMember.ofString("7")));

        assertEquals(7, withPriorityConfig(withValue).priority());
        assertTrue(withPriorityConfig(withoutValue).enabled());
        assertEquals(0, withPriorityConfig(withoutValue).priority());
        assertEquals(0, withPriorityConfig(withText).priority());
    }

    // ---- beans ----

    private static BeanDescriptor redBean() {
        return new BeanDescriptor(BeanDescriptor.minimal(LISTENER).id(), LISTENER, BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.ClassType(LISTENER)),
                Set.of(new QualifierInstance(RED, Map.of()), QualifierInstance.ANY), null, false, 0, List.of(), null,
                Set.of(DotName.of(Audited.class.getName())));
    }

    private static BeanDescriptor enhanced(VaubanClassConfig config) {
        var result = EnhancementApplier.applyEnhancements(List.of(redBean()), Map.of(LISTENER, List.of(config)));
        return result.getFirst();
    }

    @Test
    void aVetoAddedAsAnAnnotationInfoRemovesTheBean() {
        var config = listenerConfig();
        config.addAnnotation(built(Vetoed.class));

        assertTrue(EnhancementApplier.applyEnhancements(List.of(redBean()), Map.of(LISTENER, List.of(config))).isEmpty());
    }

    @Test
    void removingAllAnnotationsDropsQualifiersAndBindings() {
        var config = listenerConfig();
        config.removeAllAnnotations();

        var bean = enhanced(config);

        assertTrue(bean.qualifiers().isEmpty());
        assertTrue(bean.interceptorBindings().isEmpty());
    }

    @Test
    void addedAnnotationInfosAddBindingsAndUnknownQualifiers() {
        var config = listenerConfig();
        config.addAnnotation(built(Audited.class));
        config.addAnnotation(new SimpleAnnotationInfo("com.acme.Missing", Map.of()));

        var bean = enhanced(config);

        assertTrue(bean.interceptorBindings().contains(DotName.of(Audited.class.getName())));
        assertEquals(Audited.class, bean.interceptorBindingAnnotations().getFirst().annotationType());
        assertFalse(bean.interceptorBindings().contains(DotName.of("com.acme.Missing")));
        assertTrue(names(bean.qualifiers()).contains(DotName.of("com.acme.Missing")));
    }

    @Test
    void anAddedNamedWithoutValueOnAConfigWithoutClassNamesNothing() {
        var config = new VaubanClassConfig(Red.class, new IndexLookup(new IndexBuilder().build()));
        config.addAnnotation(jakarta.enterprise.inject.literal.NamedLiteral.of(""));

        assertNull(config.info());
        assertNull(enhanced(config).name());
    }

    @Test
    void anAddedInjectMakesAMethodAnEnhancedInitializer() {
        var config = listenerConfig();
        method(config, "setup", 1).addAnnotation(Inject.class);
        method(config, "staticInit", 1).addAnnotation(Red.class);

        var bean = enhanced(config);

        assertEquals(1, bean.enhancedInjectionMethods().size());
        assertTrue(bean.enhancedInjectionMethods().iterator().next().contains("setup"));
        assertEquals(1, bean.injectionPoints().size());
    }

    // ---- observers ----

    private static ObserverDescriptor observer() {
        return new ObserverDescriptor(LISTENER, ON_EVENT, new TypeInfo.ClassType(DotName.of("java.lang.String")),
                List.of(QualifierInstance.DEFAULT, QualifierInstance.ANY), false, 0);
    }

    private static List<ObserverDescriptor> observersAfter(VaubanClassConfig config) {
        return EnhancementApplier.applyObserverEnhancements(List.of(observer()), Map.of(LISTENER, List.of(config)));
    }

    @Test
    void anObserverIsUntouchedByConfigsOfOtherMethodsOrOfItsOtherParameters() {
        var config = listenerConfig();
        method(config, "other", 1).addAnnotation(Red.class);
        method(config, ON_EVENT, 0).addAnnotation(Red.class);
        method(config, ON_EVENT, 1).addAnnotation(Red.class);

        assertEquals(observer().qualifiers(), observersAfter(config).getFirst().qualifiers());
    }

    @Test
    void anObservedParameterGainsAddedQualifierInfosAndLosesDefaultOnlyForAnExplicitOne() {
        var implicit = listenerConfig();
        var implicitParameter = method(implicit, ON_EVENT, 1).getParameterConfigs().getFirst();
        implicitParameter.addAnnotation(built(Named.class, VALUE, BuiltAnnotationMember.ofString("n")));
        implicitParameter.addAnnotation(built(Any.class));
        implicitParameter.addAnnotation(built(Default.class));
        var explicit = listenerConfig();
        method(explicit, ON_EVENT, 1).getParameterConfigs().getFirst().addAnnotation(built(Red.class));

        assertTrue(names(Set.copyOf(observersAfter(implicit).getFirst().qualifiers())).contains(QualifierInstance.DEFAULT_NAME));
        assertEquals(Set.of(RED, QualifierInstance.ANY_NAME), names(Set.copyOf(observersAfter(explicit).getFirst().qualifiers())));
    }

    @Test
    void aRemovalPredicateDropsOnlyTheQualifiersItMatches() {
        var config = listenerConfig();
        method(config, ON_EVENT, 1).getParameterConfigs().getFirst()
                .removeAnnotation(info -> info.name().equals(QualifierInstance.DEFAULT_NAME.value()));

        assertEquals(Set.of(QualifierInstance.ANY_NAME), names(Set.copyOf(observersAfter(config).getFirst().qualifiers())));
    }
}
