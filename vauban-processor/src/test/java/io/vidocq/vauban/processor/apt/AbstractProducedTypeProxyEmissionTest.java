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
package io.vidocq.vauban.processor.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BUG-20261114-02: a normal-scoped producer of an abstract class gets its client proxy at build time, in the
 * producer's package, as any fully-public class does. CDI 4.1 §3.10 does not count an abstract class as unproxyable;
 * the proxy forwards its abstract methods to the contextual instance. The processor used to report it as needing a
 * proxy inside its own package and ship nothing, leaving the run time to fail on it.
 */
class AbstractProducedTypeProxyEmissionTest {

    private static ProcessorCompiler.Result result;

    @BeforeAll
    static void compile(@TempDir Path workDir) throws IOException {
        var sources = new LinkedHashMap<String, String>();
        sources.put("app/meter/Meter.java", """
                package app.meter;
                public abstract class Meter implements java.util.function.Supplier<String> {
                    protected Meter() {}
                    public abstract int reading();
                    public String unit() { return "kWh"; }
                }
                """);
        sources.put("app/meter/FixedMeter.java", """
                package app.meter;
                public final class FixedMeter extends Meter {
                    @Override public int reading() { return 42; }
                    @Override public String get() { return "meter"; }
                    @Override public String unit() { return "MWh"; }
                }
                """);
        sources.put("app/Producers.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Producers {
                    @jakarta.enterprise.inject.Produces @jakarta.enterprise.context.ApplicationScoped
                    java.time.Clock clock() { return java.time.Clock.systemUTC(); }
                    @jakarta.enterprise.inject.Produces @jakarta.enterprise.context.ApplicationScoped
                    app.meter.Meter meter() { return new app.meter.FixedMeter(); }
                }
                """);
        result = ProcessorCompiler.compile(workDir, sources);
    }

    /** The binary name of the one producer proxy of {@code producedSimpleName} generated in package {@code app}. */
    private static String producerProxy(String producedSimpleName) throws IOException {
        try (Stream<Path> files = Files.list(result.generated().resolve("app"))) {
            var proxies = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith(producedSimpleName + "$$") && name.endsWith("_ClientProxy.java"))
                    .toList();
            assertEquals(1, proxies.size(), proxies + " in " + result.messages());
            return "app." + proxies.getFirst().replace(".java", "");
        }
    }

    private static Object newProxy(String binaryName, Supplier<?> delegate) throws Exception {
        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                AbstractProducedTypeProxyEmissionTest.class.getClassLoader())) {
            return loader.loadClass(binaryName).getConstructor(Supplier.class).newInstance(delegate);
        }
    }

    @Test
    void theModuleCompiles() {
        assertTrue(result.success(), result.messages().toString());
    }

    @Test
    void aJdkAbstractClassGetsItsProxyInTheProducerPackage() throws Exception {
        var proxy = producerProxy("Clock");
        var fixed = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

        var clock = (Clock) newProxy(proxy, () -> fixed);

        assertEquals(Instant.EPOCH, clock.instant());
        assertEquals(ZoneOffset.UTC, clock.getZone());
        assertTrue(result.mentions("Generated build-time producer proxy " + proxy + " for java.time.Clock"),
                result.messages().toString());
    }

    @Test
    void anAbstractClassOfAnotherPackageGetsItsProxyInTheProducerPackage() throws Exception {
        var proxy = producerProxy("Meter");
        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            var instance = loader.loadClass("app.meter.FixedMeter").getConstructor().newInstance();
            var meter = loader.loadClass(proxy).getConstructor(Supplier.class)
                    .newInstance((Supplier<?>) () -> instance);
            var type = loader.loadClass("app.meter.Meter");

            assertEquals(42, type.getMethod("reading").invoke(meter), "an abstract method of the class");
            assertEquals("meter", type.getMethod("get").invoke(meter),
                    "an abstract method of an interface the class does not implement");
            assertEquals("MWh", type.getMethod("unit").invoke(meter), "a concrete method");
        }
    }

    @Test
    void noAbstractClassIsReportedAsNeedingItsOwnPackage() {
        assertFalse(result.mentions("needs its client proxy inside"), result.messages().toString());
        assertFalse(result.messages().stream().anyMatch(message -> message.contains("ABSTRACT")),
                result.messages().toString());
    }

    @Test
    void theProviderCreatesBothProxiesUnderTheirProducedTypeKeys() throws IOException {
        var provider = Files.readString(result.generated().resolve("app/_VaubanComponents.java"));

        for (var key : List.of("java.time.Clock_ClientProxy", "app.meter.Meter_ClientProxy")) {
            assertTrue(provider.contains('"' + key + '"'), key + " in " + provider);
        }
    }
}
