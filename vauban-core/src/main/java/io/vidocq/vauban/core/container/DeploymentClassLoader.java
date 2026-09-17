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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deployment-wide class resolution for the build-compatible extension phase (vauban#98).
 *
 * <p>A build-compatible extension reads a deployment the container has already loaded: the
 * index carries every bean, and the container holds the {@link Class} of each one. The
 * extension SPI, however, only hands it names — {@code BeanInfo.declaringClass().name()} —
 * and the container used to resolve them through the loader of the FIRST bean class, so an
 * extension could read the metadata of every bean yet load only those that happened to share
 * that one loader. {@code mvn vidocq:dev} is precisely that layout: the application's classes
 * are defined by the layer's {@code VaubanClassLoader} while its libraries stay on the JVM
 * module path, and {@code InvokerFactory.createInvoker} then threw {@code ClassNotFoundException}
 * for every application bean — CDI 4.1 invokers silently degrading to reflection.
 *
 * <p>This loader answers, in order:
 * <ol>
 *   <li>the {@link Class} objects the container already holds, by binary name — including the
 *       ones a {@code @Discovery} extension added, registered through {@link #register};</li>
 *   <li>the loader the caller configured on the builder, when it set one;</li>
 *   <li>the thread context class loader of the build — in a Vauban layer, the layer's loader,
 *       which sees both the application and the libraries it re-layered;</li>
 *   <li>the loader of one of the bean classes, the historical behaviour, as a last resort.</li>
 * </ol>
 *
 * <p>It is a {@link ClassLoader} rather than a resolution function so the extension phase keeps
 * taking a plain {@code ClassLoader}: {@code BceProcessor}, {@code BceTypeMatcher} and
 * {@code VaubanInvokerFactory} go on calling {@code loadClass(String)} unchanged, and no public
 * signature moves. It defines nothing of its own.
 */
final class DeploymentClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** Classes the container already holds, by binary name. Written while extensions add theirs. */
    private final Map<String, Class<?>> known = new ConcurrentHashMap<>();

    /** The deployment's loaders, in resolution order, deduplicated, never null-valued. */
    private final List<ClassLoader> delegates;

    private DeploymentClassLoader(ClassLoader parent, List<ClassLoader> delegates, Collection<Class<?>> knownClasses) {
        super("vauban-deployment", parent);
        this.delegates = delegates;
        for (var clazz : knownClasses) {
            register(clazz);
        }
    }

    /**
     * Build the resolution for one deployment.
     *
     * @param beanClasses   the bean classes the container loaded, in the order they were added
     * @param configured    the loader set on the builder, or {@code null}
     * @param contextLoader the build's thread context class loader, or {@code null}
     */
    static DeploymentClassLoader forDeployment(
            Collection<Class<?>> beanClasses, ClassLoader configured, ClassLoader contextLoader) {
        var loaders = new LinkedHashSet<ClassLoader>();
        if (configured != null) loaders.add(configured);
        if (contextLoader != null) loaders.add(contextLoader);
        // Last resort: the loaders of the bean classes themselves. The first one is what the
        // extension phase used to get on its own; the others are what it used to miss.
        for (var clazz : beanClasses) {
            var loader = clazz.getClassLoader();
            if (loader != null) loaders.add(loader);
        }
        var delegates = List.copyOf(loaders);
        // Class resolution is driven by loadClass below, known classes first; the parent serves
        // the inherited resource methods, getParent(), and a deployment with no loader at all.
        var parent = delegates.isEmpty() ? ClassLoader.getSystemClassLoader() : delegates.getFirst();
        return new DeploymentClassLoader(parent, delegates, beanClasses);
    }

    /**
     * Record a class the container holds, so it is resolved by identity rather than loaded
     * again. Used for the classes a {@code @Discovery} extension adds after the bean classes.
     */
    void register(Class<?> clazz) {
        if (clazz != null) {
            known.putIfAbsent(clazz.getName(), clazz);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code resolve} is not honoured: nothing here is defined by this loader, so every
     * class returned was already linked by the loader that defined it.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            var alreadyKnown = known.get(name);
            if (alreadyKnown != null) return alreadyKnown;
            for (var delegate : delegates) {
                try {
                    return delegate.loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    // Not in this part of the deployment — ask the next one.
                }
            }
            // No delegate holds it. The parent is the first delegate whenever there is one, and
            // was asked above; a deployment with no loader at all still gets the platform
            // classes from the system loader rather than a loader that resolves nothing.
            var parent = getParent();
            if (parent != null && !delegates.contains(parent)) {
                return parent.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }
}
