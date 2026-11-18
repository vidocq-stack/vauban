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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CDI 4.1 calls a {@code @Registration} method with an {@code InterceptorInfo} parameter once for each interceptor
 * whose bean types contain an expected type, and for no other bean. The container handed it the {@code BeanInfo} of
 * each bean too, which the method cannot take: the deployment failed with "argument type mismatch"
 * (BUG-20261117-02).
 */
@DisplayName("@Registration with an InterceptorInfo parameter sees the interceptors only (BUG-20261117-02)")
class BceRegistrationInterceptorInfoTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Traced {
    }

    @Traced
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class Tracer {

        @AroundInvoke
        Object trace(InvocationContext invocation) throws Exception {
            return "traced " + invocation.proceed();
        }
    }

    @Traced
    @Dependent
    public static class TracedService {

        public String hello() {
            return "hello";
        }
    }

    /**
     * Records the interceptors {@code @Registration} gives it: any interceptor, and one whose bean types hold its
     * interceptor binding type, which none has: a binding type is no type of the interceptor.
     */
    public static class InterceptorRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void interceptors(InterceptorInfo interceptor) {
            SEEN.add("interceptor " + interceptor.declaringClass().name());
        }

        @Registration(types = Traced.class)
        public void bindings(InterceptorInfo interceptor) {
            SEEN.add("binding " + interceptor.declaringClass().name());
        }
    }

    @Test
    @DisplayName("an InterceptorInfo parameter is given each interceptor of an expected type, and no bean")
    void anInterceptorInfoParameterIsGivenEachInterceptorAndNoBean() {
        InterceptorRecorder.SEEN.clear();

        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(Tracer.class, TracedService.class, InterceptorRecorder.class).initialize()) {
            assertEquals("traced hello", container.select(TracedService.class).get().hello());
        }

        assertEquals(List.of("interceptor " + Tracer.class.getName()), List.copyOf(InterceptorRecorder.SEEN));
    }

    /** A type no bean and no interceptor has. */
    public interface Marker {
    }

    /** A class of the deployment that is no bean, as it is abstract, and has the type {@link Marker}. */
    public abstract static class MarkedPart implements Marker {
    }

    /** Records what {@code @Registration(types = Marker.class)} gives a {@code BeanInfo} and an {@code InterceptorInfo}. */
    public static class MarkerRecorder implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Marker.class)
        public void beans(BeanInfo bean) {
            SEEN.add("bean " + bean.declaringClass().name());
        }

        @Registration(types = Marker.class)
        public void interceptors(InterceptorInfo interceptor) {
            SEEN.add("interceptor " + interceptor.declaringClass().name());
        }
    }

    /** What {@code MarkerRecorder} records when the container starts with {@code MarkedPart} and no bean of its type. */
    private static List<String> markedAtStart() {
        MarkerRecorder.SEEN.clear();
        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(MarkedPart.class, TracedService.class, MarkerRecorder.class).initialize()) {
            assertEquals("hello", container.select(TracedService.class).get().hello());
        }
        return List.copyOf(MarkerRecorder.SEEN);
    }

    /**
     * Pins what the container does today when no bean has an expected type: it gives a {@code BeanInfo} parameter the
     * classes of the deployment that have it, beans or not, here the abstract {@code MarkedPart}. CDI 4.1 calls it for
     * beans only, and the processor, which cannot load the classes the compilation compiles, does not make the call:
     * BUG-20261117-06 tracks it ({@link #aBeanInfoParameterIsGivenNoClassThatIsNoBean}). An {@code InterceptorInfo}
     * parameter takes no such class: it was given one, and the deployment failed with "argument type mismatch"
     * (BUG-20261117-02).
     */
    @Test
    @DisplayName("an InterceptorInfo parameter is given no class of the deployment that is no interceptor")
    void anInterceptorInfoParameterIsGivenNoClassOfTheDeploymentThatIsNoInterceptor() {
        assertEquals(List.of("bean " + MarkedPart.class.getName()), markedAtStart());
    }

    /**
     * CDI 4.1 calls a {@code BeanInfo} parameter once for each bean whose bean types hold an expected type: the
     * abstract {@code MarkedPart} is no bean (BUG-20261117-06).
     */
    @Test
    @Disabled("BUG-20261117-06: with no bean of an expected type, a BeanInfo parameter is given classes that are no beans")
    @DisplayName("a BeanInfo parameter is given no class of the deployment that is no bean")
    void aBeanInfoParameterIsGivenNoClassThatIsNoBean() {
        assertEquals(List.of(), markedAtStart());
    }

    @Dependent
    public static class Clock {
    }

    @Traced
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class TimingTracer {

        @Inject
        Clock clock;

        @AroundInvoke
        Object trace(InvocationContext invocation) throws Exception {
            return invocation.proceed();
        }
    }

    /** Records the bean types and the injection points of the interceptors {@code @Registration} gives it. */
    public static class InterceptorDescriber implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = Object.class)
        public void interceptors(InterceptorInfo interceptor) {
            interceptor.types().forEach(type -> SEEN.add("type " + nameOf(type)));
            interceptor.qualifiers().forEach(qualifier -> SEEN.add("qualifier " + qualifier.name()));
            interceptor.injectionPoints().forEach(point -> SEEN.add("injection point " + nameOf(point.type())));
        }

        private static String nameOf(jakarta.enterprise.lang.model.types.Type type) {
            return type.isClass() ? type.asClass().declaration().name() : type.toString();
        }
    }

    /**
     * CDI 4.1 gives an interceptor's {@code BeanInfo} its bean types and its injection points, as a bean's. An
     * extension that synthesizes a bean for each injection point it sees, as Ravel's does for {@code @ConfigProperty},
     * misses those of an interceptor (BUG-20261117-04).
     */
    @Test
    @DisplayName("an InterceptorInfo gives the interceptor's bean types and injection points")
    void anInterceptorInfoGivesTheBeanTypesAndInjectionPoints() {
        InterceptorDescriber.SEEN.clear();

        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(TimingTracer.class, Clock.class, TracedService.class, InterceptorDescriber.class)
                .initialize()) {
            assertEquals("hello", container.select(TracedService.class).get().hello());
        }

        assertEquals(List.of("injection point " + Clock.class.getName(),
                        "qualifier jakarta.enterprise.inject.Any", "qualifier jakarta.enterprise.inject.Default",
                        "type " + TimingTracer.class.getName(),
                        "type java.lang.Object"),
                InterceptorDescriber.SEEN.stream().sorted().toList());
    }
}
