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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.RuntimeGeneration.Generated;
import io.vidocq.vauban.core.container.RuntimeGeneration.Kind;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import io.vidocq.vauban.core.extensions.SyntheticClientProxies;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The freeze generates the client proxy of a synthetic bean of a normal scope (vauban#342)")
class FrozenSyntheticClientProxyTest {

    @TempDir
    Path output;

    /** What a test reads of a bean whose class another loader defines. */
    public interface Named {
        String name();
    }

    // ---- a bean class of another archive: this test's loader stands for a library jar ----

    public static class LibraryService implements Named {
        @Override
        public String name() {
            return "library";
        }
    }

    public static class LibraryServiceCreator implements SyntheticBeanCreator<LibraryService> {
        @Override
        public LibraryService create(Instance<Object> lookup, Parameters params) {
            return new LibraryService();
        }
    }

    public static class LibraryExtension implements BuildCompatibleExtension {
        @Synthesis
        public void service(SyntheticComponents components) {
            components.addBean(LibraryService.class).type(LibraryService.class).scope(ApplicationScoped.class)
                    .createWith(LibraryServiceCreator.class);
        }
    }

    /**
     * A class of another archive whose proxy forwards through public members only, with no no-arg constructor, as
     * langchain4j-cdi's {@code McpCdi41InvokerProvider}.
     */
    public static class PublicProvider implements Named {
        private final String[] keys;

        public PublicProvider(String[] keys) {
            this.keys = keys;
        }

        @Override
        public String name() {
            return "public " + keys.length;
        }
    }

    public static class PublicProviderCreator implements SyntheticBeanCreator<PublicProvider> {
        @Override
        public PublicProvider create(Instance<Object> lookup, Parameters params) {
            return new PublicProvider(new String[] {"a", "b"});
        }
    }

    public static class PublicProviderExtension implements BuildCompatibleExtension {
        @Synthesis
        public void provider(SyntheticComponents components) {
            components.addBean(PublicProvider.class).type(PublicProvider.class).scope(ApplicationScoped.class)
                    .createWith(PublicProviderCreator.class);
        }
    }

    /** A class of another archive whose proxy must forward a method of its package: only its package can hold it. */
    public static class PackageService implements Named {
        @Override
        public String name() {
            return "package";
        }

        String inPackage() {
            return "p";
        }
    }

    public static class PackageServiceCreator implements SyntheticBeanCreator<PackageService> {
        @Override
        public PackageService create(Instance<Object> lookup, Parameters params) {
            return new PackageService();
        }
    }

    public static class PackageServiceExtension implements BuildCompatibleExtension {
        @Synthesis
        public void service(SyntheticComponents components) {
            components.addBean(PackageService.class).type(PackageService.class).scope(ApplicationScoped.class)
                    .createWith(PackageServiceCreator.class);
        }
    }

    // ---- a bean class the build writes: these three are copied under the output and loaded from there ----

    public static class DirectService implements Named {
        @Override
        public String name() {
            return "direct";
        }
    }

    public static class DirectServiceCreator implements SyntheticBeanCreator<DirectService> {
        @Override
        public DirectService create(Instance<Object> lookup, Parameters params) {
            return new DirectService();
        }
    }

    public static class DirectExtension implements BuildCompatibleExtension {
        @Synthesis
        public void service(SyntheticComponents components) {
            components.addBean(DirectService.class).type(DirectService.class).scope(ApplicationScoped.class)
                    .createWith(DirectServiceCreator.class);
        }
    }

    // ---- a bean reached through an interface ----

    public interface Ledger {
        String entry();

        default String twice() {
            return entry() + entry();
        }
    }

    public interface Audited {
        String auditor();
    }

    public static class LedgerCreator implements SyntheticBeanCreator<Ledger> {
        @Override
        public Ledger create(Instance<Object> lookup, Parameters params) {
            class Book implements Ledger, Audited {
                @Override
                public String entry() {
                    return "e";
                }

                @Override
                public String auditor() {
                    return "a";
                }
            }
            return new Book();
        }
    }

    public static class LedgerExtension implements BuildCompatibleExtension {
        @Synthesis
        public void ledger(SyntheticComponents components) {
            components.addBean(Ledger.class).type(Ledger.class).type(Audited.class).scope(ApplicationScoped.class)
                    .createWith(LedgerCreator.class);
        }
    }

    private static final String DIRECT = FrozenSyntheticClientProxyTest.class.getName() + "$Direct";

    /** Defines the {@code Direct*} classes itself, from the output, as the loader of an application would. */
    private static final class OutputFirst extends URLClassLoader {
        OutputFirst(Path output) throws IOException {
            super(new URL[] {output.toUri().toURL()}, FrozenSyntheticClientProxyTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith(DIRECT)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
                return super.loadClass(name, resolve);
            }
        }
    }

    private void copyDirectClasses() throws IOException {
        for (Class<?> type : List.of(DirectService.class, DirectServiceCreator.class, DirectExtension.class)) {
            String path = type.getName().replace('.', '/') + ".class";
            Path target = output.resolve(path);
            Files.createDirectories(target.getParent());
            try (InputStream in = type.getClassLoader().getResourceAsStream(path)) {
                Files.copy(in, target);
            }
        }
    }

    private static String proxyPath(Class<?> beanClass) {
        return RuntimeClientProxyGenerator.proxyClassName(beanClass).replace('.', '/') + ".class";
    }

    @Test
    @DisplayName("a bean class of another archive: the proxy is placed, and its class listed for the layer")
    void theProxyOfAClassOfAnotherArchiveIsPlaced() throws IOException {
        Path manifest = output.resolve(SyntheticClientProxies.PLACEMENT_MANIFEST);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "# written by another build step\ncom.acme.Produced\n");

        FrozenExtensions.Outcome outcome = VaubanContainer.builder().addBeanClass(LibraryExtension.class)
                .freezeExtensions(output);

        assertTrue(outcome.frozen(), outcome.reason());
        Path placed = output.resolve(SyntheticClientProxies.PLACED_PREFIX + proxyPath(LibraryService.class));
        assertArrayEquals(RuntimeClientProxyGenerator.generate(LibraryService.class).bytecode(),
                Files.readAllBytes(placed), "the class the start would have generated");
        assertFalse(Files.exists(output.resolve(proxyPath(LibraryService.class))),
                "not in the application's output: the package belongs to the other archive");
        assertEquals(List.of("# written by another build step", "com.acme.Produced", LibraryService.class.getName()),
                Files.readAllLines(manifest, StandardCharsets.UTF_8));
    }

    private void providerPackages() throws IOException {
        Files.createDirectories(output.resolve("zoo/app"));
        Files.createDirectories(output.resolve("acme/app"));
        Files.write(output.resolve("zoo/app/_VaubanComponents.class"), new byte[0]);
        Files.write(output.resolve("acme/app/_VaubanComponents.class"), new byte[0]);
    }

    @Test
    @DisplayName("a class of another archive with public members only: the proxy goes next to a component provider, "
            + "and the start uses it whatever loader defines the class (BUG-20261119-70)")
    void aPublicClassOfAnotherArchiveHasItsProxyNextToAComponentProvider() throws Exception {
        providerPackages();
        try (var loader = new OutputFirst(output)) {
            assertTrue(VaubanContainer.builder().classLoader(loader).addBeanClass(PublicProviderExtension.class)
                    .freezeExtensions(output).frozen());
        }
        String proxyName = io.vidocq.vauban.core.proxy.ClientProxyShape.producerProxyName("acme.app",
                PublicProvider.class.getName());
        assertTrue(Files.isRegularFile(output.resolve(proxyName.replace('.', '/') + ".class")), proxyName);
        assertFalse(Files.exists(output.resolve(SyntheticClientProxies.PLACED_PREFIX + proxyPath(PublicProvider.class))),
                "nothing to place: no layer needs to define a class in the package of the library");
        assertFalse(Files.exists(output.resolve(SyntheticClientProxies.PLACEMENT_MANIFEST)));

        try (var loader = new OutputFirst(output);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader)
                        .addBeanClass(PublicProviderExtension.class).build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertEquals(List.of(), container.runtimeGeneration().recordedAtBoot());

            PublicProvider provider = container.select(PublicProvider.class);
            assertEquals(proxyName, provider.getClass().getName());
            assertEquals("public 2", provider.name());
            assertEquals(java.util.Set.of(), container.runtimeGeneration().announced(), "nothing generated later");
        }
    }

    @Test
    @DisplayName("a class of another archive whose proxy forwards a package method: the proxy is still placed")
    void aClassWithAPackageMethodKeepsItsPlacedProxy() throws IOException {
        providerPackages();
        try (var loader = new OutputFirst(output)) {
            assertTrue(VaubanContainer.builder().classLoader(loader).addBeanClass(PackageServiceExtension.class)
                    .freezeExtensions(output).frozen());
        }
        assertTrue(Files.isRegularFile(
                output.resolve(SyntheticClientProxies.PLACED_PREFIX + proxyPath(PackageService.class))));
        assertEquals(List.of(PackageService.class.getName()),
                Files.readAllLines(output.resolve(SyntheticClientProxies.PLACEMENT_MANIFEST), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a bean class the build writes: the proxy is next to it, and the start generates nothing")
    void theProxyOfAClassTheBuildWritesIsNextToIt() throws Exception {
        copyDirectClasses();
        try (var loader = new OutputFirst(output)) {
            FrozenExtensions.Outcome outcome = VaubanContainer.builder().classLoader(loader)
                    .addBeanClass(loader.loadClass(DirectExtension.class.getName())).freezeExtensions(output);
            assertTrue(outcome.frozen(), outcome.reason());
        }
        assertTrue(Files.isRegularFile(output.resolve(proxyPath(DirectService.class))));
        assertFalse(Files.exists(output.resolve(SyntheticClientProxies.PLACEMENT_MANIFEST)));

        try (var loader = new OutputFirst(output);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader)
                        .addBeanClass(loader.loadClass(DirectExtension.class.getName())).build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertEquals(List.of(), container.runtimeGeneration().recordedAtBoot());

            Class<?> service = loader.loadClass(DirectService.class.getName());
            Named proxy = (Named) container.select(service);
            assertEquals("direct", proxy.name());
            assertEquals(RuntimeClientProxyGenerator.proxyClassName(service), proxy.getClass().getName());
            assertEquals(java.util.Set.of(), container.runtimeGeneration().announced(), "nothing generated later");
        }
    }

    @Test
    @DisplayName("an interface: the proxy goes to a package that has a component provider, and the start uses it")
    void theProxyOfAnInterfaceLivesNextToAComponentProvider() throws Exception {
        Files.createDirectories(output.resolve("zoo/app"));
        Files.createDirectories(output.resolve("acme/app"));
        Files.write(output.resolve("zoo/app/_VaubanComponents.class"), new byte[0]);
        Files.write(output.resolve("acme/app/_VaubanComponents.class"), new byte[0]);

        try (var loader = new OutputFirst(output)) {
            assertTrue(VaubanContainer.builder().classLoader(loader).addBeanClass(LedgerExtension.class)
                    .freezeExtensions(output).frozen());
        }
        String proxyName = io.vidocq.vauban.core.proxy.ClientProxyShape.producerProxyName("acme.app",
                Ledger.class.getName());
        assertTrue(Files.isRegularFile(output.resolve(proxyName.replace('.', '/') + ".class")), proxyName);

        try (var loader = new OutputFirst(output);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader)
                        .addBeanClass(LedgerExtension.class).build()) {
            assertTrue(container.extensionsAtStart().frozen(), container.extensionsAtStart().note());
            assertEquals(List.of(), container.runtimeGeneration().recordedAtBoot());

            Ledger ledger = container.select(Ledger.class);
            assertEquals(proxyName, ledger.getClass().getName());
            assertEquals("e", ledger.entry());
            assertEquals("ee", ledger.twice(), "a default method goes to the bean");
            assertEquals("a", ((Audited) ledger).auditor(), "the other interface among the bean types");
            assertEquals(java.util.Set.of(), container.runtimeGeneration().announced());
        }
    }

    @Test
    @DisplayName("an interface, and no component provider in the output: no proxy, the start builds its own")
    void anInterfaceWithoutAComponentProviderKeepsTheStartProxy() throws Exception {
        try (var loader = new OutputFirst(output)) {
            assertTrue(VaubanContainer.builder().classLoader(loader).addBeanClass(LedgerExtension.class)
                    .freezeExtensions(output).frozen());
        }
        try (var files = Files.walk(output)) {
            assertEquals(List.of(), files.filter(file -> file.toString().endsWith("_ClientProxy.class")).toList());
        }
        try (var loader = new OutputFirst(output);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader)
                        .addBeanClass(LedgerExtension.class).build()) {
            assertEquals(List.of(new Generated(Kind.SYNTHETIC_INTERFACE_PROXY, Ledger.class.getName())),
                    container.runtimeGeneration().recordedAtBoot());
            assertEquals("e", container.select(Ledger.class).entry());
        }
    }

    @Test
    @DisplayName("without a freeze, the boot lists the class proxy of a synthetic bean as such")
    void withoutAFreezeTheBootListsTheClassProxyOfASyntheticBean() throws Exception {
        copyDirectClasses();
        try (var loader = new OutputFirst(output);
                VaubanContainer container = VaubanContainer.builder().classLoader(loader)
                        .addBeanClass(loader.loadClass(DirectExtension.class.getName())).build()) {
            assertEquals(List.of(new Generated(Kind.SYNTHETIC_CLIENT_PROXY,
                            DirectService.class.getName() + "_ClientProxy")),
                    container.runtimeGeneration().recordedAtBoot());
        }
    }

    @Test
    @DisplayName("the warning of a synthetic class proxy names the freeze, not the annotation processor")
    void theWarningNamesTheFreeze() {
        var warnings = new ArrayList<String>();
        var generation = new RuntimeGeneration(warnings::add);
        generation.generated(Kind.SYNTHETIC_CLIENT_PROXY, "lib.Provider_ClientProxy");
        generation.bootCompleted();

        String warning = warnings.getFirst();
        assertTrue(warning.contains("  - client proxy of a synthetic bean lib.Provider_ClientProxy"), warning);
        assertTrue(warning.contains("the build that freezes the"), warning);
        assertFalse(warning.contains("add vauban-processor to the annotation processor path"),
                "the class is often in a jar of another project: " + warning);
    }
}
