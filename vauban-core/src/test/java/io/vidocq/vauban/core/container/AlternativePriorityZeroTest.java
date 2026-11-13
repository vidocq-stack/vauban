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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.extensions.FrozenExtensions;
import io.vidocq.vauban.core.extensions.SyntheticBeanDescriptor;
import io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.inject.Inject;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An alternative is selected when it declares a priority, whatever the value: {@code @Priority(0)} is a priority
 * (CDI 4.1 §5.1.1, "an alternative is selected ... if the bean has a priority"), not the absence of one
 * (BUG-20261113-14).
 */
@DisplayName("An alternative at priority 0 is selected")
class AlternativePriorityZeroTest {

    public interface Greeting {
        String hello();
    }

    @Dependent
    public static class Plain implements Greeting {
        @Override
        public String hello() {
            return "plain";
        }
    }

    @Alternative
    @Priority(0)
    @Dependent
    public static class AtZero implements Greeting {
        @Override
        public String hello() {
            return "zero";
        }
    }

    /** No priority: an alternative that is not selected, so {@link Plain} stays the bean. */
    @Alternative
    @Dependent
    public static class Unselected implements Greeting {
        @Override
        public String hello() {
            return "unselected";
        }
    }

    @Dependent
    public static class Client {
        @Inject
        Greeting greeting;
    }

