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

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;

/**
 * The serial form of a client proxy: its bean's passivation id and the type the proxy extends or implements. A
 * client proxy writes its delegate ({@link ContextualInstance}), which writes this reference, so a session value that
 * holds a proxy moves to another container (foy#114). It reads back as the importing container's client proxy of the
 * same bean, or throws {@link InvalidObjectException} with a message that names the cause, never as {@code null}:
 * the web container drops the session either way.
 *
 * @param passivationId the bean's passivation id, its {@code BeanId}
 * @param typeName      the binary name of the proxied type: the bean class of a subclass proxy, the interface of an
 *                      interface proxy
 */
record ClientProxyReference(String passivationId, String typeName) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The importing container's client proxy of the bean with the same id: the one it caches, so that proxy identity
     * holds within that container ({@link VaubanBeanManager#getReference} returns it for a normal-scoped bean). The
     * bean must still have a normal scope there: a {@code @Dependent} bean has no proxy, and its reference would be a
     * new instance in the session's place.
     */
    @Serial
    private Object readResolve() throws ObjectStreamException {
        try {
            return proxyInImportingContainer();
        } catch (RuntimeException e) {
            throw new InvalidObjectException("the importing container gives no client proxy of " + passivationId
                    + ": " + e, e);
        }
    }

    /** The importing container's proxy of the bean; a failure of the container is the caller's to wrap. */
    private Object proxyInImportingContainer() throws InvalidObjectException {
        if (passivationId == null || typeName == null) {
            throw new InvalidObjectException("an incomplete client proxy reference in the stream");
        }
        BeanManager beanManager = ImportingContainer.beanManager();
        Bean<?> bean = ImportingContainer.bean(beanManager, passivationId);
        if (!beanManager.isNormalScope(bean.getScope())) {
            throw new InvalidObjectException("the bean " + passivationId + " has the pseudo-scope "
                    + bean.getScope().getName() + " in the importing container: a client proxy reads back as the"
                    + " proxy of a normal-scoped bean only");
        }
        if (!isProxiedBy(bean)) {
            throw new InvalidObjectException("the client proxy written for " + passivationId + " proxies "
                    + typeName + ", which is neither the bean class nor a bean type in the importing container: "
                    + bean.getTypes());
        }
        return beanManager.getReference(bean, Object.class, beanManager.createCreationalContext(bean));
    }

    /**
     * Whether a proxy of {@code typeName} can stand for {@code bean}: the bean class (a managed bean's subclass
     * proxy, even when {@code @Typed} leaves its class out of the bean types), or one of the classes among its bean
     * types. This catches a synthetic bean whose id now names a bean of another type, its registration slot having
     * shifted, but not one whose id now names another bean of the same type, such as a second persistence unit's
     * {@code EntityManager}: the read then resolves to that bean. The types are read as objects: a parameterized type
     * is no proxied type.
     */
    private boolean isProxiedBy(Bean<?> bean) {
        if (typeName.equals(bean.getBeanClass().getName())) {
            return true;
        }
        for (Object type : bean.getTypes()) {
            if (type instanceof Class<?> beanType && typeName.equals(beanType.getName())) {
                return true;
            }
        }
        return false;
    }
}
