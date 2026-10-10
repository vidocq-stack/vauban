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

import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import java.lang.annotation.Annotation;
import java.util.Objects;

/**
 * What a web container calls to drive the CDI session context (CDI 4.1 §6.7.2): bind the current
 * request's session to the thread around each request, announce a new session, and end a session.
 *
 * <p>The session is bound per thread: a web container calls {@link #activateSession} when a
 * request starts and {@link #deactivateSession} when it ends, on the thread that serves it.</p>
 */
public final class WebContexts {

    private WebContexts() {
    }

    /** Binds {@code store} to the current thread: {@code @SessionScoped} beans now live in it. */
    public static void activateSession(SessionStore store) {
        SessionContext.CURRENT.set(Objects.requireNonNull(store, "store"));
    }

    /** Unbinds the session from the current thread; the instances stay in the session. */
    public static void deactivateSession() {
        SessionContext.CURRENT.remove();
    }

    /** Fires {@code @Initialized(SessionScoped.class)}, when the web container creates a session. */
    public static void sessionInitialized(BeanManager beanManager, Object payload) {
        fire(beanManager, Initialized.Literal.of(SessionScoped.class), payload);
    }

    /**
     * Ends a session: fires {@code @BeforeDestroyed(SessionScoped.class)}, destroys every
     * {@code @SessionScoped} instance it holds, then fires {@code @Destroyed(SessionScoped.class)}.
     * The session is bound to the current thread while its instances are destroyed, so their
     * {@code @PreDestroy} methods can still use session-scoped beans, and the thread's own binding
     * is restored afterwards: a web container may end a session from a request of another one, or
     * from a thread that serves no request at all, when the session expires.
     */
    public static void destroySession(BeanManager beanManager, SessionStore store, Object payload) {
        Objects.requireNonNull(store, "store");
        SessionStore previous = SessionContext.CURRENT.get();
        SessionContext.CURRENT.set(store);
        try {
            fire(beanManager, BeforeDestroyed.Literal.of(SessionScoped.class), payload);
            SessionContext.destroyAll(store);
        } finally {
            if (previous == null) {
                SessionContext.CURRENT.remove();
            } else {
                SessionContext.CURRENT.set(previous);
            }
        }
        fire(beanManager, Destroyed.Literal.of(SessionScoped.class), payload);
    }

    private static void fire(BeanManager beanManager, Annotation qualifier, Object payload) {
        beanManager.getEvent().select(qualifier).fire(payload);
    }
}
