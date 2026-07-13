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
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Vauban implementation of {@link CDI}, providing programmatic access
 * to the running CDI container via {@code CDI.current()}.
 */
public class VaubanCDI extends CDI<Object> {

    static final VaubanCDI INSTANCE = new VaubanCDI();

    @Override
    public BeanManager getBeanManager() {
        return getContainer().getBeanManager();
    }

    @Override
    public Instance<Object> select(Annotation... qualifiers) {
        // CDI 4.1 §11.1: programmatic lookup must honor the given qualifiers —
        // they used to be silently dropped, resolving @Default instead.
        return new InstanceImpl<>(getContainer(), Object.class, qualifiers, null);
    }

    @Override
    public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new InstanceImpl<>(getContainer(), subtype, qualifiers, null);
    }

    @Override
    public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        @SuppressWarnings("unchecked")
        var clazz = (Class<U>) rawClassOf(subtype.getType());
        // Preserve the full generic type so parameterized lookups keep matching.
        return new InstanceImpl<>(getContainer(), clazz, subtype.getType(), qualifiers, null, null);
    }

    private static Class<?> rawClassOf(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() instanceof Class<?> raw) return raw;
        return Object.class;
    }

    @Override
    public boolean isUnsatisfied() {
        return false;
    }

    @Override
    public boolean isAmbiguous() {
        return false;
    }

    @Override
    public boolean isResolvable() {
        return true;
    }

    @Override
    public Object get() {
        throw new IllegalStateException("Cannot call get() on CDI root instance");
    }

    @Override
    public void destroy(Object instance) {
        // No-op
    }

    @Override
    public Handle<Object> getHandle() {
        throw new UnsupportedOperationException("getHandle() not supported on CDI root instance");
    }

    @Override
    public Iterable<? extends Handle<Object>> handles() {
        return List.of();
    }

    @Override
    public Stream<Object> stream() {
        return Stream.empty();
    }

    @Override
    public Iterator<Object> iterator() {
        return Collections.emptyIterator();
    }

    private static VaubanContainer getContainer() {
        var container = VaubanContainer.current();
        if (container == null) {
            throw new IllegalStateException("No Vauban CDI container is running");
        }
        return container;
    }
}
