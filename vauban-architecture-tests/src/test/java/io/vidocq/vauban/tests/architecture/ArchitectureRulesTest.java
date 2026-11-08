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
package io.vidocq.vauban.tests.architecture;

import static io.vidocq.vauban.tests.architecture.VaubanClasses.SHIPPED;
import static io.vidocq.vauban.tests.architecture.VaubanClasses.SHIPPED_WITHOUT_THREAD_LOCAL_HOLDERS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architecture rules on the classes Vauban ships: vauban-api, vauban-classloader-spi, vauban-weaver,
 * vauban-classloader, vauban-indexer, vauban-core, vauban-processor, vauban-sjar, vauban-webcontexts and
 * vauban-naming, test classes left out (Vidocq/vidocq#289). A failure names each class and the dependency or
 * call that breaks the rule.
 */
class ArchitectureRulesTest {

    @Test
    void theShippedClassesAreFound() {
        // Guards the other tests: a class path change that imports nothing would make every rule pass.
        assertTrue(SHIPPED.contain("io.vidocq.vauban.api.VaubanComponentProvider"), "vauban-api imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.classloader.spi.PluginContext"), "vauban-classloader-spi imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.weaver.ProxyLinkWeaver"), "vauban-weaver imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.classloader.Launch"), "vauban-classloader imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.core.container.VaubanContainer"), "vauban-core imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.processor.VaubanProcessor"), "vauban-processor imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.sjar.SjarKeyProvider"), "vauban-sjar imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.webcontexts.SessionContext"), "vauban-webcontexts imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.naming.BeanDeclarations"), "vauban-naming imported");
        assertTrue(SHIPPED.contain("io.vidocq.vauban.indexer.model.ClassInfo"), "vauban-indexer imported");
    }

    static Stream<Named<ArchRule>> rules() {
        return Stream.of(
                named("API_DEPENDENCIES", ArchitectureRules.API_DEPENDENCIES),
                named("SPI_DEPENDENCIES", ArchitectureRules.SPI_DEPENDENCIES),
                named("WEAVER_DEPENDENCIES", ArchitectureRules.WEAVER_DEPENDENCIES),
                named("CLASSLOADER_DEPENDENCIES", ArchitectureRules.CLASSLOADER_DEPENDENCIES),
                named("INDEXER_DEPENDENCIES", ArchitectureRules.INDEXER_DEPENDENCIES),
                named("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES),
                named("PROCESSOR_DEPENDENCIES", ArchitectureRules.PROCESSOR_DEPENDENCIES),
                named("SJAR_DEPENDENCIES", ArchitectureRules.SJAR_DEPENDENCIES),
                named("WEBCONTEXTS_DEPENDENCIES", ArchitectureRules.WEBCONTEXTS_DEPENDENCIES),
                named("NAMING_DEPENDENCIES", ArchitectureRules.NAMING_DEPENDENCIES),
                named("FOUNDATIONAL_BRICKS", ArchitectureRules.FOUNDATIONAL_BRICKS),
                named("API_NEVER_DEPENDS_ON_CORE", ArchitectureRules.API_NEVER_DEPENDS_ON_CORE),
                named("LAYERS", ArchitectureRules.LAYERS),
                named("NO_REFLECTION", ArchitectureRules.NO_REFLECTION),
                named("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theShippedClassesKeepTheRule(ArchRule rule) {
        rule.check(SHIPPED);
    }

    @Test
    void theShippedClassesKeepNoThreadLocalOutsideTheDocumentedHolders() {
        ArchitectureRules.NO_THREAD_LOCAL.check(SHIPPED_WITHOUT_THREAD_LOCAL_HOLDERS);
    }
}
