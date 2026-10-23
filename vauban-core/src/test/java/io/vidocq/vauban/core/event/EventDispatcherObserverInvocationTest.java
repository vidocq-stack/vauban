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
package io.vidocq.vauban.core.event;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObserverException;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.TransientReference;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How {@link EventDispatcher} calls an observer method (CDI 4.1 §10.5): the declaring class it loads, the
 * conditional observer it skips, the arguments it injects besides the event, and the runtime event type its
 * {@link EventMetadata} reports.
 */
class EventDispatcherObserverInvocationTest {

    private static final String ON = "on";
    private static final String EVENT = "event";
    private static final List<Object> RECEIVED = new CopyOnWriteArrayList<>();

    private static VaubanContainer container;

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Red {
    }

    /** A runtime annotation that is no qualifier. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Note {
    }

    @Dependent
    public static class Part {
        public String label() {
            return "part";
        }
    }

    @Red
    @Dependent
    public static class RedPart extends Part {
        @Override
        public String label() {
            return "red";
        }
    }

    /** Not a bean: its static observer methods are called without an instance. */
    public static final class NotABean {
        private NotABean() {
        }

        public static void on() {
            RECEIVED.add("no event");
        }

        public static void on(@Observes String event) {
            RECEIVED.add(event);
        }

        public static void injected(@Observes String event, @TransientReference @Red Part red, @Note Part plain,
                Provider<Part> provider, @SuppressWarnings("rawtypes") Instance raw, Instance<List<String>> generic,
                Event<String> events) {
            RECEIVED.add(red.label() + "," + plain.label() + "," + provider.get().label() + ","
                    + (raw != null) + "," + (generic != null) + "," + (events != null));
        }

        public static <N extends Number> void numbers(@Observes N number) {
            RECEIVED.add(number);
        }

        public static void typed(@Observes List<String> event, EventMetadata metadata) {
            RECEIVED.add(metadata.getType());
        }
    }

    @ApplicationScoped
    public static class Lazy {
        public void touch() {
            // creates the contextual instance
        }

        public void on(@Observes String event) {
            RECEIVED.add("lazy " + event);
        }
    }

    public interface SubList<E> extends List<E> {
    }

    /** A runtime event class with its own type parameter, under the selected type's raw class. */
    @SuppressWarnings("java:S2160")
    public static class MyList<E> extends ArrayList<E> implements SubList<E> {
    }

    /** Selected types, read off these fields' declarations. */
    @SuppressWarnings({"unused", "java:S1068"})
    private static final class Selected {
        List<String> list;
        ArrayList<String> arrayList;
    }

    @BeforeAll
    static void boot() {
        container = VaubanContainer.builder()
                .addBeanClass(Part.class)
                .addBeanClass(RedPart.class)
                .addBeanClass(Lazy.class)
                .build();
    }

    @AfterAll
    static void shutdown() {
        container.close();
    }

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    private static ObserverDescriptor observer(Class<?> declaring, String method, TypeInfo eventType, String reception) {
        return new ObserverDescriptor(DotName.of(declaring.getName()), method, eventType, List.of(), false, 0,
                reception, null);
    }

    private static TypeInfo classType(Class<?> type) {
        return new TypeInfo.ClassType(DotName.of(type.getName()));
    }

    private static Type selected(String field) throws NoSuchFieldException {
        return Selected.class.getDeclaredField(field).getGenericType();
    }

    @Test
    void anObserverWhoseClassCannotBeLoadedFailsWithAnObserverException() {
        var dispatcher = container.eventDispatcher();
        var missing = new ObserverDescriptor(DotName.of("com.acme.Missing"), ON, classType(String.class), List.of(),
                false, 0);

        var failure = assertThrows(ObserverException.class, () -> dispatcher.invokeObserverDirect(missing, EVENT));

        assertEquals("Failed to invoke observer: com.acme.Missing.on", failure.getMessage());
    }

    @Test
    void aConditionalObserverOfAClassThatIsNoBeanIsStillNotified() {
        container.eventDispatcher().invokeObserverDirect(
                observer(NotABean.class, ON, classType(String.class), "IF_EXISTS"), EVENT);

        assertEquals(List.of(EVENT), List.copyOf(RECEIVED));
    }

    @Test
    void aConditionalObserverIsNotifiedOnlyOnceItsBeanHasAnInstance() {
        var dispatcher = container.eventDispatcher();
        var conditional = observer(Lazy.class, ON, classType(String.class), "IF_EXISTS");

        dispatcher.invokeObserverDirect(conditional, "first");
        container.select(Lazy.class).touch();
        dispatcher.invokeObserverDirect(conditional, "second");

        assertEquals(List.of("lazy second"), List.copyOf(RECEIVED));
    }

    @Test
    void parametersBesidesTheEventAreInjectedWithTheirQualifiers() {
        container.eventDispatcher().invokeObserverDirect(
                observer(NotABean.class, "injected", classType(String.class), null), EVENT);

        assertEquals(List.of("red,part,part,true,true,true"), List.copyOf(RECEIVED));
    }

    @Test
    void aTypeVariableEventParameterObservesAnyEventWithinItsBound() {
        container.eventDispatcher().invokeObserverDirect(
                observer(NotABean.class, "numbers", new TypeInfo.TypeVariable("N", List.of(classType(Number.class))),
                        null), 42);

        assertEquals(List.of(42), List.copyOf(RECEIVED));
    }

    @Test
    void eventMetadataReportsTheRuntimeClassWithTheSelectedTypeArguments() throws NoSuchFieldException {
        var observed = new TypeInfo.ParameterizedType(DotName.of(List.class.getName()), List.of(classType(String.class)));
        var dispatcher = new EventDispatcher(List.of(observer(NotABean.class, "typed", observed, null)), container);

        dispatcher.fire(new MyList<String>(), selected("list"), null);
        dispatcher.fire(new MyList<String>(), selected("arrayList"), null);

        assertEquals(2, RECEIVED.size());
        for (var reported : RECEIVED) {
            var type = assertInstanceOf(ParameterizedType.class, reported);
            assertEquals(MyList.class, type.getRawType());
            assertArrayEquals(new Type[] {String.class}, type.getActualTypeArguments());
        }
    }
}
