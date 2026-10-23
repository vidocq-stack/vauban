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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the processor emits for producers of each shape, for a bean with private constructors only, for fields a
 * provider can or cannot assign, for the default package, and for a package that declares a qualifier and nothing
 * else.
 */
class ProducerAndProviderScenariosTest {

    private static final String DEPENDENT = "@jakarta.enterprise.context.Dependent\n";
    private static final String APPLICATION_SCOPED = "@jakarta.enterprise.context.ApplicationScoped\n";
    private static final String PRODUCES = "@jakarta.enterprise.inject.Produces " + APPLICATION_SCOPED;

    private static ProcessorCompiler.Result result;

    @BeforeAll
    static void compile(@TempDir Path workDir) throws IOException {
        var sources = new LinkedHashMap<String, String>();
        sources.put("app/Gadget.java", """
                package app;
                public class Gadget {
                    protected Gadget(int size) {}
                    public Gadget() {}
                    public String name() { return "gadget"; }
                    protected String tag() { return "tag"; }
                    public String $$internal() { return "internal"; }
                    public static Gadget of() { return new Gadget(); }
                }
                """);
        sources.put("app/Linked.java", """
                package app;
                public class Linked {
                    protected Linked(io.vidocq.vauban.api.ProxyLink link) {}
                    public Linked(String name) {}
                    public String name() { return "linked"; }
                }
                """);
        sources.put("app/Producers.java", "package app;\n" + DEPENDENT + "public class Producers {\n"
                + PRODUCES + "Gadget gadget = new Gadget();\n"
                + PRODUCES + "Linked linked() { return new Linked(\"l\"); }\n"
                + "@jakarta.enterprise.inject.Produces @app.qualifiers.Fast Gadget fast() { return new Gadget(); }\n}\n");
        sources.put("app/Locked.java", "package app;\n" + APPLICATION_SCOPED + """
                public class Locked {
                    private Locked() {}
                    public String open() { return "open"; }
                }
                """);
        sources.put("app/Holder.java", "package app;\n" + DEPENDENT + """
                public class Holder {
                    @jakarta.inject.Inject private Gadget hidden;
                    @jakarta.inject.Inject public Gadget injected;
                    @jakarta.inject.Inject @app.qualifiers.Fast Gadget fast;
                    public Linked mutable;
                    public static Linked shared;
                    public final Linked fixed = null;
                    public int count;
                    public static class Nested { public Linked slot; }
                }
                """);
        sources.put("app/more/Extra.java", "package app.more;\n" + DEPENDENT + "public class Extra {}\n");
        sources.put("app/qualifiers/Fast.java", """
                package app.qualifiers;
                @jakarta.inject.Qualifier
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Fast {}
                """);
        sources.put("Widget.java", "public class Widget { public String name() { return \"widget\"; } }\n");
        sources.put("DefaultProducer.java", DEPENDENT + "public class DefaultProducer {\n"
                + PRODUCES + "Widget make() { return new Widget(); }\n}\n");
        result = ProcessorCompiler.compile(workDir, sources);
    }

    private static List<String> generatedSources(String packagePath, String prefix) throws IOException {
        var dir = result.generated().resolve(packagePath);
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.startsWith(prefix)).toList();
        }
    }

    private static String provider(String packagePath) throws IOException {
        return Files.readString(result.generated().resolve(packagePath).resolve("_VaubanComponents.java"));
    }

    @Test
    void theModuleCompiles() {
        assertTrue(result.success(), result.messages().toString());
    }

    @Test
    void aProducedClassWithAnEntryConstructorGetsASourceProxyInTheProducerPackage() throws IOException {
        assertEquals(1, generatedSources("app", "Linked$$").size(), result.messages().toString());
    }

    @Test
    void aProducedClassWithProtectedMethodsGetsItsProxyPlacedInItsOwnPackage() {
        assertTrue(result.mentions("Producer of app.Gadget needs its client proxy inside app (PROTECTED_VIRTUALS)"),
                result.messages().toString());
    }

    @Test
    void aProducerInTheDefaultPackageGetsItsProxyThere() throws IOException {
        var proxies = generatedSources("", "Widget$$");

        assertEquals(1, proxies.size(), result.messages().toString());
        assertTrue(proxies.getFirst().endsWith("_ClientProxy.java"), proxies.toString());
    }

    @Test
    void aBeanWithPrivateConstructorsOnlyGetsABytecodeProxy() {
        assertTrue(Files.isRegularFile(result.classes().resolve("app/Locked_ClientProxy.class")));
        assertFalse(Files.exists(result.generated().resolve("app/Locked_ClientProxy.java")));
    }

    @Test
    void aPrivateInjectedFieldIsReportedAsNeedingOpens() {
        assertTrue(result.mentions("Field injection for private field app.Holder#hidden still needs opens"),
                result.messages().toString());
    }

    @Test
    void theProviderAssignsPublicMutableReferenceFieldsOnce() throws IOException {
        var provider = provider("app");

        assertTrue(provider.contains("\"mutable\""), provider);
        assertEquals(1, provider.split("\"injected\"", -1).length - 1, provider);
        assertFalse(provider.contains("\"shared\"") || provider.contains("\"fixed\"") || provider.contains("\"count\""),
                provider);
    }

    @Test
    void aPackageDeclaringOnlyAQualifierGetsAProviderForIt() throws IOException {
        assertTrue(provider("app/qualifiers").contains("app.qualifiers.Fast"), result.messages().toString());
    }
}
