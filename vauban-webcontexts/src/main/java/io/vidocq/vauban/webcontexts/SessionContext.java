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

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.PassivationCapable;
import java.lang.annotation.Annotation;

/**
 * The context of {@link SessionScoped}: active on a thread while a web container has bound a
 * session to it ({@link WebContexts#activateSession}). Each instance lives in the session's own
 * storage, under one attribute per bean, so it lasts as long as the session and is seen by every
 * request of that session.
 *
 * <p>Public with a public no-arg constructor: the container instantiates it, from the
 * registration made by {@link WebContextsExtension}.</p>
 */
public final class SessionContext implements AlterableContext {

    /** The prefix of the session attributes that hold an instance. */
    static final String ATTRIBUTE_PREFIX = "io.vidocq.vauban.webcontexts.session:";

    /**
     * The session bound to the current thread. A thread-local, not a scoped value: a web
     * container binds and unbinds the session from separate callbacks (request listeners).
     */
    static final ThreadLocal<SessionStore> CURRENT = new ThreadLocal<>();

    @Override
    public Class<? extends Annotation> getScope() {
        return SessionScoped.class;
    }

    @Override
    public boolean isActive() {
        return CURRENT.get() != null;
    }

    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        SessionStore store = current();
        String key = key(contextual);
        synchronized (store.mutex()) {
            if (store.getAttribute(key) instanceof Entry<?> entry) {
                @SuppressWarnings("unchecked")
                T instance = (T) entry.instance();
                return instance;
            }
            if (creationalContext == null) {
                return null;
            }
            T instance = contextual.create(creationalContext);
            store.setAttribute(key, new Entry<>(contextual, instance, creationalContext));
            return instance;
        }
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        return get(contextual, null);
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        SessionStore store = current();
        String key = key(contextual);
        Object removed;
        synchronized (store.mutex()) {
            removed = store.getAttribute(key);
            store.removeAttribute(key);
        }
        if (removed instanceof Entry<?> entry) {
            entry.destroy();
        }
    }

    /** Destroys every instance the session holds; the session stays bound to nothing. */
    static void destroyAll(SessionStore store) {
        for (String name : store.attributeNames()) {
            if (name.startsWith(ATTRIBUTE_PREFIX)) {
                Object value = store.getAttribute(name);
                store.removeAttribute(name);
                if (value instanceof Entry<?> entry) {
                    entry.destroy();
                }
            }
        }
    }

    private static SessionStore current() {
        SessionStore store = CURRENT.get();
        if (store == null) {
            throw new ContextNotActiveException("SessionScoped is not active: no session is bound to this thread");
        }
        return store;
    }

    /**
     * One attribute per bean. A passivation-capable bean has an id that the container keeps stable;
     * otherwise the bean's class and qualifiers, and the identity of its Bean object, which is
     * the same for the life of the container.
     */
    private static String key(Contextual<?> contextual) {
        if (contextual instanceof PassivationCapable capable && capable.getId() != null) {
            return ATTRIBUTE_PREFIX + capable.getId();
        }
        String name = contextual instanceof Bean<?> bean
                ? bean.getBeanClass().getName() + bean.getQualifiers()
                : contextual.getClass().getName();
        return ATTRIBUTE_PREFIX + name + "@" + Integer.toHexString(System.identityHashCode(contextual));
    }

    /** An instance, with what is needed to destroy it. */
    record Entry<T>(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext) {

        void destroy() {
            contextual.destroy(instance, creationalContext);
        }
    }
}
