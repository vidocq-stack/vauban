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
import static io.vidocq.vauban.tests.architecture.VaubanClasses.SHIPPED_AND_FIXTURES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;
import io.vidocq.vauban.api.archfixture.ApiReachingIntoCore;
import io.vidocq.vauban.classloader.ArchFixtureClassloaderReachingIntoCore;
import io.vidocq.vauban.classloader.spi.archfixture.SpiReachingIntoApi;
import io.vidocq.vauban.core.archfixture.CoreReachingIntoAnotherBrick;
import io.vidocq.vauban.core.archfixture.CoreReachingIntoTheProcessor;
import io.vidocq.vauban.indexer.archfixture.IndexerReachingIntoCore;
import io.vidocq.vauban.naming.archfixture.NamingReachingIntoCore;
import io.vidocq.vauban.processor.archfixture.ProcessorReachingIntoSjar;
import io.vidocq.vauban.sjar.archfixture.SjarReachingIntoCore;
import io.vidocq.vauban.weaver.archfixture.WeaverReachingIntoTheSpi;
import io.vidocq.vauban.webcontexts.archfixture.WebcontextsReachingIntoApi;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each architecture rule catches what it forbids: a rule that passes on the shipped classes proves nothing unless
 * it fails on a class that breaks it. A violation must name the fixture, so that a rule failing for another
 * reason (an empty layer, a class the rule does not select) does not count.
 */
class ArchitectureRulesSelfTest {

    /** The module and layer rules, each with a fixture of an {@code archfixture} package that breaks it. */
    static Stream<Arguments> moduleRuleBreaches() {
        return Stream.of(
                ruleWith("API_DEPENDENCIES", ArchitectureRules.API_DEPENDENCIES, ApiReachingIntoCore.class),
                ruleWith("API_NEVER_DEPENDS_ON_CORE", ArchitectureRules.API_NEVER_DEPENDS_ON_CORE, ApiReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, ApiReachingIntoCore.class),
                ruleWith("SPI_DEPENDENCIES", ArchitectureRules.SPI_DEPENDENCIES, SpiReachingIntoApi.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, SpiReachingIntoApi.class),
                ruleWith("WEAVER_DEPENDENCIES", ArchitectureRules.WEAVER_DEPENDENCIES, WeaverReachingIntoTheSpi.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, WeaverReachingIntoTheSpi.class),
                ruleWith(
                        "CLASSLOADER_DEPENDENCIES",
                        ArchitectureRules.CLASSLOADER_DEPENDENCIES,
                        ArchFixtureClassloaderReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, ArchFixtureClassloaderReachingIntoCore.class),
                ruleWith("INDEXER_DEPENDENCIES", ArchitectureRules.INDEXER_DEPENDENCIES, IndexerReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, IndexerReachingIntoCore.class),
                ruleWith("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES, CoreReachingIntoTheProcessor.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, CoreReachingIntoTheProcessor.class),
                ruleWith("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES, CoreReachingIntoAnotherBrick.class),
                ruleWith("FOUNDATIONAL_BRICKS", ArchitectureRules.FOUNDATIONAL_BRICKS, CoreReachingIntoAnotherBrick.class),
                ruleWith(
                        "PROCESSOR_DEPENDENCIES",
                        ArchitectureRules.PROCESSOR_DEPENDENCIES,
                        ProcessorReachingIntoSjar.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, ProcessorReachingIntoSjar.class),
                ruleWith("SJAR_DEPENDENCIES", ArchitectureRules.SJAR_DEPENDENCIES, SjarReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, SjarReachingIntoCore.class),
                ruleWith(
                        "WEBCONTEXTS_DEPENDENCIES",
                        ArchitectureRules.WEBCONTEXTS_DEPENDENCIES,
                        WebcontextsReachingIntoApi.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, WebcontextsReachingIntoApi.class),
                ruleWith("NAMING_DEPENDENCIES", ArchitectureRules.NAMING_DEPENDENCIES, NamingReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, NamingReachingIntoCore.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("moduleRuleBreaches")
    void eachModuleRuleCatchesAFixtureAmongTheShippedClasses(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, SHIPPED_AND_FIXTURES, fixture);
    }

    /** The code rules, each with a fixture below that breaks it. */
    static Stream<Arguments> codeRuleBreaches() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpAClassByName.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAMethodReflectively.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAnAnnotationReflectively.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesAFixedThreadPool.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, SchedulesOnAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, BuildsAThreadPoolExecutor.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, StartsAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, CreatesAThread.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, KeepsAThreadLocal.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("codeRuleBreaches")
    void eachCodeRuleCatchesAFixture(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, new ClassFileImporter().importClasses(fixture), fixture);
    }

    /** What the code rules let through: look-alikes of the breaches that keep the rule. */
    static Stream<Arguments> codeRuleLookAlikes() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpACharsetByName.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesVirtualThreads.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, UsesVirtualThreads.class));
    }

