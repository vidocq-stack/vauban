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

import io.vidocq.vauban.api.Passivation;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.PassivationCapable;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Annotation;

/**
 * The context of {@link SessionScoped}: active on a thread while a web container has bound a
 * session to it ({@link WebContexts#activateSession}). Each instance lives in the session's own
 * storage, under one attribute per bean, so it lasts as long as the session and is seen by every
 * request of that session.
 *
 * <p>An attribute can move to another container with its session, as a web container moves the
 * live sessions when Vidocq swaps the application layer (foy#114): it is written with Java
 * serialization as its bean's passivation id and its instance, and read back while the importing
 * container is bound ({@link Passivation#importInto}). Neither side runs a lifecycle callback:
 * this is passivation.</p>
 *
 * <p>Public with a public no-arg constructor: the container instantiates it, from the
 * registration made by {@link WebContextsExtension}.</p>
 */
public final class SessionContext implements AlterableContext {

    /** The prefix of the session attributes that hold an instance. */
    static final String ATTRIBUTE_PREFIX = "io.vidocq.vauban.webcontexts.session:";

    /**
     * The suffix vauban-core gives the subclass it generates for an intercepted bean
     * ({@code InterceptedShape.SUBCLASS_SUFFIX}); this module does not read vauban-core.
     */
    private static final String INTERCEPTED_SUFFIX = "$$Intercepted";

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
            if (store.getAttribute(key) instanceof Entry<?> entry && !entry.isSentinel()) {
                @SuppressWarnings("unchecked")
                T instance = (T) entry.instance();
                return instance;
            }
            if (creationalContext == null) {
                return null;
            }
            T instance = contextual.create(creationalContext);
            store.setAttribute(key, new Entry<>(contextual, instance, creationalContext, store.isSession(instance)));
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
     * One attribute per bean. A passivation-capable bean, as every Vauban bean is, is keyed by its
     * id, which two containers built from the same classes share for a managed bean and a producer:
     * the attribute of a session that moved is found again. A synthetic bean's id ends with its
     * registration slot, which shifts when beans are added before it, and overloaded producer methods
     * share one id. Otherwise the bean's class and qualifiers, and the identity of its Bean object,
     * which is the same for the life of the container only.
     *
     * <p>Since the key is shared across containers, a store must never be bound to two live
     * containers at once: the second would take the first one's entries as its own. Values go from
     * one container to the next through their serial form only.</p>
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

    /**
     * An instance, with what is needed to destroy it.
     *
     * <p>Serializable, but never written itself: its bean and creational context belong to the
     * container that holds it. It writes its {@link EntryForm} instead, its bean's passivation id and
     * its instance, which reads back as an entry of the importing container. An entry whose instance
     * is the session itself ({@link SessionStore#isSession}) writes a {@link Skipped} form, which
     * reads back as the {@link #SENTINEL}.</p>
     */
    static final class Entry<T> implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * What a skipped entry reads back as: no instance. The context creates one at its next use
         * and replaces the sentinel; destroying the sentinel destroys nothing.
         */
        static final Entry<Object> SENTINEL = new Entry<>(null, null, null, true);

        private final transient Contextual<T> contextual;
        private final transient T instance;
        private final transient CreationalContext<T> creationalContext;
        /** The instance is the session itself: it stays out of the stream. */
        private final transient boolean skipped;

        Entry(Contextual<T> contextual, T instance, CreationalContext<T> creationalContext, boolean skipped) {
            this.contextual = contextual;
            this.instance = instance;
            this.creationalContext = creationalContext;
            this.skipped = skipped;
        }

        T instance() {
            return instance;
        }

        boolean isSentinel() {
            return this == SENTINEL;
        }

        void destroy() {
            if (!isSentinel()) {
                contextual.destroy(instance, creationalContext);
            }
        }

