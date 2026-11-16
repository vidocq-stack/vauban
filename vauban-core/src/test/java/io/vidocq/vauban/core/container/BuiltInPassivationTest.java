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

import io.vidocq.vauban.api.Passivation;
import io.vidocq.vauban.core.event.EventImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamConstants;
import java.io.UncheckedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in {@code Event}, {@code Instance} and {@code BeanManager} a bean holds move to the importing container,
 * as a web container moves a session when Vidocq swaps the application layer (foy#114). An injected {@code Event} or
 * {@code Instance} writes its injection point, an {@link InjectionPointReference}, which reads back as what the
 * importing container injects there; a bean manager writes a {@link BeanManagerReference}, which reads back as the
 * importing container's. Container A wrote, container B reads, inside {@link Passivation#importInto}.
 */
@DisplayName("Built-in Event, Instance and BeanManager move to the importing container")
class BuiltInPassivationTest {

    private static final String HOLDER = Holder.class.getName();
    private static final String EVENT = Event.class.getName();
    private static final String INSTANCE = Instance.class.getName();
    /** How a message that cannot tell them apart names the built-ins. */
    private static final String BUILT_INS = "an Event, an Instance or a Provider";

    private final List<VaubanContainer> containers = new ArrayList<>();

    @AfterEach
    void close() {
        containers.reversed().forEach(VaubanContainer::close);
    }

    @Test
    @DisplayName("an Instance injected into a constructor reads back as the importing container's")
    void anInstanceInjectedIntoAConstructorMoves() throws Exception {
        var a = container();
        byte[] bytes = bytesOf(a.select(Holder.class).byConstructor);

        var b = container();
        b.select(Shelf.class).add("b");
        var read = (Instance<?>) readIn(b.getBeanManager(), bytes);

        assertSame(b.select(Shelf.class), read.get(), "B's client proxy");
        assertEquals(List.of("b"), ((Shelf) read.get()).marks());
    }

    @Test
    @DisplayName("an Event injected into an initializer method fires in the importing container")
    void anEventInjectedIntoAnInitializerMoves() throws Exception {
        var a = container();
        byte[] bytes = bytesOf(a.select(Holder.class).byInitializer);

        var b = container();
        @SuppressWarnings("unchecked")
        var read = (Event<Ping>) readIn(b.getBeanManager(), bytes);
        read.fire(new Ping("moved"));

        assertEquals(List.of("moved"), b.select(Recorder.class).pings(), "B's observer");
        assertEquals(List.of(), a.select(Recorder.class).pings(), "not A's");
    }

    @Test
    @DisplayName("a Provider field reads back as the importing container's")
    void aProviderFieldMoves() throws Exception {
        var a = container();
        byte[] bytes = bytesOf(a.select(Holder.class).provider);

        var b = container();
        var read = (Provider<?>) readIn(b.getBeanManager(), bytes);

        assertSame(b.select(Shelf.class), read.get());
    }

    @Test
    @DisplayName("a moved Event moves again, to a third container")
    void aMovedEventMovesAgain() throws Exception {
        var a = container();
        var b = container();
        var once = readIn(b.getBeanManager(), bytesOf(a.select(Holder.class).pings));

        var c = container();
        @SuppressWarnings("unchecked")
        var twice = (Event<Ping>) readIn(c.getBeanManager(), bytesOf(once));
        twice.fire(new Ping("twice"));

        assertEquals(List.of("twice"), c.select(Recorder.class).pings());
        assertEquals(List.of(), b.select(Recorder.class).pings());
    }

    @Test
    @DisplayName("a bean manager reads back as the importing container's, and not without one")
    void aBeanManagerReadsBackAsTheImportingOne() throws Exception {
        var a = container();
        byte[] bytes = bytesOf(a.getBeanManager());

        var b = container();
        var unbound = assertThrows(InvalidObjectException.class, () -> readObject(bytes));

        assertSame(b.getBeanManager(), readIn(b.getBeanManager(), bytes));
        assertEquals("no importing container bound", unbound.getMessage());
    }

    @Test
    @DisplayName("an Event obtained by select(...) cannot be written")
    void aSelectedEventCannotBeWritten() {
        var a = container();
        var selected = a.select(Holder.class).pings.select(Default.Literal.INSTANCE);

        var thrown = assertThrows(NotSerializableException.class, () -> bytesOf(selected));

        assertTrue(thrown.getMessage().startsWith("an Event obtained by select(...)"), thrown.getMessage());
    }

    @Test
    @DisplayName("an Event or an Instance obtained by a programmatic lookup cannot be written")
    void aProgrammaticLookupCannotBeWritten() {
        var a = container();
        var event = a.getBeanManager().getEvent();
        var instance = a.getBeanManager().createInstance();

        var eventThrown = assertThrows(NotSerializableException.class, () -> bytesOf(event));
        var instanceThrown = assertThrows(NotSerializableException.class, () -> bytesOf(instance));

        assertTrue(eventThrown.getMessage().contains("programmatic lookup"), eventThrown.getMessage());
        assertTrue(instanceThrown.getMessage().contains("programmatic lookup"), instanceThrown.getMessage());
    }

    @Test
    @DisplayName("an Event given to a producer method, outside a bean with a passivation id, cannot be written")
    void anEventGivenToAProducerCannotBeWritten() {
        var a = container();
        var carried = a.select(Carrier.class).pings();

        var thrown = assertThrows(NotSerializableException.class, () -> bytesOf(carried));

        assertTrue(thrown.getMessage().startsWith(BUILT_INS + " injected outside a bean with a passivation id"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("a reference to an injection point the importing bean lacks fails, naming it")
    void anUnknownInjectionPointFails() throws Exception {
        var b = container();
        byte[] renamed = bytesOf(new InjectionPointReference(HOLDER, HOLDER + "#renamed", -1, EVENT));
        byte[] shifted = bytesOf(new InjectionPointReference(HOLDER,
                HOLDER + "#init(" + EVENT + ")", 1, EVENT));

        var renamedThrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), renamed));
        var shiftedThrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), shifted));

        assertTrue(renamedThrown.getMessage().contains(HOLDER + "#renamed"), renamedThrown.getMessage());
        assertTrue(shiftedThrown.getMessage().contains(HOLDER + "#init(" + EVENT + ")"), shiftedThrown.getMessage());
    }

    @Test
    @DisplayName("a reference to a bean the importing container lacks fails, naming the bean")
    void anUnknownBeanFails() throws Exception {
        var b = container();
        byte[] bytes = bytesOf(new InjectionPointReference("com.acme.Gone", "com.acme.Gone#pings", -1, EVENT));

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertTrue(thrown.getMessage().contains("com.acme.Gone"), thrown.getMessage());
    }

    @Test
    @DisplayName("a reference whose injection point now has another type fails")
    void anInjectionPointOfAnotherTypeFails() throws Exception {
        var b = container();
        byte[] bytes = bytesOf(new InjectionPointReference(HOLDER, HOLDER + "#shelves", -1, EVENT));

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertTrue(thrown.getMessage().contains(INSTANCE), thrown.getMessage());
    }

    @Test
    @DisplayName("a reference to an injection point that takes no built-in creates no bean and fails")
    void anInjectionPointOfABeanTypeFails() throws Exception {
        var b = container();
        byte[] bytes = bytesOf(new InjectionPointReference(HOLDER, HOLDER + "#shelf", -1, Shelf.class.getName()));

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertTrue(thrown.getMessage().contains(Shelf.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("a reference with no passivation id, no member or no type fails")
    void anIncompleteReferenceFails() throws Exception {
        var b = container();
        var incomplete = List.of(
                new InjectionPointReference(null, HOLDER + "#pings", -1, EVENT),
                new InjectionPointReference(HOLDER, null, -1, EVENT),
                new InjectionPointReference(HOLDER, HOLDER + "#pings", -1, null));

        for (var reference : incomplete) {
            byte[] bytes = bytesOf(reference);
            var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));
            assertEquals("an incomplete injection point reference in the stream", thrown.getMessage());
        }
    }

    @Test
    @DisplayName("an importing bean manager that is not Vauban's fails, and is never asked")
    void aForeignImportingBeanManagerFails() throws Exception {
        var a = container();
        byte[] bytes = bytesOf(a.select(Holder.class).pings);

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(foreign(), bytes));

        assertTrue(thrown.getMessage().contains("not a Vauban container"), thrown.getMessage());
    }

    @Test
    @DisplayName("an Event given with no injection point, as to an observer method, cannot be written, and says why")
    void aBuiltInWithNoInjectionPointCannotBeWritten() {
        var thrown = assertThrows(NotSerializableException.class,
                () -> VaubanInjectionPoint.serialFormOf(null));

        assertTrue(thrown.getMessage().startsWith("an Event given with no injection point, as to an observer method"),
                thrown.getMessage());
        assertFalse(thrown.getMessage().endsWith("null"), thrown.getMessage());
    }

    @Test
    @DisplayName("an injection point that names no field and no parameter cannot be written")
    void anInjectionPointWithNoMemberCannotBeWritten() {
        var a = container();
        var holder = a.getBeanManager().getPassivationCapableBean(HOLDER);
        var memberless = new VaubanInjectionPoint(Event.class, Set.of(Default.Literal.INSTANCE), holder, null);

        var thrown = assertThrows(NotSerializableException.class,
                () -> VaubanInjectionPoint.serialFormOf(memberless));

        assertTrue(thrown.getMessage().startsWith(BUILT_INS + " injected at "), thrown.getMessage());
        assertTrue(thrown.getMessage().endsWith("its injection point names no field and no parameter"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("a failure of the importing container fails the read, with the failure as its cause")
    void aFailureOfTheImportingContainerFails() throws Exception {
        var a = container();
        byte[] bytes = bytesOf(a.select(Holder.class).pings);

        var b = container();
        var holder = (ManagedBean<?>) b.getBeanManager().getPassivationCapableBean(HOLDER);
        var detached = new VaubanBeanManager(null, Map.of(), List.of(holder), null, null);
        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(detached, bytes));

        assertTrue(thrown.getMessage().startsWith("the importing container injects nothing at " + HOLDER + "#pings"),
                thrown.getMessage());
        assertInstanceOf(NullPointerException.class, thrown.getCause());
    }

    @Test
    @DisplayName("a field shadowed in a subclass moves as its own, with its own qualifiers")
    void aShadowedFieldMovesAsItsOwn() throws Exception {
        var a = container();
        var loud = a.select(LoudHolder.class);
        byte[] own = bytesOf(loud.pings);
        byte[] inherited = bytesOf(((Holder) loud).pings);

        var b = container();
        @SuppressWarnings("unchecked")
        var readOwn = (Event<Ping>) readIn(b.getBeanManager(), own);
        @SuppressWarnings("unchecked")
        var readInherited = (Event<Ping>) readIn(b.getBeanManager(), inherited);
        readOwn.fire(new Ping("own"));
        readInherited.fire(new Ping("inherited"));

        assertEquals(List.of("own"), b.select(Recorder.class).loudPings(), "only the subclass field is @Loud");
        assertEquals(List.of("own", "inherited"), b.select(Recorder.class).pings());
    }

    @Test
    @DisplayName("two overloaded initializer methods move as their own")
    void overloadedInitializersMoveAsTheirOwn() throws Exception {
        var a = container();
        var holder = a.select(Holder.class);
        byte[] event = bytesOf(holder.byInitializer);
        byte[] instance = bytesOf(holder.byOverload);

        var b = container();
        @SuppressWarnings("unchecked")
        var readEvent = (Event<Ping>) readIn(b.getBeanManager(), event);
        var readInstance = (Instance<?>) readIn(b.getBeanManager(), instance);
        readEvent.fire(new Ping("init(Event)"));

        assertEquals(List.of("init(Event)"), b.select(Recorder.class).pings());
        assertSame(b.select(Shelf.class), readInstance.get(), "init(Instance) gives B's shelf");
    }

    @Test
    @DisplayName("reading an Instance back creates no bean: it resolves at get()")
    void readingAnInstanceCreatesNoBean() throws Exception {
        var a = container();
        byte[] bytes = bytesOf(a.select(Holder.class).counted);

        var b = container();
        Counted.CREATED.set(0);
        var read = (Instance<?>) readIn(b.getBeanManager(), bytes);

        assertEquals(0, Counted.CREATED.get(), "no Counted created on read");
        read.get();
        assertEquals(1, Counted.CREATED.get(), "one at get()");
    }

    @Test
    @DisplayName("the built-ins of a class that also declares a producer move with the class's own bean")
    void theBuiltInsOfAClassThatDeclaresAProducerMove() throws Exception {
        var a = container();
        var workshop = a.select(Workshop.class);
        byte[] event = bytesOf(workshop.pings);
        byte[] instance = bytesOf(workshop.shelves);

        var b = container();
        @SuppressWarnings("unchecked")
        var readEvent = (Event<Ping>) readIn(b.getBeanManager(), event);
        var readInstance = (Instance<?>) readIn(b.getBeanManager(), instance);
        readEvent.fire(new Ping("workshop"));

        assertEquals(List.of("workshop"), b.select(Recorder.class).pings());
        assertSame(b.select(Shelf.class), readInstance.get());
    }

    @Test
    @DisplayName("an Event, an Instance or a bean manager is read through its serial form only")
    void builtInsAreReadThroughTheirFormOnly() throws Exception {
        var b = container();
        var bare = List.of(EventImpl.class.getName(), InstanceImpl.class.getName(),
                VaubanBeanManager.class.getName());

        for (var className : bare) {
            byte[] bytes = streamOfABare(className);
            var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));
            assertTrue(thrown.getMessage().endsWith("is read through its serial form only"), thrown.getMessage());
        }
    }

    // --- fixtures ---

    public record Ping(String text) {
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Loud {
    }

    @ApplicationScoped
    public static class Recorder {

        private final List<String> pings = new CopyOnWriteArrayList<>();

        private final List<String> loudPings = new CopyOnWriteArrayList<>();

        void onPing(@Observes Ping ping) {
            pings.add(ping.text());
        }

        void onLoudPing(@Observes @Loud Ping ping) {
            loudPings.add(ping.text());
        }

        public List<String> pings() {
            return List.copyOf(pings);
        }

        public List<String> loudPings() {
            return List.copyOf(loudPings);
        }
    }

    /** Counts its instances. */
    @Dependent
    public static class Counted {

        static final AtomicInteger CREATED = new AtomicInteger();

        public Counted() {
            CREATED.incrementAndGet();
        }
    }

    public record Tool(String name) {
    }

    /** Holds built-ins, and declares a producer: two beans have this bean class. */
    @Dependent
    public static class Workshop {

        @Inject
        Event<Ping> pings;

        @Inject
        Instance<Shelf> shelves;

        @Produces
        Tool tool() {
            return new Tool("hammer");
        }
    }

    @ApplicationScoped
    public static class Shelf {

        private final List<String> marks = new CopyOnWriteArrayList<>();

        public void add(String mark) {
            marks.add(mark);
        }

        public List<String> marks() {
            return List.copyOf(marks);
        }
    }

    /** Takes the built-ins through a constructor, two overloaded initializer methods and fields. */
    @Dependent
    public static class Holder {

        final Instance<Shelf> byConstructor;

        Event<Ping> byInitializer;

        Instance<Shelf> byOverload;

        @Inject
        Event<Ping> pings;

        @Inject
        Instance<Counted> counted;

        @Inject
        Instance<Shelf> shelves;

        @Inject
        Provider<Shelf> provider;

        @Inject
        Shelf shelf;

        @Inject
        public Holder(Instance<Shelf> byConstructor) {
            this.byConstructor = byConstructor;
        }

        @Inject
        void init(Event<Ping> byInitializer) {
            this.byInitializer = byInitializer;
        }

        @Inject
        void init(Instance<Shelf> byOverload) {
            this.byOverload = byOverload;
        }
    }

    /** Shadows {@link Holder#pings} with a qualified {@code Event} of its own. */
    @Dependent
    @Typed(LoudHolder.class)
    public static class LoudHolder extends Holder {

        @Inject
        @Loud
        Event<Ping> pings;

        @Inject
        public LoudHolder(Instance<Shelf> byConstructor) {
            super(byConstructor);
        }
    }

    /** What a producer method builds from the {@code Event} it was given. */
    public record Carrier(Event<Ping> pings) {
    }

    @Dependent
    public static class Carriers {

        @Produces
        Carrier carrier(Event<Ping> pings) {
            return new Carrier(pings);
        }
    }

    // --- helpers ---

    private VaubanContainer container() {
        var container = VaubanContainer.builder()
                .addBeanClass(Recorder.class)
                .addBeanClass(Shelf.class)
                .addBeanClass(Holder.class)
                .addBeanClass(LoudHolder.class)
                .addBeanClass(Counted.class)
                .addBeanClass(Workshop.class)
                .addBeanClass(Carriers.class)
                .build();
        containers.add(container);
        return container;
    }

    /** A bean manager that is not Vauban's, and fails whatever it is asked. */
    private static BeanManager foreign() {
        InvocationHandler handler = (_, method, _) -> {
            throw new UnsupportedOperationException(method.getName());
        };
        return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[] {BeanManager.class}, handler);
    }

    /**
     * The stream of an object of {@code className} written as itself, with its class but no serial form: its
     * writeReplace always replaces it, so the bytes are laid out by hand. It has no field to write, all of them
     * transient, and no serializable superclass.
     */
    private static byte[] streamOfABare(String className) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeShort(ObjectStreamConstants.STREAM_MAGIC);
            out.writeShort(ObjectStreamConstants.STREAM_VERSION);
            out.writeByte(ObjectStreamConstants.TC_OBJECT);
            out.writeByte(ObjectStreamConstants.TC_CLASSDESC);
            out.writeUTF(className);
            out.writeLong(1L);
            out.writeByte(ObjectStreamConstants.SC_SERIALIZABLE);
            out.writeShort(0);
            out.writeByte(ObjectStreamConstants.TC_ENDBLOCKDATA);
            out.writeByte(ObjectStreamConstants.TC_NULL);
        }
        return bytes.toByteArray();
    }

    private static byte[] bytesOf(Object value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Object readObject(byte[] bytes) throws IOException, ClassNotFoundException {
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    /** Reads {@code bytes} with {@code beanManager} bound as the importing container. */
    private static Object readIn(BeanManager beanManager, byte[] bytes) throws IOException {
        try {
            return Passivation.importInto(beanManager, () -> {
                try {
                    return readObject(bytes);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
