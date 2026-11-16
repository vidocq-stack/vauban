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
package io.vidocq.vauban.webcontexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.api.Passivation;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.PassivationCapable;
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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A session moves from one container to another, as the web container moves it when Vidocq swaps the application
 * layer (foy#114): each attribute is written to its own bytes, then read back while the importing container is bound
 * ({@link Passivation#importInto}). Container A wrote the session, container B reads it.
 */
class SessionPassivationTest {

    private static final String BASKET_KEY = SessionContext.ATTRIBUTE_PREFIX + Basket.class.getName();
    private static final String SESSION_KEY =
            SessionContext.ATTRIBUTE_PREFIX + SessionProducers.class.getName() + "#session";

    private final List<VaubanContainer> containers = new ArrayList<>();

    @BeforeEach
    void clearTheLogs() {
        Basket.LIFECYCLE.clear();
        SessionProducers.DISPOSED.clear();
    }

    @AfterEach
    void stop() {
        WebContexts.deactivateSession();
        containers.reversed().forEach(VaubanContainer::close);
    }

    @Test
    void aSerializableSessionBeanMovesWithItsStateAndNoLifecycleCallback() throws Exception {
        var a = container(Basket.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Basket.class).add("apple");
        WebContexts.deactivateSession();
        var written = write(exported);

        var b = container(Basket.class);
        var imported = read(b, written);
        WebContexts.activateSession(imported);

        assertEquals(List.of("apple"), b.select(Basket.class).items(), "the instance moved with its state");
        var moved = ((SessionContext.Entry<?>) imported.getAttribute(BASKET_KEY)).instance();
        assertSame(moved, sessionContext(b).get(bean(b, Basket.class)), "B's context returns the moved instance");
        assertEquals(List.of("post-construct"), List.copyOf(Basket.LIFECYCLE),
                "only A created the basket: B runs no @PostConstruct, A no @PreDestroy");

        WebContexts.deactivateSession();
        WebContexts.destroySession(b.getBeanManager(), imported, "s1");

        assertEquals(List.of("post-construct", "pre-destroy [apple]"), List.copyOf(Basket.LIFECYCLE),
                "B destroys the moved instance once");
    }

    @Test
    void aMovedSessionBeanMovesAgain() throws Exception {
        var a = container(Basket.class);
        var first = new MapSessionStore();
        WebContexts.activateSession(first);
        a.select(Basket.class).add("apple");
        WebContexts.deactivateSession();

        var b = container(Basket.class);
        var second = read(b, write(first));
        WebContexts.activateSession(second);
        b.select(Basket.class).add("pear");
        WebContexts.deactivateSession();

        var c = container(Basket.class);
        var third = read(c, write(second));
        WebContexts.activateSession(third);
        assertEquals(List.of("apple", "pear"), c.select(Basket.class).items(), "the state survived two moves");
        WebContexts.deactivateSession();
        WebContexts.destroySession(c.getBeanManager(), third, "s1");

        assertEquals(List.of("post-construct", "pre-destroy [apple, pear]"), List.copyOf(Basket.LIFECYCLE),
                "one @PostConstruct in all, and C destroys the instance once");
    }

    @Test
    void theAttributeKeyIsTheIdOfTheBeanInBothContainers() throws Exception {
        var a = container(Basket.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Basket.class).add("pear");
        WebContexts.deactivateSession();

        assertEquals(List.of(BASKET_KEY), exported.attributeNames(), "A keys the instance by the id of its bean");

        var b = container(Basket.class);
        var imported = read(b, write(exported));
        WebContexts.activateSession(imported);
        b.select(Basket.class).add("plum");

        assertEquals(List.of(BASKET_KEY), imported.attributeNames(), "B finds it under the same key");
        assertEquals(List.of("pear", "plum"), b.select(Basket.class).items());
    }

    @Test
    void aProducedSessionBeanMovesToo() throws Exception {
        var a = container(SessionProducers.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Wallet.class).add(3);
        WebContexts.deactivateSession();

        var b = container(SessionProducers.class);
        WebContexts.activateSession(read(b, write(exported)));

        assertEquals(5, b.select(Wallet.class).add(2), "the wallet moved with its coins");
    }

    @Test
    void aClientProxyHeldBySessionBeanReachesTheImportingContainersBean() throws Exception {
        var a = container(Shopper.class, Catalog.class, Visit.class);
        a.select(Catalog.class).mark("A");
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Shopper.class).name("ada");
        WebContexts.deactivateSession();
        var written = write(exported);

        var b = container(Shopper.class, Catalog.class, Visit.class);
        b.select(Catalog.class).mark("B");
        WebContexts.activateSession(read(b, written));
        var shopper = b.select(Shopper.class);

        assertEquals("ada", shopper.name(), "the session bean moved with its state");
        assertEquals(List.of("B"), shopper.catalog().marks(), "the field reaches B's service, not A's");
        assertSame(b.select(Catalog.class), shopper.catalog(), "the field holds B's own client proxy");
    }

    @Test
    void aClientProxyOfATypedBeanReachesTheImportingContainersBean() throws Exception {
        var a = container(Checkout.class, FlatPricing.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Checkout.class).owner("cy");
        a.select(Checkout.class).pricing().mark("A");
        WebContexts.deactivateSession();
        var written = write(exported);

        var b = container(Checkout.class, FlatPricing.class);
        b.select(Pricing.class).mark("B");
        WebContexts.activateSession(read(b, written));
        var checkout = b.select(Checkout.class);

        assertEquals("cy", checkout.owner(), "the session bean moved with its state");
        assertEquals(List.of("B"), checkout.pricing().marks(), "the field reaches B's service, not A's");
        assertSame(b.select(Pricing.class), checkout.pricing(), "the field holds B's own client proxy");
    }

    @Test
    void aClientProxyOfARequestBeanResolvesUnderTheImportingRequest() throws Exception {
        var a = container(Shopper.class, Catalog.class, Visit.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Shopper.class).name("bob");
        WebContexts.deactivateSession();
        var written = write(exported);

        var b = container(Shopper.class, Catalog.class, Visit.class);
        WebContexts.activateSession(read(b, written));
        var shopper = b.select(Shopper.class);

        b.requestContext().runInScope(() -> {
            shopper.visit().page();
            shopper.visit().page();
            assertEquals(2, b.select(Visit.class).pages(), "the field reaches B's request instance");
        });
    }

    @Test
    void anInjectedEventFiresInTheImportingContainer() throws Exception {
        var a = container(Desk.class, Signals.class, Catalog.class);
        var written = writeADesk(a, "ada");

        var b = container(Desk.class, Signals.class, Catalog.class);
        WebContexts.activateSession(read(b, written));
        var desk = b.select(Desk.class);
        desk.signals().fire(new Signal("moved"));

        assertEquals("ada", desk.owner(), "the session bean moved with its state");
        assertEquals(List.of("moved"), b.select(Signals.class).received(), "B's observer got the signal");
        assertEquals(List.of(), a.select(Signals.class).received(), "A's observer did not");
    }

    @Test
    void anEventInjectedIntoAnInitializerMovesWithItsQualifiers() throws Exception {
        var a = container(Desk.class, Signals.class, Catalog.class);
        var written = writeADesk(a, "bob");

        var b = container(Desk.class, Signals.class, Catalog.class);
        WebContexts.activateSession(read(b, written));
        b.select(Desk.class).urgent().fire(new Signal("now"));

        assertEquals(Set.of("now", "urgent now"), Set.copyOf(b.select(Signals.class).received()),
                "B's observers got the signal, the @Urgent one included");
        assertEquals(List.of(), a.select(Signals.class).received(), "A's observers did not");
    }

    @Test
    void anInjectedInstanceResolvesInTheImportingContainer() throws Exception {
        var a = container(Desk.class, Signals.class, Catalog.class);
        a.select(Catalog.class).mark("A");
        var written = writeADesk(a, "cy");

        var b = container(Desk.class, Signals.class, Catalog.class);
        b.select(Catalog.class).mark("B");
        WebContexts.activateSession(read(b, written));
        var catalog = b.select(Desk.class).catalogs().get();

        assertSame(b.select(Catalog.class), catalog, "get() gives B's client proxy of the application bean");
        assertEquals(List.of("B"), catalog.marks(), "B's catalog, not A's");
    }

    @Test
    void anInjectedBeanManagerIsTheImportingOne() throws Exception {
        var a = container(Desk.class, Signals.class, Catalog.class);
        var written = writeADesk(a, "dee");

        var b = container(Desk.class, Signals.class, Catalog.class);
        WebContexts.activateSession(read(b, written));

        assertSame(b.getBeanManager(), b.select(Desk.class).beanManager());
    }

    @Test
    void aSelectedInstanceCannotBeWritten() {
        var a = container(Picker.class, Catalog.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Picker.class).pick();
        WebContexts.deactivateSession();

        var thrown = assertThrows(NotSerializableException.class, () -> write(exported));

        assertTrue(thrown.getMessage().startsWith("an Instance or a Provider obtained by select(...)"),
                thrown.getMessage());
    }

    @Test
    void aBuiltInReadWithNoImportingContainerFails() throws Exception {
        var a = container(Desk.class, Signals.class, Catalog.class);
        WebContexts.activateSession(new MapSessionStore());
        var desk = a.select(Desk.class);
        var written = List.of(bytesOf(desk.signals()), bytesOf(desk.catalogs()), bytesOf(desk.beanManager()));
        WebContexts.deactivateSession();

        for (byte[] bytes : written) {
            var thrown = assertThrows(InvalidObjectException.class, () -> readObject(bytes));
            assertEquals("no importing container bound", thrown.getMessage());
        }
    }

    @Test
    void anInjectionPointTheImportingContainerLacksFails() throws Exception {
        var a = container(Desk.class, Signals.class, Catalog.class);
        WebContexts.activateSession(new MapSessionStore());
        byte[] written = bytesOf(a.select(Desk.class).signals());
        WebContexts.deactivateSession();
        // The field was renamed between the two containers: the stream names it by its old name.
        var renamed = renamed(written, Desk.class.getName() + "#signals", Desk.class.getName() + "#signalz");

        var b = container(Desk.class, Signals.class, Catalog.class);
        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), renamed));

        assertTrue(thrown.getMessage().contains(Desk.class.getName() + "#signalz"), thrown.getMessage());
    }

    @Test
    void aReadWithNoImportingContainerFails() throws Exception {
        var a = container(Basket.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Basket.class).add("fig");
        WebContexts.deactivateSession();
        byte[] bytes = write(exported).get(BASKET_KEY);

        var thrown = assertThrows(InvalidObjectException.class, () -> readObject(bytes));

        assertEquals("no importing container bound", thrown.getMessage());
    }

    @Test
    void aReadOfABeanTheImportingContainerLacksFails() throws Exception {
        var a = container(Basket.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Basket.class).add("kiwi");
        WebContexts.deactivateSession();
        byte[] bytes = write(exported).get(BASKET_KEY);

        var importing = container(Notepad.class).getBeanManager();
        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(importing, bytes));

        assertTrue(thrown.getMessage().contains(Basket.class.getName()), thrown.getMessage());
    }

    @Test
    void aReadOfAnInstanceThatIsNotOfItsBeanFails() throws Exception {
        var importing = container(Basket.class).getBeanManager();
        byte[] bytes = bytesOf(new SessionContext.EntryForm(Basket.class.getName(), "not a basket"));

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(importing, bytes));

        assertTrue(thrown.getMessage().contains(Basket.class.getName()), thrown.getMessage());
    }

    @Test
    void aFailureOfTheImportingContainerIsAnInvalidObjectWithItsCause() throws Exception {
        var basket = container(Basket.class).getBeanManager();
        byte[] bytes = bytesOf(new SessionContext.EntryForm(Basket.class.getName(), "a basket"));
        var failure = new IllegalStateException("no bean today");
        var failing = (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[] {BeanManager.class}, (_, method, args) -> {
                    if (method.getName().equals("getPassivationCapableBean")) {
                        throw failure;
                    }
                    return method.invoke(basket, args);
                });

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(failing, bytes));

        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains(Basket.class.getName()), thrown.getMessage());
    }

    @Test
    void aReadOfAFormWithNoPassivationIdFails() throws Exception {
        var importing = container(Basket.class).getBeanManager();
        byte[] bytes = bytesOf(new SessionContext.EntryForm(null, "orphan"));

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(importing, bytes));

        assertEquals("no passivation id in the stream", thrown.getMessage());
    }

    @Test
    void anEntryIsReadThroughItsSerialFormOnly() throws Exception {
        var importing = container(Basket.class).getBeanManager();
        byte[] bytes = streamOfABareEntry();

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(importing, bytes));

        assertTrue(thrown.getMessage().startsWith("an entry is read through its serial form only"),
                thrown.getMessage());
    }

    @Test
    void aSessionBeanThatIsNotSerializableCannotBeWritten() {
        var a = container(Notepad.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(Notepad.class).write("draft");
        WebContexts.deactivateSession();

        var thrown = assertThrows(NotSerializableException.class, () -> write(exported));

        assertEquals("bean class " + Notepad.class.getName() + " is not Serializable", thrown.getMessage());
    }

    @Test
    void anInterceptedSessionBeanCannotBeWrittenYet() {
        var a = container(AuditInterceptor.class, AuditedBasket.class);
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        a.select(AuditedBasket.class).add();
        WebContexts.deactivateSession();
        var key = SessionContext.ATTRIBUTE_PREFIX + AuditedBasket.class.getName();
        var instance = ((SessionContext.Entry<?>) exported.getAttribute(key)).instance();
        assertTrue(instance.getClass().getName().endsWith("$$Intercepted"), instance.getClass().getName());

        var thrown = assertThrows(NotSerializableException.class, () -> write(exported));

        assertTrue(thrown.getMessage().startsWith("intercepted instances cannot move yet"), thrown.getMessage());
    }

    @Test
    void anInstanceOfAContextualThatIsNotPassivationCapableCannotBeWritten() {
        var exported = holding(new Foreign());

        var thrown = assertThrows(NotSerializableException.class, () -> write(exported));

        assertTrue(thrown.getMessage().startsWith("contextual has no passivation id"), thrown.getMessage());
    }

    @Test
    void anInstanceOfAContextualWithANullPassivationIdCannotBeWritten() {
        var exported = holding(new ForeignWithANullId());

        var thrown = assertThrows(NotSerializableException.class, () -> write(exported));

        assertTrue(thrown.getMessage().startsWith("contextual has no passivation id"), thrown.getMessage());
    }

    @Test
    void theSessionItselfStaysOutOfTheStreamAndIsProducedAgainAtItsFirstUse() throws Exception {
        var a = container(SessionProducers.class);
        var exported = new WebSessionStore(new WebSession("s1"));
        WebContexts.activateSession(exported);
        assertEquals("s1", a.select(WebSession.class).id());
        WebContexts.deactivateSession();
        var written = write(exported);

        var b = container(SessionProducers.class);
        var imported = new WebSessionStore(new WebSession("s1, imported"));
        readInto(b, written, imported);
        var sentinel = imported.getAttribute(SESSION_KEY);
        assertSame(SessionContext.Entry.SENTINEL, sentinel, "the session itself reads back as the sentinel");
        WebContexts.activateSession(imported);
        var session = bean(b, WebSession.class);

        assertNull(sessionContext(b).get(session), "the sentinel counts as no instance");
        assertEquals("s1, imported", b.select(WebSession.class).id(), "the producer gives the imported session");
        var produced = (SessionContext.Entry<?>) imported.getAttribute(SESSION_KEY);
        assertSame(imported.session(), produced.instance(), "the produced session replaced the sentinel");
        assertNotSame(sentinel, produced);
    }

    @Test
    void theSentinelMovesAgainAsTheSentinel() throws Exception {
        var a = container(SessionProducers.class);
        var exported = new WebSessionStore(new WebSession("s1"));
        WebContexts.activateSession(exported);
        a.select(WebSession.class).id();
        WebContexts.deactivateSession();

        var b = container(SessionProducers.class);
        var once = new WebSessionStore(new WebSession("s1, once"));
        readInto(b, write(exported), once);
        var c = container(SessionProducers.class);
        var twice = new WebSessionStore(new WebSession("s1, twice"));
        readInto(c, write(once), twice);
        assertSame(SessionContext.Entry.SENTINEL, twice.getAttribute(SESSION_KEY), "still the sentinel");
        WebContexts.activateSession(twice);

        assertEquals("s1, twice", c.select(WebSession.class).id());
    }

    @Test
    void destroyingTheSentinelDestroysNothing() throws Exception {
        var a = container(SessionProducers.class);
        var exported = new WebSessionStore(new WebSession("s1"));
        WebContexts.activateSession(exported);
        a.select(WebSession.class).id();
        WebContexts.deactivateSession();
        var written = write(exported);
        var b = container(SessionProducers.class);

        var ended = new WebSessionStore(new WebSession("s1, ended"));
        readInto(b, written, ended);
        WebContexts.destroySession(b.getBeanManager(), ended, "ended");

        var removed = new WebSessionStore(new WebSession("s1, removed"));
        readInto(b, written, removed);
        WebContexts.activateSession(removed);
        sessionContext(b).destroy(bean(b, WebSession.class));

        assertEquals(List.of(), List.copyOf(SessionProducers.DISPOSED), "no disposer ran for the sentinel");
        assertTrue(ended.attributeNames().isEmpty(), "destroyAll removed the sentinel: " + ended.attributeNames());
        assertTrue(removed.attributeNames().isEmpty(), "destroy removed the sentinel: " + removed.attributeNames());
    }

    // --- helpers ---

    /** A contextual of no container, which is not passivation capable. */
    private static class Foreign implements Contextual<String> {

        @Override
        public String create(CreationalContext<String> creationalContext) {
            return "foreign";
        }

        @Override
        public void destroy(String instance, CreationalContext<String> creationalContext) {
            // Nothing to release.
        }
    }

    /** Passivation capable, but with no id to give. */
    private static final class ForeignWithANullId extends Foreign implements PassivationCapable {

        @Override
        public String getId() {
            return null;
        }
    }

    /** A session that holds an instance of {@code foreign}, created in a container that knows nothing of it. */
    private MapSessionStore holding(Contextual<String> foreign) {
        var a = container();
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        sessionContext(a).get(foreign, a.getBeanManager().createCreationalContext(foreign));
        WebContexts.deactivateSession();
        return exported;
    }

    /**
     * The stream of an entry written as itself, with its class but no serial form: no ObjectOutputStream writes
     * one, since the entry's writeReplace always replaces it, so the bytes are laid out by hand. The entry has no
     * field to write, all of them transient, and no serializable superclass.
     */
    private static byte[] streamOfABareEntry() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeShort(ObjectStreamConstants.STREAM_MAGIC);
            out.writeShort(ObjectStreamConstants.STREAM_VERSION);
            out.writeByte(ObjectStreamConstants.TC_OBJECT);
            out.writeByte(ObjectStreamConstants.TC_CLASSDESC);
            out.writeUTF(SessionContext.Entry.class.getName());
            out.writeLong(1L);
            out.writeByte(ObjectStreamConstants.SC_SERIALIZABLE);
            out.writeShort(0);
            out.writeByte(ObjectStreamConstants.TC_ENDBLOCKDATA);
            out.writeByte(ObjectStreamConstants.TC_NULL);
        }
        return bytes.toByteArray();
    }

    private VaubanContainer container(Class<?>... beanClasses) {
        var builder = VaubanContainer.builder().addBeanClass(WebContextsExtension.class);
        for (Class<?> beanClass : beanClasses) {
            builder.addBeanClass(beanClass);
        }
        var container = builder.build();
        containers.add(container);
        return container;
    }

    private static AlterableContext sessionContext(VaubanContainer container) {
        return (AlterableContext) container.getBeanManager().getContext(SessionScoped.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> Bean<T> bean(VaubanContainer container, Class<T> type) {
        var beanManager = container.getBeanManager();
        return (Bean<T>) beanManager.resolve(beanManager.getBeans(type));
    }

    /** The session of a desk {@code owner} used in {@code container}, written as the web container writes it. */
    private static Map<String, byte[]> writeADesk(VaubanContainer container, String owner) throws IOException {
        var exported = new MapSessionStore();
        WebContexts.activateSession(exported);
        container.select(Desk.class).owner(owner);
        WebContexts.deactivateSession();
        return write(exported);
    }

    /**
     * {@code bytes} with the one string {@code from} replaced by {@code to}, of the same length: what a stream written
     * before a rename would name. The modified UTF-8 of an ASCII string is its bytes, after their length.
     */
    private static byte[] renamed(byte[] bytes, String from, String to) {
        byte[] target = from.getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = to.getBytes(StandardCharsets.US_ASCII);
        assertEquals(target.length, replacement.length, "a rename of the same length keeps the stream valid");
        var renamed = bytes.clone();
        int found = -1;
        for (int i = 0; i + target.length <= renamed.length && found < 0; i++) {
            if (Arrays.equals(renamed, i, i + target.length, target, 0, target.length)) {
                found = i;
            }
        }
        assertTrue(found >= 0, "the stream names " + from);
        System.arraycopy(replacement, 0, renamed, found, replacement.length);
        return renamed;
    }

    /** Each attribute to its own bytes, as the web container writes a session it moves. */
    private static Map<String, byte[]> write(SessionStore store) throws IOException {
        var written = new LinkedHashMap<String, byte[]>();
        for (String name : store.attributeNames()) {
            written.put(name, bytesOf(store.getAttribute(name)));
        }
        return written;
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

    private static MapSessionStore read(VaubanContainer container, Map<String, byte[]> written) throws IOException {
        var store = new MapSessionStore();
        readInto(container, written, store);
        return store;
    }

    private static void readInto(VaubanContainer container, Map<String, byte[]> written, SessionStore store)
            throws IOException {
        for (var attribute : written.entrySet()) {
            store.setAttribute(attribute.getKey(), readIn(container.getBeanManager(), attribute.getValue()));
        }
    }
}
