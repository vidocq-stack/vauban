/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package io.vidocq.vauban.experiment.app;

import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import io.vidocq.vauban.core.container.ExperimentalBootstrap;
import io.vidocq.vauban.experiment.beans.FallbackService;
import io.vidocq.vauban.experiment.extension.ReflectiveProbe;
import jakarta.enterprise.inject.se.ExperimentalPackageOpener;
import jakarta.enterprise.inject.se.SeContainer;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Each scenario must run in its own ordinary module-path JVM. */
public final class Main {
    private static final Class<?> BEAN = FallbackService.class;
    private static final Module LIBRARY = BEAN.getModule();
    private static final String PACKAGE = BEAN.getPackageName();
    private static final Module CDI = ExperimentalPackageOpener.class.getModule();
    private static final Module CONTAINER = ExperimentalBootstrap.class.getModule();
    private static final Module FT = ModuleLayer.boot().findModule("io.vidocq.heisenberg.core").orElseThrow();
    private static final Module INTERCEPTOR = FaultToleranceInterceptor.class.getModule();
    private static final Module EXTENSION = ReflectiveProbe.class.getModule();
    private static final Class<?>[] BEANS = {
            HeisenbergExtension.class, FaultToleranceInterceptor.class,
            StateRegistryBean.class, BulkheadStateRegistryBean.class, BEAN
    };

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        check(Runtime.version().feature() == 25, "Use Java 25 for recorded results");
        for (Module module : List.of(LIBRARY, CDI, CONTAINER, FT, INTERCEPTOR, EXTENSION)) {
            check(module.isNamed() && !module.getDescriptor().isAutomatic(), "Explicit named module: " + module);
            check(module.getLayer() == ModuleLayer.boot(), "No application-owned module layer: " + module);
        }
        check(BEAN.getClassLoader() == ClassLoader.getSystemClassLoader(), "No Vauban-owned bean class loader");
        boolean donation = scenario.equals("donation");
        boolean closed = scenario.equals("missing-opening");
        check(LIBRARY.isOpen(PACKAGE, CDI) == (!donation && !closed), "Expected module descriptor");
        check(!LIBRARY.isOpen(PACKAGE, CONTAINER), "Initially closed to container");
        check(!LIBRARY.isOpen(PACKAGE, FT), "Initially closed to FT");
        check(!LIBRARY.isOpen(PACKAGE, EXTENSION), "Initially closed to extension");
        String expectedProvider = PACKAGE + (donation ? "._VaubanComponents" : ".RelayComponents");
        check(ExperimentalBootstrap.providersFor(BEAN).equals(List.of(expectedProvider)),
                "Only expected generated provider may be registered");
        check(!ReflectiveProbe.canAccess(BEAN), "Extension initially cannot reflect");