    @Test
    @DisplayName("a lookup returns the alternative declared at priority 0")
    void aLookupReturnsTheAlternative() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Plain.class).addBeanClass(AtZero.class).build()) {
            assertEquals("zero", container.select(Greeting.class).hello());
        }
    }

    @Test
    @DisplayName("an injection point gets the alternative declared at priority 0")
    void anInjectionPointGetsTheAlternative() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Plain.class).addBeanClass(AtZero.class).addBeanClass(Client.class).build()) {
            assertEquals("zero", container.select(Client.class).greeting.hello());
        }
    }

    @Test
    @DisplayName("an alternative without a priority is still not selected")
    void anAlternativeWithoutAPriorityIsNotSelected() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Plain.class).addBeanClass(Unselected.class).addBeanClass(Client.class).build()) {
            assertEquals("plain", container.select(Greeting.class).hello());
            assertEquals("plain", container.select(Client.class).greeting.hello());
        }
    }

    // ---- through a stereotype, and on a producer ----

    @Stereotype
    @Alternative
    @Priority(0)
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface FirstMock {
    }

    @FirstMock
    @Dependent
    public static class Mocked implements Greeting {
        @Override
        public String hello() {
            return "mocked";
        }
    }

    @Dependent
    public static class Producing {
        @Produces
        @Alternative
        @Priority(0)
        Greeting produced() {
            return () -> "produced";
        }
    }

    @Test
    @DisplayName("an alternative stereotype declaring priority 0 selects its beans")
    void aStereotypeAtPriorityZeroSelectsItsBeans() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Plain.class).addBeanClass(FirstMock.class).addBeanClass(Mocked.class)
                .addBeanClass(Client.class).build()) {
            assertEquals("mocked", container.select(Greeting.class).hello());
            assertEquals("mocked", container.select(Client.class).greeting.hello());
        }
    }

    @Test
    @DisplayName("a producer declared an alternative at priority 0 is selected")
    void aProducerAtPriorityZeroIsSelected() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(Plain.class).addBeanClass(Producing.class).addBeanClass(Client.class).build()) {
            assertEquals("produced", container.select(Greeting.class).hello());
            assertEquals("produced", container.select(Client.class).greeting.hello());
        }
    }

    // ---- a synthetic bean, run at start and frozen at build ----

    public static class SyntheticGreeting implements SyntheticBeanCreator<Greeting> {
        @Override
        public Greeting create(Instance<Object> lookup, Parameters params) {
            return () -> "synthetic";
        }
    }

    public static class AddsAnAlternativeAtZero implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components) {
            components.addBean(Greeting.class).type(Greeting.class).alternative(true).priority(0)
                    .createWith(SyntheticGreeting.class);
        }
    }

    public static class AddsAnAlternativeWithoutPriority implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components) {
            components.addBean(Greeting.class).type(Greeting.class).alternative(true)
                    .createWith(SyntheticGreeting.class);
        }
    }

    @TempDir
    Path output;

    private VaubanContainerBuilder deployment(Class<?> extension) {
        try {
            return VaubanContainer.builder()
                    .classLoader(new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader()))
                    .addBeanClass(Plain.class).addBeanClass(Client.class).addBeanClass(extension);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a synthetic alternative given priority 0 is selected, run at start and frozen at build")
    void aSyntheticAlternativeAtPriorityZeroIsSelected() {
        try (VaubanContainer container = deployment(AddsAnAlternativeAtZero.class).build()) {
            assertEquals("synthetic", container.select(Greeting.class).hello());
            assertEquals("synthetic", container.select(Client.class).greeting.hello());
        }
        FrozenExtensions.Outcome outcome = deployment(AddsAnAlternativeAtZero.class).freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());
        try (VaubanContainer container = deployment(AddsAnAlternativeAtZero.class).build()) {
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals("synthetic", container.select(Greeting.class).hello());
            assertEquals("synthetic", container.select(Client.class).greeting.hello());
        }
    }

    @Test
    @DisplayName("a synthetic alternative given no priority is not selected, run at start and frozen at build")
    void aSyntheticAlternativeWithoutPriorityIsNotSelected() {
        try (VaubanContainer container = deployment(AddsAnAlternativeWithoutPriority.class).build()) {
            assertEquals("plain", container.select(Client.class).greeting.hello());
        }
        FrozenExtensions.Outcome outcome = deployment(AddsAnAlternativeWithoutPriority.class)
                .freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());
        try (VaubanContainer container = deployment(AddsAnAlternativeWithoutPriority.class).build()) {
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals("plain", container.select(Client.class).greeting.hello());
        }
    }

    // ---- a priority an extension adds or removes ----

    /** Selects {@link Unselected}, as a portable extension would with an annotation instance. */
    public static class SelectsWithAnAnnotation implements BuildCompatibleExtension {
        @Enhancement(types = Unselected.class)
        public void select(ClassConfig unselected) {
            unselected.addAnnotation(new DeclaredPriorityTest.PriorityLiteral(0));
        }
    }

    /** Selects {@link Unselected} through the language model. */
    public static class SelectsWithAnAnnotationInfo implements BuildCompatibleExtension {
        @Enhancement(types = Unselected.class)
        public void select(ClassConfig unselected) {
            unselected.addAnnotation(AnnotationBuilder.of(Priority.class).value(10).build());
        }
    }

    /** Takes {@link AtZero} out of the selection by removing its priority. */
    public static class Unselects implements BuildCompatibleExtension {
        @Enhancement(types = AtZero.class)
        public void unselect(ClassConfig atZero) {
            atZero.removeAnnotation(annotation -> Priority.class.getName().equals(annotation.name()));
        }
    }

    @Test
    @DisplayName("an alternative an extension gives a priority is selected, whichever addAnnotation it uses")
    void anAlternativeGivenAPriorityByAnExtensionIsSelected() {
        for (Class<?> extension : List.of(SelectsWithAnAnnotation.class, SelectsWithAnAnnotationInfo.class)) {
            try (VaubanContainer container = VaubanContainer.builder().addBeanClass(extension)
                    .addBeanClass(Plain.class).addBeanClass(Unselected.class).addBeanClass(Client.class).build()) {
                assertEquals("unselected", container.select(Greeting.class).hello(), extension.getSimpleName());
                assertEquals("unselected", container.select(Client.class).greeting.hello(), extension.getSimpleName());
            }
        }
    }

    @Test
    @DisplayName("an alternative whose priority an extension removes is not selected")
    void anAlternativeWhosePriorityAnExtensionRemovesIsNotSelected() {
        try (VaubanContainer container = VaubanContainer.builder().addBeanClass(Unselects.class)
                .addBeanClass(Plain.class).addBeanClass(AtZero.class).addBeanClass(Client.class).build()) {
            assertEquals("plain", container.select(Greeting.class).hello());
            assertEquals("plain", container.select(Client.class).greeting.hello());
        }
    }

    /** Removes a {@code @Priority} {@link Mocked} does not carry: its stereotype gives it one. */
    public static class RemovesFromTheClassOnly implements BuildCompatibleExtension {
        @Enhancement(types = Mocked.class)
        public void remove(ClassConfig mocked) {
            mocked.removeAnnotation(annotation -> Priority.class.getName().equals(annotation.name()));
        }
    }

    @Test
    @DisplayName("removing @Priority from a class leaves the priority its stereotype gives")
    void removingThePriorityOfTheClassLeavesTheOneOfItsStereotype() {
        try (VaubanContainer container = VaubanContainer.builder().addBeanClass(RemovesFromTheClassOnly.class)
                .addBeanClass(Plain.class).addBeanClass(FirstMock.class).addBeanClass(Mocked.class)
                .addBeanClass(Client.class).build()) {
            assertEquals("mocked", container.select(Greeting.class).hello());
            assertEquals("mocked", container.select(Client.class).greeting.hello());
        }
    }

    @Test
    @DisplayName("metadata of an earlier version, without priorityDeclared, reads a priority of 0 as none")
    void metadataOfAnEarlierVersionReadsZeroAsNone() {
        var props = new Properties();
        props.setProperty("bean.count", "2");
        props.setProperty("bean.0.beanClass", Greeting.class.getName());
        props.setProperty("bean.0.alternative", "true");
        props.setProperty("bean.0.priority", "0");
        props.setProperty("bean.1.beanClass", Greeting.class.getName());
        props.setProperty("bean.1.alternative", "true");
        props.setProperty("bean.1.priority", "10");

        List<SyntheticBeanDescriptor> beans = SyntheticMetadataSerializer.readBeans(props);

        assertFalse(beans.get(0).priorityDeclared());
        assertTrue(beans.get(1).priorityDeclared());
    }
}
