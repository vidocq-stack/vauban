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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DefinitionException;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link BceProcessor} tolerates and what it does not: members whose signature names an absent type are left
 * out; an exception a {@code @Discovery} method throws is a definition error (BUG-20261118-04), and so is one an
 * extension throws in the enhancement of the archives, its constructor included (BUG-20261118-05,
 * BUG-20261118-57).
 */
class BceProcessorFailureToleranceTest {

    private static final DotName TARGET = DotName.of("com.acme.Target");

    public static class FailingConstructor implements BuildCompatibleExtension {
        public FailingConstructor() {
            throw new IllegalStateException("cannot be built");
        }
    }

    public static class FailingDiscovery implements BuildCompatibleExtension {
        @Discovery
        public void discover(ScannedClasses scanned) {
            scanned.add("com.acme.NeverAdded");
            throw new IllegalStateException("discovery failed");
        }
    }

    /** Declares a {@code @Discovery} parameter of a type the phase does not support, and records each call. */
    public static class UnsupportedDiscoveryParameter implements BuildCompatibleExtension {

        static final List<String> CALLED = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Discovery
        public void discover(Types types) {
            CALLED.add("discover with " + types);
        }
    }

    /** Reports an error through the {@code Messages} of {@code @Discovery}. */
    public static class ReportingDiscovery implements BuildCompatibleExtension {
        @Discovery
        public void discover(Messages messages) {
            messages.error("nothing to discover");
        }
    }

    private static IndexLookup lookupWithTarget() {
        return new IndexLookup(new IndexBuilder()
                .add(new ClassInfo(TARGET, DotName.of("java.lang.Object"), List.of(), 0x0001, List.of(), List.of(),
                        List.of(), ClassInfo.ClassKind.CLASS))
                .build());
    }

    @Test
    void anExtensionThatCannotBeBuiltFailsEveryPhase() {
        var lookup = lookupWithTarget();
        var loader = getClass().getClassLoader();
        var extensions = List.<Class<?>>of(FailingConstructor.class);
        var built = "of " + FailingConstructor.class.getName()
                + " failed: java.lang.IllegalStateException: cannot be built";

        var archiveClasses = List.<Class<?>>of(Object.class);
        var index = lookup.index();
        var pairs = List.<Map.Entry<Class<?>, Class<?>>>of(Map.entry(FailingConstructor.class, Object.class));

        var discovery = BceProcessor.processDiscovery(extensions, lookup);
        var enhancement = assertThrows(DefinitionException.class,
                () -> BceProcessor.processEnhancementOnly(extensions, archiveClasses, index, loader));
        var replay = assertThrows(DefinitionException.class,
                () -> BceProcessor.replayEnhancementForTargets(pairs, index));

        assertAll(() -> assertTrue(discovery.bceInstances().isEmpty()),
                () -> assertEquals(List.of("@Discovery " + built), discovery.definitionErrors()),
                () -> assertTrue(enhancement.getMessage().contains("@Enhancement " + built), enhancement.getMessage()),
                () -> assertTrue(replay.getMessage().contains("@Enhancement " + built), replay.getMessage()),
                () -> assertEquals("cannot be built", replay.getCause().getMessage()));
    }

    @Test
    void aDiscoveryMethodThatThrowsIsADefinitionError() {
        var discovery = BceProcessor.processDiscovery(List.of(FailingDiscovery.class), lookupWithTarget());

        assertAll(() -> assertEquals(List.of(FailingDiscovery.class), List.copyOf(discovery.bceInstances().keySet())),
                () -> assertEquals(List.of("@Discovery of " + FailingDiscovery.class.getName()
                        + " failed: java.lang.IllegalStateException: discovery failed"), discovery.definitionErrors()),
                () -> assertEquals("discovery failed", discovery.definitionErrorCauses().getFirst().getMessage()));
    }

    @Test
    void aDiscoveryParameterOfAnUnsupportedTypeIsReportedBeforeTheCall() {
        UnsupportedDiscoveryParameter.CALLED.clear();

        var discovery = BceProcessor.processDiscovery(List.of(UnsupportedDiscoveryParameter.class), lookupWithTarget());

        var invalid = "@Discovery method " + UnsupportedDiscoveryParameter.class.getName()
                + ".discover has invalid parameter type: " + Types.class.getName();
        List<String> called = UnsupportedDiscoveryParameter.CALLED;
        // A deployment problem, as the Javadoc of BuildCompatibleExtension says (BUG-20261118-58)
        assertAll(() -> assertEquals(List.of(invalid), discovery.deploymentErrors()),
                () -> assertEquals(List.of(), discovery.definitionErrors()),
                () -> assertEquals(List.of(), called, "the method is not called"));
    }

    @Test
    void aDiscoveryMethodGetsMessagesAndItsErrorsAreDeploymentProblems() {
        var discovery = BceProcessor.processDiscovery(List.of(ReportingDiscovery.class), lookupWithTarget());

        assertAll(() -> assertEquals(List.of("nothing to discover"), discovery.deploymentErrors()),
                () -> assertEquals(List.of(), discovery.definitionErrors()));
    }