        switch (scenario) {
            case "donation" -> donation();
            case "authorized" -> authorized();
            case "unauthorized" -> unauthorized();
            case "wrong-recipient" -> wrongRecipient();
            case "missing-opening" -> missingOpening();
            case "extension" -> extension(true);
            case "unauthorized-extension" -> extension(false);
            case "shared-module" -> sharedModule();
            case "spoofed-provider" -> spoofedProvider();
            default -> throw new IllegalArgumentException("Unknown scenario " + scenario);
        }
        System.out.println("PASS " + scenario);
    }

    private static void donation() {
        try (var container = ExperimentalBootstrap.select(Set.of()).initialize(BEANS)) {
            check(ExperimentalBootstrap.hasDonatedLookup(BEAN), "Donation baseline supplies a lookup");
            invokeFallbacks(container);
            check(!LIBRARY.isOpen(PACKAGE, FT), "Donation did not open the package to FT");
        }
        check(!ExperimentalBootstrap.hasDonatedLookup(BEAN), "Donation registry removed on close");
    }

    private static void authorized() throws NoSuchMethodException {
        var bootstrap = withContainerOpening(Set.of(FT));
        bootstrap.delegate(BEAN, FT);
        check(LIBRARY.isOpen(PACKAGE, FT), "Authorized FT module receives opening");
        check(!LIBRARY.isOpen(PACKAGE, INTERCEPTOR), "Interceptor module is not automatically granted access");
        try (var container = bootstrap.initialize(BEANS)) {
            assertNoDonation();
            invokeFallbacks(container);
            check(!ReflectiveProbe.canAccess(BEAN), "Unrelated extension remains denied");
        }
    }

    private static void unauthorized() {
        var bootstrap = withContainerOpening(Set.of());
        expectSecurity(() -> bootstrap.delegate(BEAN, FT));
        check(!LIBRARY.isOpen(PACKAGE, FT), "Rejected request must not mutate module graph");
        expectFallbackAccessFailure(bootstrap);
    }

    private static void wrongRecipient() {
        var bootstrap = withContainerOpening(Set.of(INTERCEPTOR));
        bootstrap.delegate(BEAN, INTERCEPTOR);
        check(LIBRARY.isOpen(PACKAGE, INTERCEPTOR), "Only the named interceptor module received access");
        check(!LIBRARY.isOpen(PACKAGE, FT), "Actual fallback resolver module remains closed");
        expectFallbackAccessFailure(bootstrap);
    }

    private static void missingOpening() {
        var bootstrap = ExperimentalBootstrap.select(Set.of(FT));
        expectSecurity(() -> bootstrap.openLibrary(BEAN));
        check(!LIBRARY.isOpen(PACKAGE, CONTAINER), "Missing library consent cannot be bypassed");
        check(!LIBRARY.isOpen(PACKAGE, FT), "No downstream relay");
    }

    private static void extension(boolean authorize) throws Exception {
        var bootstrap = withContainerOpening(authorize ? Set.of(FT, EXTENSION) : Set.of(FT));
        bootstrap.delegate(BEAN, FT);
        if (authorize) {
            bootstrap.delegate(BEAN, EXTENSION);
        } else {
            expectSecurity(() -> bootstrap.delegate(BEAN, EXTENSION));
        }
        try (var container = bootstrap.initialize(BEANS)) {
            assertNoDonation();
            invokeFallbacks(container);
            check(ReflectiveProbe.canAccess(BEAN) == authorize, "Reflection follows exact extension grant");
            var service = container.select(FallbackService.class).get();
            if (authorize) {
                check("private fallback for reflection".equals(ReflectiveProbe.invoke(BEAN, service, "reflection")),
                        "Existing Method.invoke works after relay");
            } else {
                try {
                    ReflectiveProbe.invoke(BEAN, service, "reflection");
                    throw new AssertionError("Reflection must fail without extension grant");
                } catch (IllegalAccessException expected) {
                    System.out.println("Expected reflective access refusal");
                }
            }
        }
    }

    private static void sharedModule() {
        var first = withContainerOpening(Set.of(FT));
        first.delegate(BEAN, FT);
        try (var one = first.initialize(BEANS)) {
            invokeFallbacks(one);
            // No consent on the second container. Module.addOpens is already effective JVM-wide
            // for these exact Module objects: this deliberately records a limitation.
            var second = ExperimentalBootstrap.select(Set.of());
            try (var two = second.initialize(BEANS)) {
                assertNoDonation();
                invokeFallbacks(two);
                System.out.println("Two containers sharing Module objects share the opening");
            }
        }
        check(LIBRARY.isOpen(PACKAGE, FT), "Closing both containers does not revoke Module.addOpens");
    }

    private static void spoofedProvider() {
        expectSecurity(() -> ExperimentalPackageOpener.forProvider(MethodHandles.lookup()));
        check(!LIBRARY.isOpen(PACKAGE, CONTAINER), "Non-provider factory call has no access side effect");
        check(!LIBRARY.isOpen(PACKAGE, FT), "Non-provider call did not grant FT");
    }

    private static ExperimentalBootstrap withContainerOpening(Set<Module> recipients) {
        var bootstrap = ExperimentalBootstrap.select(recipients);
        bootstrap.openLibrary(BEAN);
        check(LIBRARY.isOpen(PACKAGE, CONTAINER), "CDI relayed opening to actual selected provider");
        check(!LIBRARY.isOpen(PACKAGE, FT), "Provider access alone grants nothing to FT");
        return bootstrap;
    }

    private static void invokeFallbacks(SeContainer container) {
        var service = container.select(FallbackService.class).get();
        check("private fallback for duke".equals(service.callWithPrivateFallback("duke")), "Private fallback");
        check("package-private fallback for ada".equals(service.callWithPackagePrivateFallback("ada")),
                "Package-private fallback");
        System.out.println("Real FT interceptor invoked private and package-private fallbacks");
    }

    private static void assertNoDonation() {
        check(!ExperimentalBootstrap.hasDonatedLookup(BEAN), "Relay case must never use a donated lookup");
    }

    private static void expectFallbackAccessFailure(ExperimentalBootstrap bootstrap) {
        try (var container = bootstrap.initialize(BEANS)) {
            assertNoDonation();
            invokeFallbacks(container);
        } catch (RuntimeException failure) {
            var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            if (hasFallbackAccessFailure(failure, seen)) {
                System.out.println("Expected FT IllegalAccessException: bean package is not open to " + FT.getName());
                return;
            }
            throw new AssertionError("Failure was unrelated to the expected FT access refusal", failure);
        }
        throw new AssertionError("FT unexpectedly succeeded without access");
    }

    private static boolean hasFallbackAccessFailure(Throwable failure, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) {
            return false;
        }
        if (failure instanceof IllegalAccessException
                && String.valueOf(failure.getMessage()).contains(FT.getName())
                && String.valueOf(failure.getMessage()).contains(PACKAGE)) {
            return true;
        }
        if (hasFallbackAccessFailure(failure.getCause(), seen)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (hasFallbackAccessFailure(suppressed, seen)) {
                return true;
            }
        }
        return false;
    }

    private static void expectSecurity(Runnable action) {
        try {
            action.run();
        } catch (SecurityException expected) {
            System.out.println("Expected policy refusal: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected SecurityException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
