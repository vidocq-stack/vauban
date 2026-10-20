/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package experiment.external.app;

import experiment.external.beans.FallbackService;
import experiment.external.beans.FallbackState;
import experiment.external.other.OtherService;
import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import java.lang.reflect.InaccessibleObjectException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Runs each external-library scenario in a fresh, ordinary module-path JVM. */
public final class Main {
    private static final Class<?> BEAN = FallbackService.class;
    private static final Module LIBRARY = BEAN.getModule();
    private static final String PACKAGE = BEAN.getPackageName();
    private static final String OTHER_PACKAGE = OtherService.class.getPackageName();
    private static final Module CDI = SeContainerInitializer.class.getModule();
    private static final Module FT = ModuleLayer.boot().findModule("io.vidocq.heisenberg.core").orElseThrow();
    private static final Module INTERCEPTOR = FaultToleranceInterceptor.class.getModule();
    private static final Class<?>[] BEANS = {
            HeisenbergExtension.class, FaultToleranceInterceptor.class,
            StateRegistryBean.class, BulkheadStateRegistryBean.class,
            FallbackService.class, FallbackState.class, OtherService.class
    };

    private final SeContainerInitializer initializer;
    private final Module provider;

    private Main(SeContainerInitializer initializer) {
        this.initializer = initializer;
        this.provider = initializer.getClass().getModule();
    }

    public static void main(String[] args) {
        String scenario = args[0];
        var initializer = switch (scenario) {
            case "external-scoped" -> SeExample.initializer();
            case "external-closed" -> SeContainerInitializer.newInstance().disableDiscovery()
                    .addBeanClasses(FallbackService.class, FallbackState.class, OtherService.class);
            default -> SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(BEANS);
        };
        var test = new Main(initializer);
        test.assertInitialGraph(scenario);
        switch (scenario) {
            case "external-authorized" -> test.authorized();
            case "external-unauthorized" -> test.unauthorized();
            case "external-closed" -> test.closed();
            case "external-scoped" -> test.scoped();
            default -> throw new IllegalArgumentException("Unknown scenario " + scenario);
        }
        System.out.println("PASS " + scenario);
    }

    private void assertInitialGraph(String scenario) {
        check(Runtime.version().feature() == 25, "Use Java 25 for recorded results");
        for (Module module : List.of(LIBRARY, CDI, provider, FT, INTERCEPTOR)) {
            check(module.isNamed() && !module.getDescriptor().isAutomatic(), "Explicit named module: " + module);
            check(module.getLayer() == ModuleLayer.boot(), "Ordinary boot module layer: " + module);
        }
        check(provider.getName().equals("io.vidocq.vauban.core"), "The native Vauban initializer was selected");
        check(BEAN.getClassLoader() == ClassLoader.getSystemClassLoader(),
                "The external library uses the ordinary application class loader");
        check(LIBRARY.getDescriptor().requires().stream()
                        .noneMatch(requirement -> requirement.name().startsWith("io.vidocq.")),
                "The library descriptor must not depend on any implementation module");
        check(LIBRARY.getDescriptor().provides().isEmpty(), "The library provides no container service");
        check(Main.class.getModule().getDescriptor().requires().stream()
                        .noneMatch(requirement -> requirement.name().startsWith("io.vidocq.vauban.")),
                "The application bootstrap has no Vauban module dependency");
        for (String pkg : List.of(PACKAGE, OTHER_PACKAGE)) {
            check(LIBRARY.isOpen(pkg, CDI) == !scenario.equals("external-closed"),
                    "Expected library opening to the CDI API: " + pkg);
        }
        assertNoRuntimeGrants();
        assertOrdinaryLaunch();
    }

    private void authorized() {
        initializer.allowAccessFor(FT);
        assertNoRuntimeGrants();
        try (var container = initializer.initialize()) {
            assertContainerAccess(true);
            assertFtAccess(true, true);
            assertConfigurationClosed();
            invokeFallbacks(container);
            invokeOtherFallback(container);
        }
        assertFtAccess(true, true);
    }

    private void unauthorized() {
        try (var container = initializer.initialize()) {
            assertContainerAccess(true);
            assertFtAccess(false, false);
            invokeFallbacks(container);
        } catch (RuntimeException failure) {
            if (hasAccessFailure(failure, FT, PACKAGE, false)
                    || hasAccessFailure(failure, FT, OTHER_PACKAGE, false)) {
                assertContainerAccess(true);
                assertFtAccess(false, false);
                System.out.println("Expected FT IllegalAccessException without application authorization");
                return;
            }
            throw new AssertionError("Failure was unrelated to FT access refusal", failure);
        }
        throw new AssertionError("FT unexpectedly succeeded without application authorization");
    }

