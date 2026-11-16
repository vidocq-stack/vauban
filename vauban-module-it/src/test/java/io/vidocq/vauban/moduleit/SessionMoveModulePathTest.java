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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.api.Passivation;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.webcontexts.SessionStore;
import io.vidocq.vauban.webcontexts.WebContexts;
import io.vidocq.vauban.webcontexts.WebContextsExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A session moves from one container to another on the MODULE PATH, as the web container moves it when Vidocq swaps
 * the application layer (foy#114). Its session bean holds two client proxies the processor rendered as source, one
 * class-typed and one interface-typed, and the package-private serial forms of vauban-webcontexts and vauban-core
 * (the session entry's, the contextual instance's reference to its bean) read back with no {@code opens}. The
 * container's run-time generation report stays empty: no proxy is generated at run time on either side.
 */
@DisplayName("A session holding source-rendered client proxies moves on the module path")
class SessionMoveModulePathTest {

    /**
     * The logger of the container's run-time generation report, held so that the handler stays on it: the
     * {@code RUNTIME CODE GENERATION FALLBACK} warning lists every class a container generates at run time.
     */
    private static final Logger RUNTIME_GENERATION =
            Logger.getLogger("io.vidocq.vauban.core.container.RuntimeGeneration");

    private final List<VaubanContainer> containers = new ArrayList<>();
    private final List<String> runtimeGenerated = new CopyOnWriteArrayList<>();
    private final Handler report = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            runtimeGenerated.add(logRecord.getMessage());
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Nothing held.
        }
    };

    @BeforeEach
    void listen() {
        RUNTIME_GENERATION.addHandler(report);
    }

    @AfterEach
    void stop() {
        RUNTIME_GENERATION.removeHandler(report);
        WebContexts.deactivateSession();
        containers.reversed().forEach(VaubanContainer::close);
    }

    @Test
    @DisplayName("the session bean moves, and its proxies reach the importing container's beans")
    void aSessionBeanAndItsProxiesMove() throws Exception {
        var a = container();
        assertEquals(List.of(), runtimeGenerated, "no class generated at run time: every proxy comes from the build");
        assertEquals(Till.class.getName() + "_ClientProxy", a.select(Till.class).getClass().getName(),
                "the source-rendered proxy of the class");
        assertFalse(java.lang.reflect.Proxy.isProxyClass(a.select(Clerk.class).getClass()),
                "the source-rendered proxy of the interface, not the reflect.Proxy fallback");
        a.select(Till.class).mark("A");
        a.select(Clerk.class).serve("A's customer");
        var exported = new MapStore();
        WebContexts.activateSession(exported);
        a.select(ShoppingSession.class).owner("ada");
        WebContexts.deactivateSession();
        var written = write(exported);

        var b = container();
        b.select(Till.class).mark("B");
        WebContexts.activateSession(read(b, written));
        var moved = b.select(ShoppingSession.class);

        assertEquals("ada", moved.owner(), "the session bean moved with its state");
        assertSame(b.select(Till.class), moved.till(), "B's own proxy of the class");
        assertEquals(List.of("B"), moved.till().marks(), "B's till, not A's");
        assertSame(b.select(Clerk.class), moved.clerk(), "B's own proxy of the interface");
        assertEquals(List.of(), moved.clerk().served(), "B's clerk, not A's");
        assertEquals(List.of(), runtimeGenerated, "still no class generated at run time, in either container");
    }

    // --- helpers ---

    /** A session held in a map, as a web container would hold its attributes. */
    private static final class MapStore implements SessionStore {

        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            attributes.remove(name);
        }

        @Override
        public Collection<String> attributeNames() {
            return List.copyOf(attributes.keySet());
        }
    }

    private VaubanContainer container() {
        var container = VaubanContainer.builder()
                .addBeanClass(WebContextsExtension.class)
                .addBeanClass(Till.class)
                .addBeanClass(ClerkDesk.class)
                .addBeanClass(ShoppingSession.class)
                .build();
        containers.add(container);
        return container;
    }

    /** Each attribute to its own bytes, as the web container writes a session it moves. */
    private static Map<String, byte[]> write(SessionStore store) throws IOException {
        var written = new LinkedHashMap<String, byte[]>();
        for (String name : store.attributeNames()) {
            var bytes = new ByteArrayOutputStream();
            try (var out = new ObjectOutputStream(bytes)) {
                out.writeObject(store.getAttribute(name));
            }
            written.put(name, bytes.toByteArray());
        }
        return written;
    }

    /** Each attribute read back with {@code container} bound as the importing container. */
    private static MapStore read(VaubanContainer container, Map<String, byte[]> written) throws IOException {
        var store = new MapStore();
        try {
            Passivation.importInto(container.getBeanManager(), () -> {
                written.forEach((name, bytes) -> store.setAttribute(name, readObject(bytes)));
                return store;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return store;
    }

    private static Object readObject(byte[] bytes) {
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