    @Test
    void aDiscoveryMethodThatThrowsFailsTheStart() {
        var initializer = SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(FailingDiscovery.class);

        var thrown = assertThrows(DefinitionException.class, initializer::initialize);

        assertAll(() -> assertTrue(thrown.getMessage().contains(FailingDiscovery.class.getName()), thrown.getMessage()),
                () -> assertInstanceOf(IllegalStateException.class, thrown.getCause()));
    }

    /** Throws a {@code DeploymentException} from its constructor. */
    public static class DeploymentProblemConstructor implements BuildCompatibleExtension {
        public DeploymentProblemConstructor() {
            throw new jakarta.enterprise.inject.spi.DeploymentException("not deployable");
        }
    }

    /**
     * A constructor that fails in the full cycle, with no instance of {@code @Discovery}, is a definition error whose
     * cause is what the constructor threw, not the reflective wrapper (BUG-20261118-57).
     */
    @Test
    void aConstructorThatFailsInTheFullCycleIsADefinitionErrorWithItsOwnCause() {
        var lookup = lookupWithTarget();

        var result = BceProcessor.process(List.of(FailingConstructor.class), List.of(), lookup.index(),
                getClass().getClassLoader(), Map.of(), List.of());

        assertAll(() -> assertEquals(List.of(), result.deploymentErrors()),
                () -> assertEquals(1, result.definitionErrors().size(), () -> result.definitionErrors().toString()),
                () -> assertTrue(result.definitionErrors().getFirst().contains("cannot be built"),
                        result.definitionErrors().getFirst()),
                () -> assertInstanceOf(IllegalStateException.class, result.definitionErrorCauses().getFirst()));
    }

    /**
     * A constructor that throws a {@code DeploymentException} in the full cycle stays a deployment problem, as a phase
     * method's does (BUG-20261118-57).
     */
    @Test
    void aConstructorThatThrowsADeploymentExceptionStaysADeploymentProblem() {
        var lookup = lookupWithTarget();

        var result = BceProcessor.process(List.of(DeploymentProblemConstructor.class), List.of(), lookup.index(),
                getClass().getClassLoader(), Map.of(), List.of());

        assertAll(() -> assertEquals(List.of(), result.definitionErrors()),
                () -> assertEquals(1, result.deploymentErrors().size(), () -> result.deploymentErrors().toString()),
                () -> assertInstanceOf(jakarta.enterprise.inject.spi.DeploymentException.class,
                        result.deploymentErrorCauses().getFirst()));
    }

    /** An {@code @Enhancement} method with a parameter of an unsupported type and without its one target. */
    public static class UnsupportedTypeAndNoTarget implements BuildCompatibleExtension {
        @jakarta.enterprise.inject.build.compatible.spi.Enhancement(types = Object.class)
        public void enhance(jakarta.enterprise.inject.build.compatible.spi.BeanInfo bean) {
            throw new IllegalStateException("called with " + bean);
        }
    }

    /**
     * A method with both faults reports both: the unsupported type as a deployment problem, the missing target as a
     * definition error, where the first hid the second (BUG-20261118-58).
     */
    @Test
    void anUnsupportedTypeDoesNotHideAMissingTarget() {
        var lookup = lookupWithTarget();
        var prefix = "@Enhancement method " + UnsupportedTypeAndNoTarget.class.getName() + ".enhance ";

        var result = BceProcessor.process(List.of(UnsupportedTypeAndNoTarget.class), List.of(), lookup.index(),
                getClass().getClassLoader(), Map.of(), List.of());

        assertAll(() -> assertEquals(List.of(prefix + "has invalid parameter type: "
                        + jakarta.enterprise.inject.build.compatible.spi.BeanInfo.class.getName()),
                        result.deploymentErrors()),
                () -> assertEquals(1, result.definitionErrors().size(), () -> result.definitionErrors().toString()),
                () -> assertTrue(result.definitionErrors().getFirst().startsWith(prefix + "must have exactly one"),
                        result.definitionErrors().getFirst()));
    }

    @Test
    void aClassGivenAScopeIsNotPromotedWhenItCannotBeAManagedBean() {
        var lookup = lookupWithTarget();
        var config = new VaubanClassConfig(new io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo(
                lookup.requireClass(TARGET), lookup));
        config.addAnnotation(ApplicationScoped.class);
        List<BeanDescriptor> none = List.of();

        var beans = BceProcessor.beansAfterEnhancement(none, Map.of(TARGET, List.of(config)), name -> null);

        assertTrue(beans.isEmpty());
    }

    @Test
    void methodsOfAClassWhoseSignaturesNameAnAbsentTypeAreLeftOut() {
        var absent = ClassDesc.of("com.acme.Absent");
        byte[] bytes = ClassFile.of().build(ClassDesc.of("gen.bce.NamesAbsent"), clb -> clb
                .withMethodBody("take", MethodTypeDesc.of(ConstantDescs.CD_void, absent),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, CodeBuilder::return_));
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("gen.bce.NamesAbsent", bytes, 0, bytes.length);
            }
        };

        assertEquals(0, BceProcessor.getDeclaredMethodsSafe(loader.define()).length);
    }
}
