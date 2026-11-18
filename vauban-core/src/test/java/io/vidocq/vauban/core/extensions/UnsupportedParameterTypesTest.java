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

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each of the five phases reports a parameter of a type its Javadoc does not list before the call, as a definition
 * error naming the extension, the method and the type, and never calls the method with {@code null}. The types match
 * exactly: an extension's own subtype of a listed type is not one. An error a {@code @Discovery} method reports
 * through {@code Messages} is a deployment problem, as the Javadoc of {@code Messages} says (BUG-20261118-22).
 */
@DisplayName("BUG-20261118-22 - every phase reports a parameter of an unsupported type before the call")
class UnsupportedParameterTypesTest {

    /** The extension methods called, for each test. */
    static final List<String> CALLED = new CopyOnWriteArrayList<>();

    /** A type of the extension's own, a subtype of a type {@code @Discovery} lists. */
    public interface OwnScannedClasses extends ScannedClasses {
    }

    public static class EnhancementWithBeanInfo implements BuildCompatibleExtension {
        @Enhancement(types = Object.class)
        public void enhance(ClassConfig clazz, BeanInfo bean) {
            CALLED.add("enhance " + clazz + bean);
        }
    }

    public static class RegistrationWithScannedClasses implements BuildCompatibleExtension {
        @Registration(types = Object.class)
        public void register(BeanInfo bean, ScannedClasses scanned) {
            CALLED.add("register " + bean + scanned);
        }
    }

    public static class SynthesisWithBeanInfo implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components, BeanInfo bean) {
            CALLED.add("synthesize " + components + bean);
        }
    }

    public static class ValidationWithBeanInfo implements BuildCompatibleExtension {
        @Validation
        public void validate(Messages messages, BeanInfo bean) {
            CALLED.add("validate " + messages + bean);
        }
    }

    public static class DiscoveryWithItsOwnType implements BuildCompatibleExtension {
        @Discovery
        public void discover(OwnScannedClasses scanned) {
            CALLED.add("discover " + scanned);
        }
    }

    public static class DiscoveryReportingAnError implements BuildCompatibleExtension {
        static final String ERROR = "nothing to discover";

        @Discovery
        public void discover(Messages messages) {
            messages.error(ERROR);
        }
    }

    @BeforeEach
    void forget() {
        CALLED.clear();
    }

    @Test
    void anEnhancementParameterOfAnUnsupportedTypeIsADefinitionError() {
        assertDefinitionError(EnhancementWithBeanInfo.class, "@Enhancement method", "enhance", BeanInfo.class);
    }

    @Test
    void aRegistrationParameterOfAnUnsupportedTypeIsADefinitionError() {
        assertDefinitionError(RegistrationWithScannedClasses.class, "@Registration method", "register",
                ScannedClasses.class);
    }

    @Test
    void aSynthesisParameterOfAnUnsupportedTypeIsADefinitionError() {
        assertDefinitionError(SynthesisWithBeanInfo.class, "@Synthesis method", "synthesize", BeanInfo.class);
    }

    @Test
    void aValidationParameterOfAnUnsupportedTypeIsADefinitionError() {
        assertDefinitionError(ValidationWithBeanInfo.class, "@Validation method", "validate", BeanInfo.class);
    }

    @Test
    void aSubtypeOfAListedTypeIsNotOne() {
        assertDefinitionError(DiscoveryWithItsOwnType.class, "@Discovery method", "discover",
                OwnScannedClasses.class);
    }

    @Test
    void anErrorDiscoveryReportsThroughMessagesIsADeploymentProblem() {
        var initializer = SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(DiscoveryReportingAnError.class);

        var thrown = assertThrows(DeploymentException.class, initializer::initialize);

        assertTrue(thrown.getMessage().contains(DiscoveryReportingAnError.ERROR), thrown.getMessage());
    }

    private static void assertDefinitionError(Class<?> extension, String phase, String method, Class<?> type) {
        var initializer = SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(extension);
        var expected = phase + " " + extension.getName() + "." + method + " has invalid parameter type: "
                + type.getName();

        var thrown = assertThrows(DefinitionException.class, initializer::initialize);

        List<String> called = CALLED;
        assertAll(() -> assertTrue(thrown.getMessage().contains(expected), thrown.getMessage()),
                () -> assertEquals(List.of(), called, "the method is not called"));
    }
}
