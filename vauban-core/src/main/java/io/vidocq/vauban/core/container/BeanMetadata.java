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
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Intercepted;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.util.AnnotationLiteral;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The bean metadata beans CDI 4.1, 5.5.8, has the container provide in CDI Lite (BUG-20261117-47): a {@code @Default}
 * {@code Bean} for any bean instance, its own metadata; a {@code @Default} {@code Interceptor} for any interceptor
 * instance, its own; an {@code @Intercepted} {@code Bean} for any interceptor instance, the bean it intercepts. Each
 * is {@code @Dependent}, and answers for the instance it is injected into.
 */
final class BeanMetadata {

    /** The qualifier of the metadata of the bean an interceptor intercepts. */
    static final Intercepted INTERCEPTED_LITERAL = new InterceptedLiteral();

    /** The bean whose instance the interceptor being created intercepts. */
    private static final ScopedValue<Bean<?>> INTERCEPTED = ScopedValue.newInstance();
    /** The interceptor being created. */
    private static final ScopedValue<Interceptor<?>> INTERCEPTOR = ScopedValue.newInstance();

    private BeanMetadata() {
    }

    /** The three beans, among the container's built-in ones. */
    static List<Bean<?>> builtInBeans() {
        return List.of(
                new BuiltInBean<>(Bean.class, Set.of(Bean.class), new Factory(null)),
                new BuiltInBean<>(Interceptor.class, Set.of(Interceptor.class), new Factory(INTERCEPTOR)),
                new BuiltInBean<>(Bean.class, Set.of(Bean.class), new Factory(INTERCEPTED),
                        Set.of(INTERCEPTED_LITERAL)));
    }

    /**
     * The factory of the three beans: what {@code bound} is bound to, or, with no value, the bean the injection point
     * being injected belongs to. One class where a lambda each spun three at the first lookup of a bean (vauban#347).
     */
    private static final class Factory implements Supplier<Object> {

        private final ScopedValue<?> bound;

        Factory(ScopedValue<?> bound) {
            this.bound = bound;
        }

        @Override
        public Object get() {
            return bound == null ? injectedInto() : boundOrNull(bound);
        }
    }

    /** {@code @Intercepted}, which the API gives no literal of. */
    @SuppressWarnings("java:S2160") // AnnotationLiteral defines equals and hashCode, as Annotation requires
    private static final class InterceptedLiteral extends AnnotationLiteral<Intercepted> implements Intercepted {
        private static final long serialVersionUID = 1L;
    }

    /** What {@code value} is bound to, or {@code null} outside the creation of an interceptor. */
    private static <T> T boundOrNull(ScopedValue<T> value) {
        return value.isBound() ? value.get() : null;
    }

    /** The bean an injection point being injected belongs to: the metadata of the instance it is injected into. */
    private static Bean<?> injectedInto() {
        var point = VaubanContainer.getCurrentInjectionPoint();
        return point == null ? null : point.getBean();
    }

    /**
     * Runs {@code creation}, which creates an instance of {@code interceptor} with {@code ctx}, the creational context
     * of the instance it intercepts: injected into it, an {@code Interceptor} is {@code interceptor}, an
     * {@code @Intercepted Bean} the bean that context creates an instance of.
     */
    static Object creatingInterceptor(Interceptor<?> interceptor, CreationalContext<?> ctx, Supplier<Object> creation) {
        var bound = ScopedValue.where(INTERCEPTOR, interceptor);
        return ctx instanceof CreationalContextImpl<?> context && context.interceptedBean() instanceof Bean<?> bean
                ? bound.where(INTERCEPTED, bean).call(creation::get)
                : bound.call(creation::get);
    }
}