        /**
         * Refuses a stream that carries an entry itself rather than its form: none is ever written,
         * and its fields would read back {@code null}.
         */
        @Serial
        private void readObject(ObjectInputStream in) throws InvalidObjectException {
            throw new InvalidObjectException("an entry is read through its serial form only");
        }

        /**
         * The form written in place of this entry. An instance that cannot move throws, with a
         * message that names the cause: the web container leaves its session behind.
         */
        @Serial
        private Object writeReplace() throws ObjectStreamException {
            if (skipped) {
                return new Skipped();
            }
            if (!(contextual instanceof PassivationCapable capable) || capable.getId() == null) {
                throw new NotSerializableException("contextual has no passivation id: " + contextual);
            }
            if (!(instance instanceof Serializable serializable)) {
                throw new NotSerializableException(
                        "bean class " + instance.getClass().getName() + " is not Serializable");
            }
            if (instance.getClass().getName().endsWith(INTERCEPTED_SUFFIX)) {
                // Its interceptor fields (manager, bindings, chains) belong to the writing container.
                throw new NotSerializableException("intercepted instances cannot move yet: " + capable.getId());
            }
            return new EntryForm(capable.getId(), serializable);
        }
    }

    /**
     * The serial form of an {@link Entry}: its bean's passivation id and its instance. It reads back
     * as an entry of the importing container, or throws {@link InvalidObjectException} with a message
     * that names the cause, never as {@code null}. The web container drops the session either way.
     */
    record EntryForm(String passivationId, Serializable instance) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * The entry of the importing container: its bean under the same id, the instance as it was
         * written, and a new creational context. No {@code @PostConstruct} runs.
         */
        @Serial
        private Object readResolve() throws ObjectStreamException {
            if (passivationId == null) {
                throw new InvalidObjectException("no passivation id in the stream");
            }
            BeanManager beanManager = Passivation.importingBeanManager()
                    .orElseThrow(() -> new InvalidObjectException("no importing container bound"));
            try {
                return entryIn(beanManager);
            } catch (RuntimeException e) {
                throw new InvalidObjectException("the importing container gives no entry of " + passivationId + ": "
                        + e, e);
            }
        }

        /** The entry of {@code beanManager}'s bean with this id; a failure of the container is the caller's. */
        private Object entryIn(BeanManager beanManager) throws InvalidObjectException {
            Bean<?> bean = beanManager.getPassivationCapableBean(passivationId);
            if (bean == null) {
                throw new InvalidObjectException("no bean " + passivationId
                        + " in the importing container: it was removed, renamed or vetoed");
            }
            if (!isOfItsTypes(bean, instance)) {
                throw new InvalidObjectException("the instance written for " + passivationId
                        + " is not of its bean types in the importing container: " + bean.getTypes());
            }
            return entryOf(bean, instance, beanManager);
        }

        private static <T> Entry<T> entryOf(Bean<T> bean, Object instance, BeanManager beanManager) {
            @SuppressWarnings("unchecked") // checked against the bean types
            T typed = (T) instance;
            return new Entry<>(bean, typed, beanManager.createCreationalContext(bean), false);
        }

        /**
         * The instance is of every class among its bean types: the bean class of a managed bean,
         * the class a producer returns, their superclasses and interfaces. A parameterized type is
         * left out, since reading its raw type is reflection, which this module does not use, and
         * {@code Object} always matches. A producer whose return type is parameterized and changed
         * between two swaps can therefore pass, and fail with a {@code ClassCastException} at its
         * first use instead of dropping the session.
         */
        private static boolean isOfItsTypes(Bean<?> bean, Object instance) {
            for (Object type : bean.getTypes()) {
                if (type instanceof Class<?> beanType && !beanType.isInstance(instance)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The serial form of an entry whose instance is the session itself: nothing but the mark. */
    record Skipped() implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Serial
        private Object readResolve() throws ObjectStreamException {
            return Entry.SENTINEL;
        }
    }
}
