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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client proxy written by one container reads back as the client proxy of the importing container, as a web
 * container moves a session when Vidocq swaps the application layer (foy#114): the proxy writes its delegate, the
 * delegate a {@link ClientProxyReference} to its bean, and the reference resolves in the container bound by
 * {@link Passivation#importInto}.
 */
@DisplayName("Client proxies move as references to their bean")
class ClientProxyPassivationTest {

    private final List<VaubanContainer> containers = new ArrayList<>();

    @AfterEach
    void close() {
        containers.reversed().forEach(VaubanContainer::close);
    }

    @Test
    @DisplayName("a client proxy reads back as the importing container's proxy of the same bean")
    void aClientProxyReadsBackAsTheImportingContainersProxy() throws Exception {
        var a = container(Counter.class);
        a.select(Counter.class).add("a");
        byte[] bytes = bytesOf(a.select(Counter.class));

        var b = container(Counter.class);
        var proxyOfB = b.select(Counter.class);
        proxyOfB.add("b");
        var read = readIn(b.getBeanManager(), bytes);

        assertSame(proxyOfB, read, "the importing container's cached proxy");
        assertEquals(List.of("b"), ((Counter) read).marks(), "B's instance, not A's");
    }

    @Test
    @DisplayName("the same proxy written twice in one stream reads back as one object")
    void oneProxyTwiceInAStreamIsOneObject() throws Exception {
        var a = container(Counter.class);
        var proxy = a.select(Counter.class);
        byte[] bytes = bytesOf(new ArrayList<>(List.of(proxy, proxy)));

        var b = container(Counter.class);
        var read = (List<?>) readIn(b.getBeanManager(), bytes);

        assertSame(read.get(0), read.get(1));
        assertSame(b.select(Counter.class), read.get(0));
    }

    @Test
    @DisplayName("a proxy of a request-scoped bean resolves in the importing container's active request")
    void aRequestScopedProxyResolvesUnderTheImportingRequest() throws Exception {
        var a = container(Visit.class);
        byte[] bytes = bytesOf(a.select(Visit.class));

        var b = container(Visit.class);
        var read = (Visit) readIn(b.getBeanManager(), bytes);

        b.requestContext().runInScope(() -> {
            read.step();
            read.step();
            assertEquals(2, b.select(Visit.class).steps(), "B's request instance");
        });
    }

    @Test
    @DisplayName("a read with no importing container fails")
    void anUnboundReadFails() throws Exception {
        var a = container(Counter.class);
        byte[] bytes = bytesOf(a.select(Counter.class));

        var thrown = assertThrows(InvalidObjectException.class, () -> readObject(bytes));

        assertEquals("no importing container bound", thrown.getMessage());
    }

    @Test
    @DisplayName("a reference to a bean the importing container lacks fails, naming the bean")
    void anUnknownBeanFails() throws Exception {
        var a = container(Counter.class);
        byte[] bytes = bytesOf(a.select(Counter.class));

        var b = container(Visit.class);
        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertTrue(thrown.getMessage().contains(Counter.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("a reference whose proxied type is not a type of the bean fails")
    void aTypeMismatchFails() throws Exception {
        var b = container(Counter.class);
        byte[] bytes = bytesOf(new ClientProxyReference(Counter.class.getName(), Visit.class.getName()));

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertTrue(thrown.getMessage().contains(Visit.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("a reference to a bean the importing container no longer gives a normal scope fails")
    void aBeanNoLongerNormalScopedFails() throws Exception {
        var b = container(Plain.class);
        byte[] bytes = bytesOf(new ClientProxyReference(Plain.class.getName(), Plain.class.getName()));

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertEquals("the bean " + Plain.class.getName() + " has the pseudo-scope "
                + Dependent.class.getName() + " in the importing container: a client proxy reads back as the proxy"
                + " of a normal-scoped bean only", thrown.getMessage());
    }

    @Test
    @DisplayName("a failure of the importing container to give the proxy is an InvalidObjectException, with its cause")
    void aFailingReferenceIsAnInvalidObject() throws Exception {
        var b = container(Counter.class);
        byte[] bytes = bytesOf(new ClientProxyReference(Counter.class.getName(), Counter.class.getName()));
        var failure = new IllegalStateException("no proxy today");
        var refusing = refusingReferences(b.getBeanManager(), failure);

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(refusing, bytes));

        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains(Counter.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("a failure of the importing container to find the bean is an InvalidObjectException, with its cause")
    void aFailingBeanLookupIsAnInvalidObject() throws Exception {
        var b = container(Counter.class);
        byte[] bytes = bytesOf(new ClientProxyReference(Counter.class.getName(), Counter.class.getName()));
        var failure = new IllegalStateException("no bean today");

        var thrown = assertThrows(InvalidObjectException.class,
                () -> readIn(failingOn(b.getBeanManager(), "getPassivationCapableBean", failure), bytes));

        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains(Counter.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("a failure of the importing container to tell the bean's scope is an InvalidObjectException, with its cause")
    void aFailingScopeCheckIsAnInvalidObject() throws Exception {
        var b = container(Counter.class);
        byte[] bytes = bytesOf(new ClientProxyReference(Counter.class.getName(), Counter.class.getName()));
        var failure = new IllegalStateException("no scope today");

        var thrown = assertThrows(InvalidObjectException.class,
                () -> readIn(failingOn(b.getBeanManager(), "isNormalScope", failure), bytes));

        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("the handler of the java.lang.reflect.Proxy fallback, never written, is never read either")
    void theReflectProxyFallbackHandlerIsNeverRead() throws Exception {
        var b = container(Counter.class);
        byte[] bytes = streamOfABare(InterceptorBeanWrapper.ReflectiveProxyHandler.class.getName());

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertTrue(thrown.getMessage().contains("java.lang.reflect.Proxy fallback"), thrown.getMessage());
    }

    @Test
    @DisplayName("a reference with no passivation id or no type fails")
    void anIncompleteReferenceFails() throws Exception {
        var b = container(Counter.class);
        byte[] noId = bytesOf(new ClientProxyReference(null, Counter.class.getName()));
        byte[] noType = bytesOf(new ClientProxyReference(Counter.class.getName(), null));

        var withoutId = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), noId));
        var withoutType = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), noType));

        assertEquals("an incomplete client proxy reference in the stream", withoutId.getMessage());
        assertEquals("an incomplete client proxy reference in the stream", withoutType.getMessage());
    }

    @Test
    @DisplayName("a contextual instance is read through its serial form only")
    void aContextualInstanceIsReadThroughItsFormOnly() throws Exception {
        var b = container(Counter.class);
        byte[] bytes = streamOfABare(ContextualInstance.class.getName());

        var thrown = assertThrows(InvalidObjectException.class, () -> readIn(b.getBeanManager(), bytes));

        assertEquals("a contextual instance is read through its serial form only", thrown.getMessage());
    }

    @Test
    @DisplayName("an interface proxied by the java.lang.reflect.Proxy fallback cannot be written, and the message names the cure")
    void theReflectProxyFallbackCannotBeWritten() {
        var a = container(Producers.class);
        var greeter = a.select(Greeter.class);
        assertTrue(java.lang.reflect.Proxy.isProxyClass(greeter.getClass()), "the reflect.Proxy fallback");
        assertEquals("hello", greeter.greet());

        var thrown = assertThrows(NotSerializableException.class, () -> bytesOf(greeter));

        assertEquals(Greeter.class.getName()
                + " is proxied by the java.lang.reflect.Proxy fallback; list it in @ClientProxies", thrown.getMessage());
    }

    // --- fixtures ---

    @ApplicationScoped
    public static class Counter {

        private final List<String> marks = new ArrayList<>();

        public void add(String mark) {
            marks.add(mark);
        }

        public List<String> marks() {
            return List.copyOf(marks);
        }
    }

    @RequestScoped
    public static class Visit {

        private int steps;

        public void step() {
            steps++;
        }

        public int steps() {
            return steps;
        }
    }

    /** A dependent bean: no client proxy. */
    @Dependent
    public static class Plain {
    }

    public interface Greeter {
        String greet();
    }

    @ApplicationScoped
    public static class Producers {
        @Produces
        @ApplicationScoped
        public Greeter greeter() {
            return () -> "hello";
        }
    }

    // --- helpers ---

    private VaubanContainer container(Class<?>... beanClasses) {
        var builder = VaubanContainer.builder();
        for (Class<?> beanClass : beanClasses) {
            builder.addBeanClass(beanClass);
        }
        var container = builder.build();
        containers.add(container);
        return container;
    }

    /** {@code beanManager}, whose {@code getReference} throws {@code failure}: a container that cannot give a proxy. */
    private static BeanManager refusingReferences(BeanManager beanManager, RuntimeException failure) {
        return failingOn(beanManager, "getReference", failure);
    }

    /** {@code beanManager}, whose method {@code methodName} throws {@code failure}. */
    private static BeanManager failingOn(BeanManager beanManager, String methodName, RuntimeException failure) {
        InvocationHandler handler = (_, method, args) -> {
            if (method.getName().equals(methodName)) {
                throw failure;
            }
            try {
                return method.invoke(beanManager, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[] {BeanManager.class}, handler);
    }

    /**
     * The stream of an object of {@code className} written as itself, with its class but no serial form: its
     * writeReplace always replaces it or refuses, so the bytes are laid out by hand. It has no field to write, all of
     * them transient, and its {@code serialVersionUID} is 1.
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
