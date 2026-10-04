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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;

/**
 * An interface default method the bean inherits, shadowed for the JVM by a declaration the bean
 * does not inherit (BUG-20261004-08). In {@code class C extends Base implements I}, where {@code I}
 * has a default {@code m()} and {@code Base} a private {@code m()}, {@code C}'s member is
 * {@code I.m()} (JLS 8.4.8), yet the JVM resolves {@code invokespecial C.m} — a generated subclass's
 * {@code super.m()} — and {@code invokevirtual C.m} through {@code C}'s superclasses before its
 * superinterfaces (JVMS 5.4.3.3), finds {@code Base.m()} and refuses it: {@code IllegalAccessError}.
 *
 * <p>Generated code reaches such a default through an interface instead: the {@code $$Intercepted}
 * subclass lists one among its direct superinterfaces and invokes the default with
 * {@code invokespecial} on it, a client proxy forwards through it with {@code invokeinterface}.
 * That interface must be accessible from the generated class — no class can list or name one it
 * cannot access (JVMS 5.4.4, JLS 6.6.1) — and when none is, the method is left out: not
 * intercepted, not forwarded, the default body running as it does without Vauban.</p>
 *
 * <p>Internal to Vauban, shared by the run-time intercepted subclass and client proxy generators:
 * this package is exported to the annotation processor only, so this class is not API and may
 * change without notice.</p>
 */
public final class ShadowedDefaults {

    private ShadowedDefaults() {}

    /**
     * Whether a class of {@code beanClass}'s superclass chain declares a method with the name and
     * descriptor of {@code defaultMethod}, an interface default method the bean inherits, that is
     * <em>not</em> a member of the bean — private, or package-private in another package (JLS
     * 8.4.8) — and to which the JVM resolves a call typed by the bean class. A declaration the bean
     * inherits is no shadow: it is the bean's member, and implements the default.
     */
    public static boolean isShadowed(Class<?> beanClass, Method defaultMethod) {
        if (!defaultMethod.isDefault()) return false;
        boolean shadowed = false;
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var declared : c.getDeclaredMethods()) {
                if (declared.getName().equals(defaultMethod.getName())
                        && declared.getReturnType() == defaultMethod.getReturnType()
                        && java.util.Arrays.equals(declared.getParameterTypes(), defaultMethod.getParameterTypes())) {
                    if (BeanMembers.isBusinessMethodOf(beanClass, declared)) return false;
                    shadowed = true;
                }
            }
        }
        return shadowed;
    }

    /**
     * The interface through which a class generated in {@code beanClass}'s package (and class loader
     * and module) can reach {@code defaultMethod} explicitly: its declaring interface when that one
     * is accessible, else an accessible interface among the bean's supertypes that inherits it;
     * {@code null} when there is none. With {@code samePackageAllowed} false — a proxy placed in
     * another package — only public interfaces in an exported package qualify.
     */
    public static Class<?> accessibleOwner(Class<?> beanClass, Method defaultMethod, boolean samePackageAllowed) {
        var declaring = defaultMethod.getDeclaringClass();
        for (var candidate : interfaceClosure(beanClass, declaring)) {
            if (inheritsTheDefault(candidate, defaultMethod)
                    && accessible(candidate, beanClass, samePackageAllowed)) {
                return candidate;
            }
        }
        return null;
    }

    /** The declaring interface first, then the bean's superinterfaces that extend it, nearest first. */
    private static LinkedHashSet<Class<?>> interfaceClosure(Class<?> beanClass, Class<?> declaring) {
        var closure = new LinkedHashSet<Class<?>>();
        closure.add(declaring);
        var queue = new ArrayDeque<Class<?>>();
        for (Class<?> c = beanClass; c != null; c = c.getSuperclass()) {
            java.util.Collections.addAll(queue, c.getInterfaces());
        }
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (declaring.isAssignableFrom(next) && closure.add(next)) {
                java.util.Collections.addAll(queue, next.getInterfaces());
            }
        }
        return closure;
    }

    /** {@code candidate} declares {@code defaultMethod}, or inherits it unchanged. */
    private static boolean inheritsTheDefault(Class<?> candidate, Method defaultMethod) {
        try {
            return candidate.getMethod(defaultMethod.getName(), defaultMethod.getParameterTypes())
                    .equals(defaultMethod);
        } catch (NoSuchMethodException notAMember) {
            return false;
        }
    }

    private static boolean accessible(Class<?> candidate, Class<?> beanClass, boolean samePackageAllowed) {
        if (Modifier.isPublic(candidate.getModifiers())) {
            var module = candidate.getModule();
            return samePackageAllowed
                    ? module.isExported(candidate.getPackageName(), beanClass.getModule())
                            && beanClass.getModule().canRead(module)
                    : module.isExported(candidate.getPackageName());
        }
        return samePackageAllowed
                && candidate.getPackageName().equals(beanClass.getPackageName())
                && candidate.getClassLoader() == beanClass.getClassLoader();
    }
}
