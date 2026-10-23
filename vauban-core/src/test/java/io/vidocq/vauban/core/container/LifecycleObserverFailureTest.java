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

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vauban#97: a lifecycle observer that throws is no longer swallowed. At startup the boot fails with a
 * {@link DeploymentException} and leaves no half-built container behind; at shutdown the exception is logged and
 * {@code close()} completes. CDI 4.1 §9.5: the observers after the failing one are not notified.
 */
@DisplayName("A throwing lifecycle observer (vauban#97)")
class LifecycleObserverFailureTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    static final IllegalStateException BOOM = new IllegalStateException("boom");

    @ApplicationScoped
    public static class FailingInit {
        void first(@Observes @Priority(1) @Initialized(ApplicationScoped.class) Object event) {
            CALLS.add("FailingInit");
            throw BOOM;
        }
    }

    @ApplicationScoped
    public static class LaterInit {
        void second(@Observes @Priority(2) @Initialized(ApplicationScoped.class) Object event) {
            CALLS.add("LaterInit");
        }
    }

    @ApplicationScoped
    public static class StartupObserver {
        void onStartup(@Observes Startup event) {
            CALLS.add("Startup");
        }
    }

    @ApplicationScoped
    public static class FailingShutdown {
        void first(@Observes @Priority(1) Shutdown event) {
            CALLS.add("FailingShutdown");
            throw BOOM;
        }
    }

    @ApplicationScoped
    public static class LaterShutdown {
        void second(@Observes @Priority(2) Shutdown event) {
            CALLS.add("LaterShutdown");
        }
    }

    @ApplicationScoped
    public static class ErrorInit {
        void first(@Observes @Initialized(ApplicationScoped.class) Object event) {
            throw new ExceptionInInitializerError("broken static state");
        }
    }

    /** Every container log line at WARNING or above names "lifecycle observer"; counted in the formatted text. */
    static final String MARK = "lifecycle observer";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private java.util.logging.StreamHandler handler;
    private java.util.logging.Logger logger;

    @BeforeEach
    void capture() {
        CALLS.clear();
        // The tests run inside io.vidocq.vauban.core, which does not read java.logging, the backend of
        // System.Logger here: read it for the capture (as ScanClasspathTest does).
        getClass().getModule().addReads(ModuleLayer.boot().findModule("java.logging").orElseThrow());
        handler = new java.util.logging.StreamHandler(out, new java.util.logging.SimpleFormatter());
        handler.setLevel(java.util.logging.Level.WARNING);
        logger = java.util.logging.Logger.getLogger(VaubanContainer.class.getName());
        logger.addHandler(handler);
    }

    @AfterEach
    void release() {
        logger.removeHandler(handler);
        VaubanContainer current = VaubanContainer.current();
        if (current != null) {
            current.close();
        }
    }

    /** What the container logged at WARNING or above so far. */
    private String logged() {
        handler.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("a throwing @Initialized(ApplicationScoped) observer fails the boot, logged, with nothing left behind")
    void aThrowingInitializedObserverFailsTheBoot() {
        var builder = VaubanContainer.builder()
                .addBeanClass(FailingInit.class)
                .addBeanClass(LaterInit.class)
                .addBeanClass(StartupObserver.class);
        DeploymentException failure = assertThrows(DeploymentException.class, builder::build);

        assertSame(BOOM, failure.getCause(), "the observer's exception is the cause");
        assertTrue(failure.getMessage().contains(FailingInit.class.getName()), failure.getMessage());
        assertEquals(CALLS, List.of("FailingInit"), "CDI §9.5: later observers and Startup are not notified");
        assertNull(VaubanContainer.current(), "no half-built container is left behind");
        String logged = logged();
        assertEquals(1, count(logged, MARK), logged);
        assertTrue(logged.contains(FailingInit.class.getName() + ".first"), logged);
        assertTrue(logged.contains("java.lang.IllegalStateException: boom"), "the stack trace is logged: " + logged);
    }

    @Test
    @DisplayName("an Error from a startup observer propagates unchanged and leaves nothing behind")
    void anErrorFromAStartupObserverLeavesNothingBehind() {
        var builder = VaubanContainer.builder().addBeanClass(ErrorInit.class);
        assertThrows(ExceptionInInitializerError.class, builder::build);

        assertNull(VaubanContainer.current(), "no half-built container is left behind");
    }

    @Test
    @DisplayName("a throwing Shutdown observer is logged and close() completes")
    void aThrowingShutdownObserverIsLoggedAndCloseCompletes() {
        VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(FailingShutdown.class)
                .addBeanClass(LaterShutdown.class)
                .build();

        container.close();

        assertEquals(CALLS, List.of("FailingShutdown"), "CDI §9.5: later observers are not notified");
        assertNull(VaubanContainer.current(), "close() completed");
        String logged = logged();
        assertEquals(1, count(logged, MARK), logged);
        assertTrue(logged.contains(FailingShutdown.class.getName() + ".first"), logged);
        assertTrue(logged.contains("Shutdown"), logged);
        assertTrue(logged.contains("java.lang.IllegalStateException: boom"), "the stack trace is logged: " + logged);
    }

    /**
     * With annotation reflection forbidden, the key of {@code @Initialized(ApplicationScoped.class)} used to need
     * reflection; the failure was swallowed with the rest, so those observers were silently never notified.
     */
    @Test
    @DisplayName("with reflection forbidden, the context lifecycle qualifiers key without reflection")
    void contextLifecycleQualifiersKeyWithoutReflection() {
        VaubanContainer container = VaubanContainer.builder().addBeanClass(LaterInit.class).build();
        var types = container.eventDispatcher().annotationTypes();
        String previous = System.getProperty(io.vidocq.vauban.core.annotation.AnnotationReflection.PROPERTY);
        System.setProperty(io.vidocq.vauban.core.annotation.AnnotationReflection.PROPERTY, "forbid");
        try {
            for (var literal : List.of(
                    jakarta.enterprise.context.Initialized.Literal.of(ApplicationScoped.class),
                    jakarta.enterprise.context.BeforeDestroyed.Literal.of(ApplicationScoped.class),
                    jakarta.enterprise.context.Destroyed.Literal.of(jakarta.enterprise.context.RequestScoped.class))) {
                var key = types.key(literal);
                assertEquals(types.key(io.vidocq.vauban.indexer.model.DotName.of(literal.annotationType().getName()),
                        java.util.Map.of("value", new io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal(
                                io.vidocq.vauban.indexer.model.DotName.of(scopeOf(literal).getName())))), key);
            }
        } finally {
            if (previous == null) {
                System.clearProperty(io.vidocq.vauban.core.annotation.AnnotationReflection.PROPERTY);
            } else {
                System.setProperty(io.vidocq.vauban.core.annotation.AnnotationReflection.PROPERTY, previous);
            }
            container.close();
        }
    }

    private static Class<?> scopeOf(java.lang.annotation.Annotation literal) {
        return switch (literal) {
            case jakarta.enterprise.context.Initialized i -> i.value();
            case jakarta.enterprise.context.BeforeDestroyed b -> b.value();
            case jakarta.enterprise.context.Destroyed d -> d.value();
            default -> throw new IllegalArgumentException(literal.toString());
        };
    }

    @Test
    @DisplayName("without a throwing observer, nothing is logged")
    void withoutAThrowingObserverNothingIsLogged() {
        VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(LaterInit.class)
                .addBeanClass(StartupObserver.class)
                .addBeanClass(LaterShutdown.class)
                .build();
        container.close();

        assertEquals(CALLS, List.of("LaterInit", "Startup", "LaterShutdown"));
        assertEquals("", logged());
        assertNull(VaubanContainer.current());
    }
}
