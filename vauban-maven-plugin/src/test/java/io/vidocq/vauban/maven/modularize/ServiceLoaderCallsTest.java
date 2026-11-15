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
package io.vidocq.vauban.maven.modularize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library that loads, through {@code ServiceLoader}, a type its callers give it -- Vaadin's {@code Lookup} loads the
 * application's route classes -- cannot declare that {@code uses}: the type is in a module that depends on it
 * (Vidocq/vidocq#598, Vauban BUG-20261115-09). An explicit descriptor alone makes every such lookup fail with
 * "does not declare `uses`"; the patched jar declares the service at the call site instead.
 */
@DisplayName("modularize: a ServiceLoader lookup of a type the caller passes works in the explicit module")
class ServiceLoaderCallsTest {

    @TempDir
    Path tmp;

    private static final String LOOKUP = """
            package com.acme.lookup;

            import java.util.ServiceLoader;

            public final class Lookup {
                private Lookup() {}

                /** Like Vaadin's Lookup: the service type is whatever the caller asks for. */
                public static Object find(Class<?> type) {
                    return ServiceLoader.load(type).findFirst().orElse(null);
                }

                public static Object findIn(Class<?> type, ClassLoader loader) {
                    return ServiceLoader.load(type, loader).findFirst().orElse(null);
                }

                public static Object findInstalled(Class<?> type) {
                    return ServiceLoader.loadInstalled(type).findFirst().orElse(null);
                }

                /** A branch that merges two types of the library: the class keeps a valid stack map. */
                public static Object findOrDefault(Class<?> type, boolean first) {
                    Object found = ServiceLoader.load(type).findFirst().orElse(null);
                    Base fallback = first ? new First() : new Second();
                    return found != null ? found : fallback.getClass().getSimpleName();
                }

                /** A method reference names ServiceLoader.load through the same constant. */
                public static Object findByReference(Class<?> type) {
                    java.util.function.Function<Class<?>, ServiceLoader<?>> load = ServiceLoader::load;
                    return load.apply(type).findFirst().orElse(null);
                }

                static class Base {}
                static final class First extends Base {}
                static final class Second extends Base {}
            }
            """;

    /** The application, a module that requires the library and opens its package, as a Vaadin route's must be. */
    private static final Map<String, String> APP = Map.of(
            "module-info", "module app { requires acme.lookup; opens app; }",
            "app.Route", """
                    package app;

                    import java.util.function.Supplier;

                    public final class Route implements Supplier<String> {
                        @Override
                        public String get() {
                            return "find=" + com.acme.lookup.Lookup.find(Route.class)
                                    + " findIn=" + com.acme.lookup.Lookup.findIn(Route.class, Route.class.getClassLoader())
                                    + " findInstalled=" + com.acme.lookup.Lookup.findInstalled(Route.class)
                                    + " default=" + com.acme.lookup.Lookup.findOrDefault(Route.class, false)
                                    + " reference=" + com.acme.lookup.Lookup.findByReference(Route.class);
                        }
                    }
                    """);

    @Test
    @DisplayName("the lookup of an application type answers instead of failing on the missing uses")
    void aLookupOfACallerTypeWorksInThePatchedModule() throws Exception {
        Path classes = tmp.resolve("lookup-classes");
        TestJars.compileClass(classes, "com.acme.lookup.Lookup", LOOKUP);
        Path jar = TestJars.jar(tmp.resolve("m2/acme-lookup-1.0.jar"), classes, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-lookup"), buildDir,
                new Modularizer.Options(Modularizer.Mode.DERIVED, Set.of(), Set.of(), Map.of(), false, false),
                line -> { });

        Path patched = ModularizedJars.root(buildDir).resolve("acme-lookup-1.0.jar");
        assertEquals(List.of(patched), result.patched());
        assertFalse(ModuleFinder.of(patched).findAll().iterator().next().descriptor().isAutomatic());

        assertEquals("find=null findIn=null findInstalled=null default=Second reference=null", runRoute(patched));
    }

    /** Compiles the application against the patched jar, then calls its route in a layer of both modules. */
    private String runRoute(Path patched) throws Exception {
        Path appClasses = tmp.resolve("app-classes");
        TestJars.compileModule(appClasses, APP, patched);
        ModuleFinder finder = ModuleFinder.of(patched, appClasses);
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), Set.of("app"));
        ModuleLayer layer = ModuleLayer.boot()
                .defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        Class<?> route = layer.findLoader("app").loadClass("app.Route");
        @SuppressWarnings("unchecked")
        Supplier<String> instance = (Supplier<String>) route.getConstructor().newInstance();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(layer.findLoader("app"));
        try {
            String answer = instance.get();
            assertTrue(answer.startsWith("find="), answer);
            return answer;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
