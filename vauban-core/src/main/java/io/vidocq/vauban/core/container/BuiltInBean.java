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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Represents a CDI built-in bean (BeanManager, Event, Instance, InjectionPoint).
 */
public final class BuiltInBean<T> implements Bean<T> {

    private final Class<T> beanClass;
    private final Set<Type> types;
    private final Supplier<T> supplier;
    private final Set<Annotation> qualifiers;

    @SuppressWarnings("unchecked")
    public BuiltInBean(Class<?> beanClass, Set<Type> types, Supplier<?> supplier) {
        this.beanClass = (Class<T>) beanClass;
        this.types = Set.copyOf(types);
        this.supplier = (Supplier<T>) supplier;
        this.qualifiers = Set.of(
            Default.Literal.INSTANCE,
            Any.Literal.INSTANCE
        );
    }

    @Override public T create(CreationalContext<T> ctx) { return supplier.get(); }
    @Override public void destroy(T instance, CreationalContext<T> ctx) { /* Built-in beans have no destruction lifecycle */ }
    @Override public Class<?> getBeanClass() { return beanClass; }
    @Override public Set<Type> getTypes() { return types; }
    @Override public Set<Annotation> getQualifiers() { return qualifiers; }
    @Override public Class<? extends Annotation> getScope() { return Dependent.class; }
    @Override public String getName() { return null; }
    @Override public Set<Class<? extends Annotation>> getStereotypes() { return Set.of(); }
    @Override public boolean isAlternative() { return false; }
    @Override public Set<InjectionPoint> getInjectionPoints() { return Set.of(); }
}
