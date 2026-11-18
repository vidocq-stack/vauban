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
package io.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.invoke.InvokerBuilder;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class VaubanInvokerFactory implements InvokerFactory {

    private final EnhancedClasses.InheritedParameters inherited;
    private final ClassLoader classLoader;
    private final List<VaubanInvokerBuilder> builders = new ArrayList<>();

    public VaubanInvokerFactory(ClassLoader classLoader) {
        this(classLoader, EnhancedClasses.InheritedParameters.NONE);
    }

    /**
     * @param inherited the annotations the {@code @Enhancement} configs of a bean class left on the parameters of the
     *                  methods it inherits (BUG-20261118-55)
     */
    VaubanInvokerFactory(ClassLoader classLoader, EnhancedClasses.InheritedParameters inherited) {
        this.classLoader = classLoader;
        this.inherited = inherited;
    }

    public List<VaubanInvokerBuilder> getBuilders() {
        return builders;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A bean that is no managed bean, a producer, a synthetic or a built-in bean, has no invoker: CDI 4.1 builds
     * one of a method of a managed bean only. It accepted a built-in bean, such as the {@code BeanManager}
     * (BUG-20261117-06). Every invoker that may not be built, of an interceptor, a constructor, a private method, a
     * method of {@code Object} or of another class, makes the factory throw a {@code DeploymentException}, as Weld
     * does; it threw an {@code IllegalStateException}.
     */
    @Override
    public InvokerBuilder<InvokerInfo> createInvoker(BeanInfo bean, MethodInfo method) {
        if (!bean.isClassBean()) {
            var declaring = bean.declaringClass();
            throw new DeploymentException("Cannot build invoker for a bean which is not a managed bean: "
                    + (declaring == null ? bean.types() : declaring.name()));
        }
        if (bean.isInterceptor()) {
            throw new DeploymentException(
                    "Cannot create invoker for interceptor bean: " + bean.declaringClass().name());
        }
        if (method.isConstructor()) {
            throw new DeploymentException(
                    "Cannot create invoker for constructor of: " + bean.declaringClass().name());
        }

        var beanClassName = bean.declaringClass().name();
        if (Modifier.isPrivate(method.modifiers())) {
            throw new DeploymentException(
                    "Cannot create invoker for private method: " + beanClassName + "." + method.name());
        }
        var target = InvokerTarget.of(beanClassName, method);
        if (target.isObjectMethod() && !"toString".equals(method.name())) {
            throw new DeploymentException("Cannot create invoker for Object method: " + method.name());
        }
        if (!target.isObjectMethod() && !declaresOrInherits(bean.declaringClass(), target.declaringClass())) {
            throw new DeploymentException(
                    "Method " + method.name() + " does not belong to bean class " + beanClassName);
        }
        var beanClass = loadable(beanClassName);
        var reflectMethod = beanClass == null ? null : VaubanInvoker.resolve(target, classLoader);
        var builder = new VaubanInvokerBuilder(target, method, reflectMethod, beanClass,
                InvokerShape.of(bean.declaringClass(), method, inherited));
        builders.add(builder);
        return builder;
    }

    /**
     * Whether {@code type}, or one of its superclasses or superinterfaces, is the class named {@code declaring}: the
     * class of a method {@code type} declares or inherits. Read on the language model, as the build reads a class it
     * compiles, so the build and the start refuse the same invoker (BUG-20261117-48).
     */
    private static boolean declaresOrInherits(ClassInfo type, String declaring) {
        if (type == null) {
            return false;
        }
        if (type.name().equals(declaring) || declaresOrInherits(type.superClassDeclaration(), declaring)) {
            return true;
        }
        return type.superInterfacesDeclarations().stream().anyMatch(iface -> declaresOrInherits(iface, declaring));
    }

    /**
     * The bean class, or {@code null} when it has no class file yet: the build compiles it, and describes the
     * invoker by names, which the start resolves (BUG-20261117-48). The start loads every class of the deployment
     * (vauban#98).
     */
    private Class<?> loadable(String beanClassName) {
        try {
            return Class.forName(beanClassName, false, classLoader);
        } catch (ClassNotFoundException | LinkageError _) {
            return null;
        }
    }
}
