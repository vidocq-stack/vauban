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

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parameters the Javadoc of each extension annotation lists are given, never {@code null}: {@code Messages} and
 * {@code Types} in {@code @Enhancement}, on each path that runs it, and {@code Messages} in {@code @Synthesis}. The
 * errors an extension reports through {@code Messages} fail the deployment (BUG-20261118-23).
 */
@DisplayName("BUG-20261118-23 - every phase gives the Messages and Types its Javadoc lists")
class ExtensionPhaseArgumentsTest {

    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final String SEEN = "seen java.lang.Object";

    /** Reports, through {@code Messages}, the class {@code Types} gives for the class it enhances. */
    public static class ReportingEnhancement implements BuildCompatibleExtension {
        @Enhancement(types = Object.class, withSubtypes = false)
        public void enhance(jakarta.enterprise.lang.model.declarations.ClassInfo clazz, Messages messages,
                            Types types) {
            messages.error("seen " + types.ofClass(clazz).declaration().name());
        }
    }

    /** Reports an error through the {@code Messages} of {@code @Synthesis}. */
    public static class ReportingSynthesis implements BuildCompatibleExtension {
        static final String ERROR = "nothing to synthesize";

        @Synthesis
        public void synthesize(SyntheticComponents components, Messages messages) {
            messages.error(ERROR);
        }
    }

    private static IndexLookup lookupWithObject() {
        return new IndexLookup(new IndexBuilder()
                .add(new ClassInfo(OBJECT, null, List.of(), 0x0001, List.of(), List.of(), List.of(),
                        ClassInfo.ClassKind.CLASS))
                .build());
    }

    @Test
    @DisplayName("the enhancement of an unprocessed archive gives Messages and Types")
    void theEnhancementOfAnUnprocessedArchiveGivesMessagesAndTypes() {
        var extensions = List.<Class<?>>of(ReportingEnhancement.class);
        var archiveClasses = List.<Class<?>>of(Object.class);
        var index = lookupWithObject().index();
        var loader = getClass().getClassLoader();

        var thrown = assertThrows(DeploymentException.class,
                () -> BceProcessor.processEnhancementOnly(extensions, archiveClasses, index, loader));

        assertTrue(thrown.getMessage().contains(SEEN), thrown.getMessage());
    }

    @Test
    @DisplayName("the replay for a pre-processed jar gives Messages and Types")
    void theReplayForAPreProcessedJarGivesMessagesAndTypes() {
        var pairs = List.<Map.Entry<Class<?>, Class<?>>>of(Map.entry(ReportingEnhancement.class, Object.class));
        var index = lookupWithObject().index();

        var thrown = assertThrows(DeploymentException.class,
                () -> BceProcessor.replayEnhancementForTargets(pairs, index));

        assertTrue(thrown.getMessage().contains(SEEN), thrown.getMessage());
    }

    @Test
    @DisplayName("@Synthesis gives Messages, and its errors fail the start")
    void synthesisGivesMessagesAndItsErrorsFailTheStart() {
        var initializer = SeContainerInitializer.newInstance().disableDiscovery()
                .addBeanClasses(ReportingSynthesis.class);

        var thrown = assertThrows(DeploymentException.class, initializer::initialize);

        assertTrue(thrown.getMessage().contains(ReportingSynthesis.ERROR), thrown.getMessage());
    }
}
