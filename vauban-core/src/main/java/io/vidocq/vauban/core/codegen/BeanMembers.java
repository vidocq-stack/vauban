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
package io.vidocq.vauban.core.codegen;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * Which methods a superclass declares are members of a bean class, and so the ones a class
 * generated for the bean — the intercepted subclass, the client proxy — may override or forward
 * (JLS 8.4.8). One decision for every run-time generator and for the interceptor chain, which keeps
 * them in line with the processor's front-ends ({@code Elements#getAllMembers}).
 *
 * <p>Internal to Vauban: this package is exported to the annotation processor only, so this class
 * is not API and may change without notice.</p>
 */
public final class BeanMembers {

    private BeanMembers() {}

    /**
     * Annotations that make a method something other than a business method, so that no generated
     * class intercepts it with {@code @AroundInvoke}: an {@code @Inject} initializer (CDI 4.1), the
     * target class's own interceptor methods, and the lifecycle callbacks, which only the
     * interceptors' lifecycle methods intercept (Jakarta Interceptors 2.2, VAU-INT-006). Matched by
     * name, so the EJB ones need no class on the module path, and the processor reads the same list.
     */
    public static final Set<String> NON_BUSINESS_METHOD_ANNOTATIONS = Set.of(
            "jakarta.inject.Inject",
            "jakarta.interceptor.AroundInvoke",
            "jakarta.interceptor.AroundConstruct",
            "jakarta.interceptor.AroundTimeout",
            "jakarta.annotation.PostConstruct",
            "jakarta.annotation.PreDestroy",
            "jakarta.ejb.PostActivate",
            "jakarta.ejb.PrePassivate");

    /** Whether {@code method} carries one of {@link #NON_BUSINESS_METHOD_ANNOTATIONS}. */
    public static boolean hasNonBusinessMethodAnnotation(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (NON_BUSINESS_METHOD_ANNOTATIONS.contains(annotation.annotationType().getName())) return true;
        }
        return false;
    }

    /**
     * Whether {@code declaration} is a business method of {@code beanClass}: an instance method that
     * is not private and that the bean inherits (JLS 8.4.8). A package-private method of a class in
     * another runtime package is not inherited, and no subclass of the bean can override it.
     *
     * @param beanClass   the bean class, {@code declaration}'s declaring class or a subclass of it
     * @param declaration a method a class of {@code beanClass}'s superclass chain declares
     */
    public static boolean isBusinessMethodOf(Class<?> beanClass, Method declaration) {
        int modifiers = declaration.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) return false;
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) return true;
        return packagePrivateInherited(beanClass, declaration.getDeclaringClass());
    }

    /**
     * Whether a package-private member of {@code declaring} is inherited by {@code beanClass}: every
     * class from the bean up to its declaring class shares that class's runtime package (JLS 8.4.8),
     * the only case in which a subclass can override it.
     */
    public static boolean packagePrivateInherited(Class<?> beanClass, Class<?> declaring) {
        for (Class<?> c = beanClass; c != null && c != declaring; c = c.getSuperclass()) {
            if (!c.getPackageName().equals(declaring.getPackageName())
                    || c.getClassLoader() != declaring.getClassLoader()) {
                return false;
            }
        }
        return true;
    }
}
