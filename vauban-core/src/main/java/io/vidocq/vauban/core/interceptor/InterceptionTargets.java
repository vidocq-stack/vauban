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
package io.vidocq.vauban.core.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * Whether, and why, the container wraps a managed bean in its {@code $$Intercepted} subclass. One
 * walk over the bean's members, shared by the container ({@code InterceptorBeanWrapper}), which
 * asks whether enabled interceptors select each member, and by {@code vauban:generate}, which
 * pre-generates the subclass at build time and asks only whether a member carries a binding
 * ({@link #carriesInterception}): the interceptors enabled in the deployment are not known there,
 * and a subclass the container does not use costs nothing.
 *
 * <p>A bean is wrapped because of, in this order (CDI 4.1 §4.2, Jakarta Interceptors 2.2):</p>
 * <ol>
 *   <li>its class-level bindings, its own and the {@code @Inherited} ones of its superclasses;</li>
 *   <li>a method-level binding of a business method it declares or inherits — from a superclass,
 *       or as an interface default method — that no class of the bean overrides; each method is
 *       resolved from the bean class, as the chain resolves it at invocation time;</li>
 *   <li>the binding of one of its constructors;</li>
 *   <li>an {@code @AroundInvoke} method of the bean class or one of its superclasses.</li>
 * </ol>
 *
 * <p>Internal to Vauban, not API: public for the container in another package of this module and
 * for the Maven plugin; it may change without notice.</p>
 */
public final class InterceptionTargets {

    private InterceptionTargets() {}

    /** Whether a member of the bean makes it intercepted. */
    public interface Bindings {

        /** Whether the bean's class-level bindings select an interceptor. */
        boolean onClass();

        /** Whether {@code method}, a method the bean declares, inherits or gets as a default, is intercepted. */
        boolean onMethod(Method method);

        /** Whether {@code constructor}, one the bean class declares, is intercepted. */
        boolean onConstructor(Constructor<?> constructor);
    }

    /** Why a bean is intercepted: the first member of the walk that makes it so. */
    public sealed interface Cause {
    }

    /** Its class-level bindings. */
    public record ClassLevel() implements Cause {
    }

    /** A business method the bean declares or inherits, as {@link Class#getDeclaredMethods} or {@link Class#getMethods} lists it. */
    public record BoundMethod(Method method) implements Cause {
    }

    /** One of its constructors. */
    public record BoundConstructor(Constructor<?> constructor) implements Cause {
    }

    /** An {@code @AroundInvoke} method of the bean class or of one of its superclasses. */
    public record TargetAroundInvoke(Method method) implements Cause {
    }

    /**
     * The reason {@code beanClass} is intercepted, the first one of the walk described on this class,
     * or {@code null} when it is not.
     */
    public static Cause causeOf(Class<?> beanClass, Bindings bindings) {
        if (bindings.onClass()) return new ClassLevel();
        for (var c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                if (bindings.onMethod(m)) return new BoundMethod(m);
            }
        }
        for (var m : beanClass.getMethods()) {
            if (m.isDefault() && bindings.onMethod(m)) return new BoundMethod(m);
        }
        for (var ctor : beanClass.getDeclaredConstructors()) {
            if (bindings.onConstructor(ctor)) return new BoundConstructor(ctor);
        }
        for (var c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) return new TargetAroundInvoke(m);
            }
        }
        return null;
    }

    /**
     * Whether the container may wrap {@code beanClass}, judged from the bindings its members carry
     * rather than from the interceptors a deployment enables: a non-final class, not itself an
     * {@code @Interceptor}, with a {@link #causeOf cause} when every interceptor binding selects an
     * interceptor. The decision {@code vauban:generate} pre-generates the subclass on, so that on the
     * strict module path the container never has to define it (BUG-20261004-10).
     */
    public static boolean carriesInterception(Class<?> beanClass) {
        if (Modifier.isFinal(beanClass.getModifiers())
                || beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) {
            return false;
        }
        return causeOf(beanClass, new Bindings() {
            @Override
            public boolean onClass() {
                return !InterceptorManager.collectAllBindings(beanClass).isEmpty();
            }

            @Override
            public boolean onMethod(Method method) {
                var found = new LinkedHashMap<Class<? extends Annotation>, Annotation>();
                InterceptorManager.collectDeclarationBindings(beanClass, method, found);
                return !found.isEmpty();
            }

            @Override
            public boolean onConstructor(Constructor<?> constructor) {
                var found = new LinkedHashMap<Class<? extends Annotation>, Annotation>();
                InterceptorManager.collectBindingsRecursively(constructor.getAnnotations(), found,
                        new HashSet<>());
                return !found.isEmpty();
            }
        }) != null;
    }
}