    private void closed() {
        initializer.allowAccessFor(FT);
        assertNoRuntimeGrants();
        try (var container = initializer.initialize()) {
            invokeFallbacks(container);
        } catch (RuntimeException failure) {
            if (hasAccessFailure(failure, provider, PACKAGE, true)
                    || hasAccessFailure(failure, provider, OTHER_PACKAGE, true)) {
                assertNoRuntimeGrants();
                System.out.println("Expected CDI access refusal without the library opening");
                return;
            }
            throw new AssertionError("Failure was unrelated to the closed library package", failure);
        }
        throw new AssertionError("CDI unexpectedly accessed the closed library package");
    }

    private void scoped() {
        // Two deployments: prove the authorized package works, then validate the excluded package.
        assertNoRuntimeGrants();
        try (var container = initializer.initialize()) {
            assertContainerAccess(false);
            assertFtAccess(true, false);
            assertConfigurationClosed();
            invokeFallbacks(container);
        }
        var withOtherPackage = SeContainerInitializer.newInstance()
                .allowAccessFor(FT, LIBRARY, PACKAGE)
                .disableDiscovery()
                .addBeanClasses(BEANS);
        assertContainerAccess(false);
        assertFtAccess(true, false);
        try (var container = withOtherPackage.initialize()) {
            throw new AssertionError("FT unexpectedly validated the unauthorized package");
        } catch (RuntimeException failure) {
            if (hasAccessFailure(failure, FT, OTHER_PACKAGE, false)) {
                assertContainerAccess(true);
                assertFtAccess(true, false);
                System.out.println("Expected FT IllegalAccessException outside the authorized package");
                return;
            }
            throw new AssertionError("Failure was unrelated to the scoped FT access refusal", failure);
        }
    }

    private void assertNoRuntimeGrants() {
        for (String pkg : List.of(PACKAGE, OTHER_PACKAGE)) {
            check(!LIBRARY.isOpen(pkg, provider), "Configuration has not opened the package to CDI: " + pkg);
            check(!LIBRARY.isOpen(pkg, FT), "Configuration has not granted FT access: " + pkg);
        }
        check(!LIBRARY.canRead(provider), "Configuration has not added a provider read");
    }

    private void assertContainerAccess(boolean otherPackage) {
        for (String pkg : List.of(PACKAGE, OTHER_PACKAGE)) {
            check(LIBRARY.isOpen(pkg, provider) == (pkg.equals(PACKAGE) || otherPackage),
                    "CDI startup relayed the registered library package: " + pkg);
            check(!LIBRARY.isOpen(pkg, INTERCEPTOR), "Being an interceptor grants no access: " + pkg);
        }
        check(LIBRARY.canRead(provider), "CDI startup prepared the generated-code read");
    }

    private static void assertFtAccess(boolean original, boolean other) {
        check(LIBRARY.isOpen(PACKAGE, FT) == original, "Expected FT access for the original package");
        check(LIBRARY.isOpen(OTHER_PACKAGE, FT) == other, "Expected FT access for the second package");
    }

    private void assertConfigurationClosed() {
        try {
            initializer.allowAccessFor(FT);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Access authorization must be configured before initialize()");
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
        System.out.println("External library: runtime proxy, private injection and both FT fallbacks work");
    }

    private static void invokeOtherFallback(SeContainer container) {
        var service = container.select(OtherService.class).get();
        check(service.getClass().getModule() == LIBRARY, "The second package proxy belongs to the library");
        check("other fallback for ada:external:1".equals(service.call("ada")),
                "The second package fallback sees its injected contextual state");
    }

    private static boolean hasAccessFailure(Throwable failure, Module recipient, String pkg,
                                            boolean reflective) {
        return hasAccessFailure(failure, recipient, pkg, reflective,
                Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
    }

    private static boolean hasAccessFailure(Throwable failure, Module recipient, String pkg,
                                            boolean reflective, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) {
            return false;
        }
        String message = String.valueOf(failure.getMessage());
        if ((failure instanceof IllegalAccessException
                || reflective && failure instanceof InaccessibleObjectException)
                && message.contains(recipient.getName())
                && message.contains(LIBRARY.getName())
                && message.contains(pkg)) {
            return true;
        }
        if (hasAccessFailure(failure.getCause(), recipient, pkg, reflective, seen)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (hasAccessFailure(suppressed, recipient, pkg, reflective, seen)) {
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
