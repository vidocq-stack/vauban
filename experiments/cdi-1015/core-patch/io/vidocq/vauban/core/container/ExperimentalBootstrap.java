/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.access.ModuleLookups;
import jakarta.enterprise.inject.se.ExperimentalPackageOpener;
import jakarta.enterprise.inject.se.SeContainer;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Experiment-only adapter compiled into a local copy of the real Vauban core jar.
 * The application supplies exact recipient Module objects when configuring bootstrap.
 * This models application policy, not a sandbox protecting an application from arbitrary
 * code allowed to start another container. No interceptor discovery grants access.
 */
public final class ExperimentalBootstrap {
    private final ExperimentalPackageOpener opener;
    private final Set<Module> authorizedRecipients;
    private boolean initialized;

    private ExperimentalBootstrap(Set<Module> authorizedRecipients) {
        this.authorizedRecipients = Set.copyOf(authorizedRecipients);
        this.opener = ExperimentalPackageOpener.forProvider(MethodHandles.lookup());
        if (opener.providerModule() != ExperimentalBootstrap.class.getModule()) {
            throw new AssertionError("The selected initializer must belong to this provider");
        }
    }

    public static ExperimentalBootstrap select(Set<Module> authorizedRecipients) {
        return new ExperimentalBootstrap(authorizedRecipients);
    }

    /** Relays only the library's declared consent to the selected CDI provider. */
    public void openLibrary(Class<?> beanClass) {
        requireBeforeInitialization();
        opener.openPackage(MethodHandles.lookup(), beanClass.getModule(), beanClass.getPackageName());
    }

    /** Additional recipients require the application-supplied grant, checked before mutation. */
    public void delegate(Class<?> beanClass, Module recipient) {
        requireBeforeInitialization();
        Objects.requireNonNull(recipient, "recipient");
        if (!authorizedRecipients.contains(recipient)) {
            throw new SecurityException("Application did not authorize " + recipient.getName());
        }
        Module source = beanClass.getModule();
        String pkg = beanClass.getPackageName();
        if (!source.isOpen(pkg, ExperimentalBootstrap.class.getModule())) {
            throw new IllegalStateException("The library must first be opened to the provider");
        }
        source.addOpens(pkg, recipient);
    }

    /** Uses the exact initializer whose module the CDI API authenticated. */
    public SeContainer initialize(Class<?>... beans) {
        requireBeforeInitialization();
        initialized = true;
        return opener.initializer().disableDiscovery().addBeanClasses(beans).initialize();
    }

    public static boolean hasDonatedLookup(Class<?> beanClass) {
        return ModuleLookups.lookupFor(beanClass).isPresent();
    }

    public static List<String> providersFor(Class<?> beanClass) {
        return ServiceLoader.load(VaubanComponentProvider.class).stream()
                .filter(provider -> provider.type().getModule() == beanClass.getModule())
                .map(provider -> provider.type().getName()).sorted().toList();
    }

    private void requireBeforeInitialization() {
        if (initialized) {
            throw new IllegalStateException("Configure access before starting the container");
        }
    }
}
