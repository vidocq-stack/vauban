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

import io.vidocq.vauban.api.VaubanComponentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ClientProxies}: a synthetic bean has no class for the processor to see, so the extension that registers one
 * of a normal scope and an interface type names the interface, and the processor generates its client proxy at build
 * time, as it does for a producer of that interface (BUG-20261113-19).
 */
@DisplayName("@ClientProxies - build-time client proxies for the interfaces of synthetic beans")
class ClientProxiesDeclarationTest {

    private static final String LEDGER = """
            package lib;
            public interface Ledger {
                String name();
                default String label() { return "ledger " + name(); }
            }
            """;

    private static final String MAIN_LEDGER = """
            package lib;
            public class MainLedger implements Ledger {
                public String name() { return "main"; }
                public String label() { return "the main ledger"; }
            }
            """;

    private static final String LEDGER_EXTENSION = """
            package app;
            @io.vidocq.vauban.api.ClientProxies(lib.Ledger.class)
            public class LedgerExtension {
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the module's provider creates the proxy of a declared interface, which forwards every method")
    void declaredInterfaceGetsItsProxy() throws Exception {
        var result = ProcessorCompiler.compile(tempDir, Map.of("lib/Ledger.java", LEDGER,
                "lib/MainLedger.java", MAIN_LEDGER, "app/LedgerExtension.java", LEDGER_EXTENSION));
        assertTrue(result.success(), "compilation should succeed: " + result.messages());

        try (var loader = new URLClassLoader(new URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            var provider = (VaubanComponentProvider)
                    loader.loadClass("app._VaubanComponents").getDeclaredConstructor().newInstance();
            assertTrue(provider.coverage().clientProxies().contains("lib.Ledger_ClientProxy"),
                    "the container must know the proxy is supplied: " + provider.coverage());

            var ledgerType = loader.loadClass("lib.Ledger");
            Object mainLedger = loader.loadClass("lib.MainLedger").getDeclaredConstructor().newInstance();
            Supplier<Object> contextualInstance = () -> mainLedger;
            var proxy = provider.createClientProxy("lib.Ledger_ClientProxy", contextualInstance);
            assertNotNull(proxy, "the provider must create the declared proxy");
            assertTrue(ledgerType.isInstance(proxy), proxy.getClass().getName());
            assertFalse(java.lang.reflect.Proxy.isProxyClass(proxy.getClass()), "a generated class, not reflect.Proxy");
            assertEquals("main", ledgerType.getMethod("name").invoke(proxy));
            assertEquals("the main ledger", ledgerType.getMethod("label").invoke(proxy),
                    "a default method goes to the contextual instance too");
        }
    }

    @Test
    @DisplayName("a producer of the same interface in the same package shares one proxy")
    void producerOfTheSameInterfaceSharesTheProxy() throws Exception {
        var result = ProcessorCompiler.compile(tempDir, Map.of("lib/Ledger.java", LEDGER,
                "lib/MainLedger.java", MAIN_LEDGER, "app/LedgerExtension.java", LEDGER_EXTENSION,
                "app/Ledgers.java", """
                        package app;
                        @jakarta.enterprise.context.ApplicationScoped
                        public class Ledgers {
                            @jakarta.enterprise.inject.Produces @jakarta.enterprise.context.ApplicationScoped
                            public lib.Ledger ledger() { return new lib.MainLedger(); }
                        }
                        """));
        assertTrue(result.success(), "compilation should succeed: " + result.messages());

        try (var files = Files.walk(result.generated())) {
            assertEquals(1, files.filter(file -> file.getFileName().toString().startsWith("Ledger$$")).count(),
                    "one proxy for the interface in the package");
        }
    }

    @Test
    @DisplayName("a class listed is refused: its synthetic bean needs no interface proxy")
    void classIsRefused() throws Exception {
        var result = ProcessorCompiler.compile(tempDir, Map.of("lib/MainLedger.java", """
                        package lib;
                        public class MainLedger {
                            public String name() { return "main"; }
                        }
                        """,
                "app/LedgerExtension.java", """
                        package app;
                        @io.vidocq.vauban.api.ClientProxies(lib.MainLedger.class)
                        public class LedgerExtension {
                        }
                        """));
        assertFalse(result.success(), "a class listed must fail the compilation: " + result.messages());
        assertTrue(result.mentions("@ClientProxies lists lib.MainLedger, which is not an interface"),
                String.valueOf(result.messages()));
    }

    @Test
    @DisplayName("an interface that is not public is refused: its proxy could not implement it")
    void packagePrivateInterfaceIsRefused() throws Exception {
        var result = ProcessorCompiler.compile(tempDir, Map.of("app/Journal.java", """
                        package app;
                        interface Journal {
                            String name();
                        }
                        """,
                "app/LedgerExtension.java", """
                        package app;
                        @io.vidocq.vauban.api.ClientProxies(Journal.class)
                        public class LedgerExtension {
                        }
                        """));
        assertFalse(result.success(), "a package-private interface must fail the compilation: " + result.messages());
        assertTrue(result.mentions("@ClientProxies lists app.Journal, which is not public"),
                String.valueOf(result.messages()));
    }
}
