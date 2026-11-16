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

import io.vidocq.vauban.core.context.CreationalContextImpl;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.Contextual;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.util.function.Supplier;

/**
 * What a client proxy delegates to: the contextual instance of its bean, looked up again on every call, in the
 * context of the bean's scope.
 *
 * <p>Serializable, but never written itself: its container and bean belong to the container that built the proxy. A
 * client proxy writes this delegate in its place ({@code writeReplace}), and the delegate writes a
 * {@link ClientProxyReference} in turn, its bean's passivation id and the proxied type, which reads back as the
 * importing container's proxy of the same bean (foy#114).</p>
 */
final class ContextualInstance implements Supplier<Object>, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient VaubanContainer container;
    private final transient ManagedBean<?> bean;
    /** The binary name of the type the proxy extends or implements. */
    private final transient String proxiedTypeName;

    ContextualInstance(VaubanContainer container, ManagedBean<?> bean, Class<?> proxiedType) {
        this.container = container;
        this.bean = bean;
        this.proxiedTypeName = proxiedType.getName();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object get() {
        ManagedBean<?> currentBean = container.beans.get(bean.descriptor().id());
        if (currentBean == null) {
            currentBean = bean;
        }
        var scopeClass = currentBean.getScope();
        var ctx = container.getFirstContext(scopeClass);
        if (ctx == null) {
            // A normal-scoped proxy call without a registered context must fail loudly
            // (CDI 4.1 §6.5.1) — the previous silent @Dependent fallback handed out a
            // fresh instance per proxy call, so state written through the proxy
            // vanished on the next call (VAU-CTX-001).
            throw new ContextNotActiveException("No context registered for scope " + scopeClass.getName()
                    + " (bean " + currentBean.getBeanClass().getName() + ")");
        }
        return ctx.get((Contextual<Object>) (Contextual<?>) currentBean, new CreationalContextImpl<>());
    }

    /**
     * Refuses a stream that carries a contextual instance itself rather than its form: none is ever written, and its
     * fields would read back {@code null}.
     */
    @Serial
    private void readObject(ObjectInputStream in) throws InvalidObjectException {
        throw new InvalidObjectException("a contextual instance is read through its serial form only");
    }

    /** The reference written in place of this delegate: its bean's passivation id and the proxied type. */
    @Serial
    private Object writeReplace() throws ObjectStreamException {
        return new ClientProxyReference(bean.getId(), proxiedTypeName);
    }
}