    @ParameterizedTest(name = "{0} keeps {1}")
    @MethodSource("codeRuleLookAlikes")
    void eachCodeRuleKeepsALookAlike(ArchRule rule, Class<?> fixture) {
        var result = rule.evaluate(new ClassFileImporter().importClasses(fixture));
        assertFalse(result.hasViolation(), () -> result.getFailureReport().toString());
    }

    @Test
    void aPlatformThreadFactoryIsTolerableOnlyWhenThePolicySaysSo() {
        // The policy of Vauban is NONE: an executor given a factory is still caught.
        ArchRule tolerant = VidocqArchRules.noPlatformThreads(PlatformThreads.UNLESS_THREAD_FACTORY_GIVEN, "test");
        var classes = new ClassFileImporter().importClasses(SchedulesOnAVirtualThread.class);
        assertFalse(tolerant.evaluate(classes).hasViolation());
        assertTrue(ArchitectureRules.NO_PLATFORM_THREADS.evaluate(classes).hasViolation());
    }

    @Test
    void theReflectionFallbacksNameShippedClasses() {
        // A stale entry would exempt a class that no longer exists, or let a new class take its name.
        for (String allowed : ArchitectureRules.REFLECTIVE_FALLBACKS.keySet()) {
            assertTrue(SHIPPED.contain(allowed), () -> allowed + " is not a shipped class");
        }
    }

    @Test
    void everyReflectionFallbackStillNeedsItsEntry() {
        // The rule without the fallbacks must fail on the class alone, or the entry hides nothing and goes.
        ArchRule strict = VidocqArchRules.noReflection(java.util.Map.of(), "test");
        for (String allowed : ArchitectureRules.REFLECTIVE_FALLBACKS.keySet()) {
            JavaClasses one = VaubanClasses.only(allowed);
            assertTrue(strict.evaluate(one).hasViolation(), () -> allowed + " uses no reflection: drop its entry");
        }
    }

    @Test
    void everyThreadLocalHolderStillKeepsAThreadLocal() {
        ArchRule strict = ArchitectureRules.NO_THREAD_LOCAL;
        for (String holder : ArchitectureRules.THREAD_LOCAL_HOLDERS.keySet()) {
            assertTrue(SHIPPED.contain(holder), () -> holder + " is not a shipped class");
            assertTrue(
                    strict.evaluate(VaubanClasses.only(holder)).hasViolation(),
                    () -> holder + " keeps no ThreadLocal: drop its entry");
        }
    }

    private static Arguments ruleWith(String name, ArchRule rule, Class<?> fixture) {
        return Arguments.of(named(name, rule), fixture);
    }

    private static void assertNamesTheFixture(ArchRule rule, JavaClasses classes, Class<?> fixture) {
        List<String> details = rule.evaluate(classes).getFailureReport().getDetails();
        assertTrue(
                details.stream().anyMatch(detail -> detail.contains(fixture.getName())),
                () -> "expected a violation by " + fixture.getName() + " of: " + rule.getDescription() + ", got "
                        + details);
    }

    static final class LooksUpAClassByName {
        Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }
    }

    static final class ReadsAMethodReflectively {
        Object method() throws NoSuchMethodException {
            return String.class.getDeclaredMethod("length");
        }
    }

    static final class ReadsAnAnnotationReflectively {
        boolean deprecated(Class<?> type) {
            return type.isAnnotationPresent(Deprecated.class);
        }
    }

    static final class LooksUpACharsetByName {
        Charset charset(String name) {
            return Charset.forName(name);
        }
    }

    static final class UsesAFixedThreadPool {
        ExecutorService pool() {
            return Executors.newFixedThreadPool(4);
        }
    }

    static final class SchedulesOnAPlatformThread {
        ScheduledExecutorService reaper() {
            return Executors.newSingleThreadScheduledExecutor();
        }
    }

    static final class BuildsAThreadPoolExecutor {
        ExecutorService pool() {
            return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }
    }

    static final class StartsAPlatformThread {
        Thread start(Runnable task) {
            return Thread.ofPlatform().start(task);
        }
    }

    static final class CreatesAThread {
        Thread create(Runnable task) {
            return new Thread(task);
        }
    }

    static final class UsesVirtualThreads {
        ExecutorService executor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    static final class SchedulesOnAVirtualThread {
        ScheduledExecutorService reaper() {
            return Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        }
    }

    static final class KeepsAThreadLocal {
        private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);

        StringBuilder buffer() {
            return BUFFER.get();
        }
    }
}
