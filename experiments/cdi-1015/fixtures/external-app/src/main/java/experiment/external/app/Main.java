/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package experiment.external.app;

import experiment.external.beans.FallbackService;
import experiment.external.beans.FallbackState;
import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import io.vidocq.vauban.core.container.ExperimentalBootstrap;
import jakarta.enterprise.inject.se.ExperimentalPackageOpener;
import jakarta.enterprise.inject.se.SeContainer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Runs each external-library scenario in a fresh, ordinary module-path JVM. */
public final class Main {
    private static final Class<?> BEAN = FallbackService.class;
    private static final Module LIBRARY = BEAN.getModule();
    private static final String PACKAGE = BEAN.getPackageName();
    private static final Module CDI = ExperimentalPackageOpener.class.getModule();
    private static final Module CONTAINER = ExperimentalBootstrap.class.getModule();
    private static final Module FT = ModuleLayer.boot().findModule("io.vidocq.heisenberg.core").orElseThrow();
    private static final Module INTERCEPTOR = FaultToleranceInterceptor.class.getModule();
    private static final Class<?>[] BEANS = {
            HeisenbergExtension.class, FaultToleranceInterceptor.class,
            StateRegistryBean.class, BulkheadStateRegistryBean.class,
            FallbackService.class, FallbackState.class
    };

    public static void main(String[] args) {
        String scenario = args[0];
        check(Runtime.version().feature() == 25, "Use Java 25 for recorded results");
        for (Module module : List.of(LIBRARY, CDI, CONTAINER, FT, INTERCEPTOR)) {
            check(module.isNamed() && !module.getDescriptor().isAutomatic(), "Explicit named module: " + module);
            check(module.getLayer() == ModuleLayer.boot(), "Ordinary boot module layer: " + module);
        }
        check(BEAN.getClassLoader() == ClassLoader.getSystemClassLoader(),
                "The external library uses the ordinary application class loader");
        check(LIBRARY.getDescriptor().requires().stream()
                        .noneMatch(requirement -> requirement.name().startsWith("io.vidocq.")),
                "The library descriptor must not depend on any implementation module");
        check(LIBRARY.getDescriptor().provides().isEmpty(), "The library provides no container service");
        check(ExperimentalBootstrap.providersFor(BEAN).isEmpty(), "No generated component provider");
        assertNoDonation();
        check(LIBRARY.isOpen(PACKAGE, CDI) == !scenario.equals("external-closed"),
                "Expected library opening to the CDI API");
        check(!LIBRARY.isOpen(PACKAGE, CONTAINER), "Initially closed to the container");
        check(!LIBRARY.isOpen(PACKAGE, FT), "Initially closed to FT");
        check(!LIBRARY.canRead(CONTAINER), "No initial read dependency on the container");
        assertOrdinaryLaunch();

        switch (scenario) {
            case "external-authorized" -> authorized();
            case "external-unauthorized" -> unauthorized();
            case "external-closed" -> closed();
            default -> throw new IllegalArgumentException("Unknown scenario " + scenario);
        }
        System.out.println("PASS " + scenario);
    }

    private static void authorized() {
        var bootstrap = withContainerOpening(Set.of(FT));
        bootstrap.delegate(BEAN, FT);
        check(LIBRARY.isOpen(PACKAGE, FT), "Application authorized the actual fallback resolver");
        check(!LIBRARY.isOpen(PACKAGE, INTERCEPTOR), "Interception alone does not grant reflective access");
        try (var container = bootstrap.initialize(BEANS)) {
            assertNoDonation();
            invokeFallbacks(container);
        }
    }

    private static void unauthorized() {
        var bootstrap = withContainerOpening(Set.of());
        expectSecurity(() -> bootstrap.delegate(BEAN, FT));
        check(!LIBRARY.isOpen(PACKAGE, FT), "Rejected grant leaves FT access closed");
        try (var container = bootstrap.initialize(BEANS)) {
            assertNoDonation();
            invokeFallbacks(container);
        } catch (RuntimeException failure) {
            var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            if (hasFallbackAccessFailure(failure, seen)) {
                System.out.println("Expected FT IllegalAccessException for the external library");
                return;
            }
            throw new AssertionError("Failure was unrelated to FT access refusal", failure);
        }
        throw new AssertionError("FT unexpectedly succeeded without application authorization");
    }

    private static void closed() {
        var bootstrap = ExperimentalBootstrap.select(Set.of(FT));
        expectSecurity(() -> bootstrap.openLibrary(BEAN));
        check(!LIBRARY.isOpen(PACKAGE, CONTAINER), "Missing library consent cannot be bypassed");
        check(!LIBRARY.isOpen(PACKAGE, FT), "No downstream access without library consent");
        check(!LIBRARY.canRead(CONTAINER), "Rejected opening cannot add a container read");
        assertNoDonation();
    }

    private static ExperimentalBootstrap withContainerOpening(Set<Module> recipients) {
        var bootstrap = ExperimentalBootstrap.select(recipients);
        bootstrap.openLibrary(BEAN);
        check(LIBRARY.isOpen(PACKAGE, CONTAINER), "CDI relayed the opening to the selected container");
        check(LIBRARY.canRead(CONTAINER), "The container prepared the read needed by generated code");
        check(!LIBRARY.isOpen(PACKAGE, FT), "Container access alone grants no FT access");
        return bootstrap;
    }

    private static void invokeFallbacks(SeContainer container) {
        var service = container.select(FallbackService.class).get();
        check(service.getClass().getName().equals(BEAN.getName() + "_ClientProxy"),
                "The normal-scoped bean uses Vauban's runtime-generated client proxy");
        check(service.getClass().getModule() == LIBRARY, "The proxy belongs to the external library module");
        check("private fallback for duke:external:1".equals(service.callWithPrivateFallback("duke")),
                "Private fallback sees the injected state");
        check("package-private fallback for ada:external:2"
                        .equals(service.callWithPackagePrivateFallback("ada")),
                "Package-private fallback shares the contextual bean state");
        check("private fallback for bob:external:3".equals(service.callWithPrivateFallback("bob")),
                "Repeated proxy calls retain the contextual bean state");
        assertNoDonation();
        System.out.println("External library: runtime proxy, private injection and both FT fallbacks work");
    }

    private static void assertNoDonation() {
        check(!ExperimentalBootstrap.hasDonatedLookup(BEAN), "No donated library lookup");
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

    private static void assertOrdinaryLaunch() {
        for (String argument : ProcessHandle.current().info().arguments().orElseThrow()) {
            check(!argument.startsWith("--add-opens")
                            && !argument.startsWith("--add-reads")
                            && !argument.startsWith("--add-exports")
                            && !argument.startsWith("--patch-module")
                            && !argument.startsWith("-javaagent:")
                            && !argument.startsWith("-agentlib:")
                            && !argument.startsWith("-agentpath:"),
                    "No launcher access override or agent: " + argument);
        }
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
