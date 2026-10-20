/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package io.vidocq.vauban.core.container;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Experimental SE adapter that prepares module access during container startup.
 * Only explicit class registration with discovery disabled is covered.
 */
public final class ExperimentalSeBootstrap extends SeContainerInitializer {
    private final SeContainerInitializer initializer;
    private final ExperimentalBootstrap bootstrap;
    private final Set<Module> recipients;
    private final List<Class<?>> classes = new ArrayList<>();
    private boolean discoveryDisabled;
    private boolean initialized;

    private ExperimentalSeBootstrap(SeContainerInitializer initializer, Set<Module> recipients) {
        this.initializer = Objects.requireNonNull(initializer, "initializer");
        this.recipients = Set.copyOf(recipients);
        this.bootstrap = ExperimentalBootstrap.configure(initializer, this.recipients);
    }

    /**
     * Wraps the provider selected through {@link SeContainerInitializer#newInstance()}.
     * Register classes and disable discovery on the returned adapter.
     */
    public static SeContainerInitializer configure(SeContainerInitializer initializer,
                                                   Set<Module> recipients) {
        return new ExperimentalSeBootstrap(initializer, recipients);
    }

    @Override
    public SeContainerInitializer addBeanClasses(Class<?>... beanClasses) {
        requireBeforeInitialization();
        var added = List.of(beanClasses);
        initializer.addBeanClasses(beanClasses);
        classes.addAll(added);
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Class<?>... packageClasses) {
        throw unsupportedDiscovery();
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Class<?>... packageClasses) {
        throw unsupportedDiscovery();
    }

    @Override
    public SeContainerInitializer addPackages(Package... packages) {
        throw unsupportedDiscovery();
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Package... packages) {
        throw unsupportedDiscovery();
    }

    @Override
    public SeContainerInitializer addExtensions(Extension... extensions) {
        requireBeforeInitialization();
        initializer.addExtensions(extensions);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SeContainerInitializer addExtensions(Class<? extends Extension>... extensions) {
        requireBeforeInitialization();
        initializer.addExtensions(extensions);
        return this;
    }

    @Override
    public SeContainerInitializer enableInterceptors(Class<?>... interceptorClasses) {
        requireBeforeInitialization();
        var added = List.of(interceptorClasses);
        initializer.enableInterceptors(interceptorClasses);
        classes.addAll(added);
        return this;
    }

    @Override
    public SeContainerInitializer enableDecorators(Class<?>... decoratorClasses) {
        requireBeforeInitialization();
        var added = List.of(decoratorClasses);
        initializer.enableDecorators(decoratorClasses);
        classes.addAll(added);
        return this;
    }

    @Override
    public SeContainerInitializer selectAlternatives(Class<?>... alternativeClasses) {
        requireBeforeInitialization();
        initializer.selectAlternatives(alternativeClasses);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SeContainerInitializer selectAlternativeStereotypes(
            Class<? extends Annotation>... alternativeStereotypes) {
        requireBeforeInitialization();
        initializer.selectAlternativeStereotypes(alternativeStereotypes);
        return this;
    }

    @Override
    public SeContainerInitializer addProperty(String key, Object value) {
        requireBeforeInitialization();
        initializer.addProperty(key, value);
        return this;
    }

    @Override
    public SeContainerInitializer setProperties(Map<String, Object> properties) {
        requireBeforeInitialization();
        initializer.setProperties(properties);
        return this;
    }

    @Override
    public SeContainerInitializer disableDiscovery() {
        requireBeforeInitialization();
        initializer.disableDiscovery();
        discoveryDisabled = true;
        return this;
    }

    @Override
    public SeContainerInitializer setClassLoader(ClassLoader classLoader) {
        requireBeforeInitialization();
        initializer.setClassLoader(classLoader);
        return this;
    }

    @Override
    public SeContainer initialize() {
        requireBeforeInitialization();
        if (!discoveryDisabled) {
            throw unsupportedDiscovery();
        }
        initialized = true;
        Module cdi = SeContainerInitializer.class.getModule();
        var packages = new LinkedHashMap<BeanPackage, Class<?>>();
        for (Class<?> beanClass : classes) {
            Module library = beanClass.getModule();
            String packageName = beanClass.getPackageName();
            if (library.isNamed() && library.isOpen(packageName, cdi)) {
                packages.putIfAbsent(new BeanPackage(library, packageName), beanClass);
            }
        }
        for (Class<?> beanClass : packages.values()) {
            bootstrap.openLibrary(beanClass);
            for (Module recipient : recipients) {
                bootstrap.delegate(beanClass, recipient);
            }
        }
        return bootstrap.initializeConfigured();
    }

    private void requireBeforeInitialization() {
        if (initialized) {
            throw new IllegalStateException("This initializer has already been used");
        }
    }

    private static UnsupportedOperationException unsupportedDiscovery() {
        return new UnsupportedOperationException(
                "This experiment supports disableDiscovery() and addBeanClasses() only");
    }

    private record BeanPackage(Module module, String name) {
    }
}
