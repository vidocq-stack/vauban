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

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.stream.Stream;

public final class VaubanSeContainer implements SeContainer {

    private final VaubanContainer container;
    private final InstanceImpl<Object> rootInstance;

    VaubanSeContainer(VaubanContainer container) {
        this.container = container;
        this.rootInstance = new InstanceImpl<>(container, Object.class);
    }

    @Override
    public void close() {
        if (!container.isRunning()) {
            throw new IllegalStateException("Container is already shut down");
        }
        container.close();
    }

    @Override
    public boolean isRunning() {
        return container.isRunning();
    }

    @Override
    public BeanManager getBeanManager() {
        if (!container.isRunning()) {
            throw new IllegalStateException("Container is already shut down");
        }
        return container.getBeanManager();
    }

    @Override
    public Instance<Object> select(Annotation... qualifiers) {
        return rootInstance.select(qualifiers);
    }

    @Override
    public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return rootInstance.select(subtype, qualifiers);
    }

    @Override
    public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return rootInstance.select(subtype, qualifiers);
    }

    @Override
    public boolean isUnsatisfied() {
        return rootInstance.isUnsatisfied();
    }

    @Override
    public boolean isAmbiguous() {
        return rootInstance.isAmbiguous();
    }

    @Override
    public boolean isResolvable() {
        return rootInstance.isResolvable();
    }

    @Override
    public Object get() {
        return rootInstance.get();
    }

    @Override
    public void destroy(Object instance) {
        rootInstance.destroy(instance);
    }

    @Override
    public Handle<Object> getHandle() {
        return rootInstance.getHandle();
    }

    @Override
    public Iterable<? extends Handle<Object>> handles() {
        return rootInstance.handles();
    }

    @Override
    public Stream<Object> stream() {
        return rootInstance.stream();
    }

    @Override
    public Iterator<Object> iterator() {
        return rootInstance.iterator();
    }
}
