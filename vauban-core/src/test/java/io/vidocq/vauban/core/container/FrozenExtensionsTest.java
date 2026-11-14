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

import io.vidocq.vauban.api.ExtensionPhase;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import jakarta.inject.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extensions of a deployment frozen at build (VAU-PERF-005, vauban#288): the build runs the extension cycle once
 * and writes its result, the start of the same deployment loads it instead of running the cycle. Every test boots
 * twice, with and without the record, or proves that the record is refused.
 */
@DisplayName("Extensions frozen at build")
class FrozenExtensionsTest {

    private static final String REQUIRED = "vauban.test.frozen.required";

    @TempDir
    Path output;

    @BeforeEach
    @AfterEach
    void reset() {
        StaticExtension.RUNS.set(0);
        StartCheckExtension.RUNS.set(0);
        DiscoveringExtension.RUNS.set(0);
        System.clearProperty(REQUIRED);
    }

    // ---- the deployment ----

    /** No bean defining annotation: {@link StaticExtension} gives it a scope. */
    public static class Promoted {
        public String id() {
            return "promoted";
        }
    }

    @Dependent
    public static class Plain {
        public String id() {
            return "plain";
        }
    }

    public static class Greeting {
        private final String text;

        Greeting(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public static class GreetingCreator implements SyntheticBeanCreator<Greeting> {
        @Override
        public Greeting create(Instance<Object> lookup, Parameters params) {
            return new Greeting(params.get("text", String.class));
        }
    }

    /** Work that depends on the classes of the deployment only: a scope added, a synthetic bean. */
    public static class StaticExtension implements BuildCompatibleExtension {
        static final AtomicInteger RUNS = new AtomicInteger();

        public StaticExtension() {
            RUNS.incrementAndGet();
        }

        @Enhancement(types = Promoted.class)
        public void scope(ClassConfig promoted) {
            promoted.addAnnotation(ApplicationScoped.class);
        }

        @Synthesis
        public void greeting(SyntheticComponents components) {
            components.addBean(Greeting.class).type(Greeting.class).scope(Dependent.class)
                    .withParam("text", "hello").createWith(GreetingCreator.class);
        }
    }

    /** Checks the environment the application starts in, so not while the application builds. */
    public static class StartCheckExtension implements BuildCompatibleExtension {
        static final AtomicInteger RUNS = new AtomicInteger();

        public StartCheckExtension() {
            RUNS.incrementAndGet();
        }

        @Validation
        public void check(Messages messages) {
            if (ExtensionPhase.isBuildTime()) {
                return;
            }
            if (System.getProperty(REQUIRED) == null) {
                messages.error("missing " + REQUIRED);
            }
        }
    }

    /** Takes an annotation away, which no record can say. */
    public static class RemovingExtension implements BuildCompatibleExtension {
        @Enhancement(types = Plain.class)
        public void rescope(ClassConfig plain) {
            plain.removeAnnotation(annotation -> annotation.name().equals(Dependent.class.getName()));
            plain.addAnnotation(ApplicationScoped.class);
        }
    }

    /** Reports a deployment problem. */
    public static class RefusingExtension implements BuildCompatibleExtension {
        @Validation
        public void refuse(Messages messages) {
            messages.error("not this deployment");
        }
    }

    /** A bean of the runtime that embeds the container, not of the application. */
    @Dependent
    public static class HostHolder {
        public String id() {
            return "host";
        }
    }

    // ---- what @Discovery registers ----

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD})
    public @interface Tagged {
    }

    public interface Service {
        String id();
    }

    @Dependent
    @Tagged
    public static class TaggedService implements Service {
        @Override
        public String id() {
            return "tagged";
        }
    }

    @Dependent
    public static class DefaultService implements Service {
        @Override
        public String id() {
            return "default";
        }
    }

    /** Resolves only if {@link Tagged} is a qualifier: two beans have the type otherwise. */
    @Dependent
    public static class TaggedConsumer {
        @Inject
        @Tagged
        Service service;

        public String id() {
            return service.id();
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Shared {
    }

    /** A bean because {@link Shared} is a stereotype, application scoped because the stereotype says so. */
    @Shared
    public static class SharedThing {
    }

    @Scope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Cached {
    }

    public static class CachedContext implements AlterableContext {
        private final Map<Contextual<?>, Object> instances = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return Cached.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) instances.computeIfAbsent(contextual, c -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) instances.get(contextual);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            instances.remove(contextual);
        }
    }

    @Cached
    public static class CachedThing {
        public String id() {
            return "cached";
        }
    }

    /** Only in the deployment because {@link DiscoveringExtension} adds it. */
    @Dependent
    public static class Extra {
        public String id() {
            return "extra";
        }
    }

    public static class DiscoveringExtension implements BuildCompatibleExtension {
        static final AtomicInteger RUNS = new AtomicInteger();

        public DiscoveringExtension() {
            RUNS.incrementAndGet();
        }

        @Discovery
        public void discover(MetaAnnotations meta, ScannedClasses scanned) {
            meta.addQualifier(Tagged.class);
            meta.addStereotype(Shared.class).addAnnotation(ApplicationScoped.class);
            meta.addContext(Cached.class, false, CachedContext.class);
            scanned.add(Extra.class.getName());
        }
    }

    // ---- a synthetic bean of a parameterized type ----

    public static class SupplierCreator implements SyntheticBeanCreator<Object> {
        @Override
        public Object create(Instance<Object> lookup, Parameters params) {
            return (java.util.function.Supplier<String>) () -> "supplied";
        }
    }

    /** A bean typed {@code Supplier<String>}: a type only the language model can name with its argument. */
    public static class GenericExtension implements BuildCompatibleExtension {
        @Synthesis
        public void supplier(SyntheticComponents components,
                jakarta.enterprise.inject.build.compatible.spi.Types types) {
            components.addBean(Object.class)
                    .type(types.parameterized(java.util.function.Supplier.class, String.class))
                    .scope(Dependent.class).createWith(SupplierCreator.class);
        }
    }

    @Dependent
    public static class GenericConsumer {
        @Inject
        java.util.function.Supplier<String> supplier;

        public String id() {
            return supplier.get();
        }
    }

    // ---- the tests ----

    private static final List<Class<?>> STATIC_DEPLOYMENT = List.of(Promoted.class, Plain.class, StaticExtension.class);

    private VaubanContainerBuilder deployment(List<Class<?>> classes) {
        VaubanContainerBuilder builder = VaubanContainer.builder().classLoader(outputLoader());
        classes.forEach(builder::addBeanClass);
        return builder;
    }

    /** The loader of a start: the build's output first, where the record is, then the classes of this test. */
    private ClassLoader outputLoader() {
        try {
            return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a frozen deployment starts without running its extensions, with what they did")
    void aFrozenDeploymentStartsWithoutRunningItsExtensions() {
        FrozenExtensions.Outcome outcome = deployment(STATIC_DEPLOYMENT).freezeExtensions(output);

        assertTrue(outcome.frozen(), outcome.reason());
        assertEquals(List.of(StaticExtension.class.getName()), outcome.extensions());
        assertEquals(List.of(), outcome.startExtensions());
        assertTrue(Files.isRegularFile(output.resolve(FrozenExtensions.PATH)));
        assertTrue(StaticExtension.RUNS.get() > 0, "the build runs the extension");

        StaticExtension.RUNS.set(0);
        try (VaubanContainer container = deployment(STATIC_DEPLOYMENT).build()) {
            assertEquals(0, StaticExtension.RUNS.get(), "the start must not run a frozen extension");
            assertSame(container.select(Promoted.class), container.select(Promoted.class),
                    "the scope the extension added");
            assertEquals("hello", container.select(Greeting.class).text());
            assertEquals("plain", container.select(Plain.class).id());
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals(List.of(StaticExtension.class.getName()), container.extensionsAtStart().frozenAtBuild());
            assertEquals(List.of(), container.extensionsAtStart().ranAtStart());
        }
    }

    @Test
    @DisplayName("without a record the extensions run at start, as before")
    void withoutARecordTheExtensionsRunAtStart() {
        try (VaubanContainer container = deployment(STATIC_DEPLOYMENT).build()) {
            assertTrue(StaticExtension.RUNS.get() > 0);
            assertEquals("hello", container.select(Greeting.class).text());
            assertFalse(container.extensionsAtStart().frozen());
            assertEquals(List.of(StaticExtension.class.getName()), container.extensionsAtStart().ranAtStart());
        }
    }

    @Test
    @DisplayName("an extension that asked whether it runs at build time runs again at start, and only it")
    void anExtensionThatAskedRunsAgainAtStart() {
        List<Class<?>> classes = List.of(Promoted.class, StaticExtension.class, StartCheckExtension.class);
        FrozenExtensions.Outcome outcome = deployment(classes).freezeExtensions(output);

        assertTrue(outcome.frozen(), outcome.reason());
        assertEquals(List.of(StartCheckExtension.class.getName()), outcome.startExtensions());

        StaticExtension.RUNS.set(0);
        StartCheckExtension.RUNS.set(0);
        VaubanContainerBuilder withoutTheValue = deployment(classes);
        DeploymentException failure = assertThrows(DeploymentException.class, withoutTheValue::build);
        assertTrue(failure.getMessage().contains("missing " + REQUIRED), failure.getMessage());
        assertEquals(0, StaticExtension.RUNS.get());

        System.setProperty(REQUIRED, "here");
        StartCheckExtension.RUNS.set(0);
        try (VaubanContainer container = deployment(classes).build()) {
            assertEquals(0, StaticExtension.RUNS.get(), "the static extension stays frozen");
            assertTrue(StartCheckExtension.RUNS.get() > 0, "the extension that asked runs at start");
            assertEquals("hello", container.select(Greeting.class).text());
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals(List.of(StartCheckExtension.class.getName()), container.extensionsAtStart().ranAtStart());
        }
    }

    @Test
    @DisplayName("the build can name extensions that run at start")
    void theBuildNamesExtensionsThatRunAtStart() {
        FrozenExtensions.Outcome outcome = deployment(STATIC_DEPLOYMENT)
                .freezeExtensions(output, Set.of(StaticExtension.class.getName()));

        assertTrue(outcome.frozen(), outcome.reason());
        assertEquals(List.of(StaticExtension.class.getName()), outcome.startExtensions());

        StaticExtension.RUNS.set(0);
        try (VaubanContainer container = deployment(STATIC_DEPLOYMENT).build()) {
            assertTrue(StaticExtension.RUNS.get() > 0);
            assertEquals("hello", container.select(Greeting.class).text());
            assertSame(container.select(Promoted.class), container.select(Promoted.class));
        }
    }

    @Test
    @DisplayName("a deployment that is not the one the build froze runs its extensions")
    void aDeploymentThatChangedRunsItsExtensions() {
        assertTrue(deployment(List.of(Promoted.class, StaticExtension.class)).freezeExtensions(output).frozen());

        StaticExtension.RUNS.set(0);
        try (VaubanContainer container = deployment(STATIC_DEPLOYMENT).build()) {
            assertTrue(StaticExtension.RUNS.get() > 0, "one more bean class: the record is another deployment's");
            assertFalse(container.extensionsAtStart().frozen());
            String note = container.extensionsAtStart().note();
            assertNotNull(note);
            assertTrue(note.contains("1 bean class differs from the build's: " + Plain.class.getName() + " (added)"),
                    note);
        }
    }

    @Test
    @DisplayName("the note of a refused record names the class the start no longer has")
    void aRefusedRecordNamesTheClassThatWentAway() {
        assertTrue(deployment(STATIC_DEPLOYMENT).freezeExtensions(output).frozen());

        try (VaubanContainer container = deployment(List.of(Promoted.class, StaticExtension.class)).build()) {
            String note = container.extensionsAtStart().note();
            assertFalse(container.extensionsAtStart().frozen());
            assertTrue(note.contains(Plain.class.getName() + " (removed)"), note);
            assertTrue(note.contains(FrozenExtensions.PATH), note);
        }
    }

    @Test
    @DisplayName("a bean the embedding runtime adds leaves the record valid")
    void aHostBeanLeavesTheRecordValid() {
        assertTrue(deployment(STATIC_DEPLOYMENT).freezeExtensions(output).frozen());

        StaticExtension.RUNS.set(0);
        try (VaubanContainer container = deployment(STATIC_DEPLOYMENT)
                .addHostBean(HostHolder.class, HostHolder::new).build()) {
            assertEquals(0, StaticExtension.RUNS.get());
            assertEquals("host", container.select(HostHolder.class).id());
            assertTrue(container.extensionsAtStart().frozen());
        }
    }

    @Test
    @DisplayName("a host bean the deployment also holds stays in its fingerprint, in either order (BUG-20261113-16)")
    void aHostBeanTheDeploymentAlsoHoldsKeepsTheRecordValid() {
        List<Class<?>> classes = List.of(Promoted.class, Plain.class, StaticExtension.class, HostHolder.class);
        assertTrue(deployment(classes).freezeExtensions(output).frozen());

        StaticExtension.RUNS.set(0);
        try (VaubanContainer container = deployment(classes).addHostBean(HostHolder.class, HostHolder::new).build()) {
            assertEquals(0, StaticExtension.RUNS.get(), container.extensionsAtStart().note());
            assertTrue(container.extensionsAtStart().frozen());
        }

        VaubanContainerBuilder hostFirst = VaubanContainer.builder().classLoader(outputLoader())
                .addHostBean(HostHolder.class, HostHolder::new);
        classes.forEach(hostFirst::addBeanClass);
        try (VaubanContainer container = hostFirst.build()) {
            assertEquals(0, StaticExtension.RUNS.get(), container.extensionsAtStart().note());
            assertEquals("host", container.select(HostHolder.class).id());
        }
    }

    @Test
    @DisplayName("an extension that removes annotations leaves no record, and a stale one goes")
    void anExtensionThatRemovesAnnotationsIsNotFrozen() throws Exception {
        assertTrue(deployment(STATIC_DEPLOYMENT).freezeExtensions(output).frozen());

        FrozenExtensions.Outcome outcome = deployment(List.of(Plain.class, RemovingExtension.class))
                .freezeExtensions(output);

        assertFalse(outcome.frozen());
        assertTrue(outcome.reason().contains(RemovingExtension.class.getName()), outcome.reason());
        assertTrue(outcome.reason().contains("removes"), outcome.reason());
        try (var files = Files.walk(output)) {
            assertEquals(List.of(), files.filter(Files::isRegularFile).toList(), "no record, stale or new");
        }
        try (VaubanContainer container = deployment(List.of(Plain.class, RemovingExtension.class)).build()) {
            assertFalse(container.extensionsAtStart().frozen());
            assertEquals(List.of(RemovingExtension.class.getName()), container.extensionsAtStart().ranAtStart());
        }
    }

    @Test
    @DisplayName("an extension that reports a problem at build leaves no record: the start reports it")
    void anExtensionErrorLeavesNoRecord() {
        List<Class<?>> classes = List.of(Plain.class, RefusingExtension.class);
        FrozenExtensions.Outcome outcome = deployment(classes).freezeExtensions(output);

        assertFalse(outcome.frozen());
        assertTrue(outcome.reason().contains("not this deployment"), outcome.reason());
        assertFalse(Files.exists(output.resolve(FrozenExtensions.PATH)));
        VaubanContainerBuilder start = deployment(classes);
        DeploymentException failure = assertThrows(DeploymentException.class, start::build);
        assertTrue(failure.getMessage().contains("not this deployment"), failure.getMessage());
    }

    @Test
    @DisplayName("what @Discovery registered is in the record: qualifier, stereotype, context, scanned class")
    void whatDiscoveryRegisteredIsFrozen() {
        List<Class<?>> classes = List.of(TaggedService.class, DefaultService.class, TaggedConsumer.class,
                SharedThing.class, CachedThing.class, DiscoveringExtension.class);
        FrozenExtensions.Outcome outcome = deployment(classes).freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());

        DiscoveringExtension.RUNS.set(0);
        try (VaubanContainer container = deployment(classes).build()) {
            assertEquals(0, DiscoveringExtension.RUNS.get());
            assertEquals("tagged", container.select(TaggedConsumer.class).id(), "the qualifier");
            assertSame(container.select(SharedThing.class), container.select(SharedThing.class), "the stereotype");
            assertSame(container.select(CachedThing.class), container.select(CachedThing.class), "the context");
            assertEquals("extra", container.select(Extra.class).id(), "the scanned class");
        }
    }

    @Test
    @DisplayName("a synthetic bean of a parameterized type keeps its type in the record")
    void aParameterizedSyntheticBeanTypeIsFrozen() {
        List<Class<?>> classes = List.of(GenericConsumer.class, GenericExtension.class);
        try (VaubanContainer container = deployment(classes).build()) {
            assertEquals("supplied", container.select(GenericConsumer.class).id(), "when the extension runs");
        }
        FrozenExtensions.Outcome outcome = deployment(classes).freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());

        try (VaubanContainer container = deployment(classes).build()) {
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals("supplied", container.select(GenericConsumer.class).id(), "from the record");
        }
    }

    @Test
    @DisplayName("the same deployment gives the same beans frozen and not frozen")
    void frozenAndReplayedDeploymentsHoldTheSameBeans() {
        List<Class<?>> classes = List.of(Promoted.class, Plain.class, StaticExtension.class, TaggedService.class,
                DefaultService.class, TaggedConsumer.class, SharedThing.class, CachedThing.class,
                DiscoveringExtension.class);
        List<String> replayed;
        try (VaubanContainer container = deployment(classes).build()) {
            assertFalse(container.extensionsAtStart().frozen());
            replayed = beanTypes(container);
        }
        assertTrue(deployment(classes).freezeExtensions(output).frozen());
        try (VaubanContainer container = deployment(classes).build()) {
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals(replayed, beanTypes(container));
        }
    }

    private static List<String> beanTypes(VaubanContainer container) {
        return container.getBeanManager().getBeans(Object.class, jakarta.enterprise.inject.Any.Literal.INSTANCE)
                .stream()
                .map(bean -> bean.getBeanClass().getName() + " " + bean.getScope().getSimpleName() + " "
                        + bean.getTypes().stream().map(Object::toString).sorted().toList() + " "
                        + bean.getQualifiers().stream().map(q -> q.annotationType().getSimpleName()).sorted().toList())
                .sorted().toList();
    }
}
