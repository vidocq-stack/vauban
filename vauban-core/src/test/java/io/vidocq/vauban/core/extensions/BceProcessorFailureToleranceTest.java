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
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phases of {@link BceProcessor} that the CDI Lite specification makes non-fatal: an extension that cannot be
 * instantiated or whose discovery method throws is skipped, and members whose signature names an absent type
 * are left out.
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

    private static IndexLookup lookupWithTarget() {
        return new IndexLookup(new IndexBuilder()
                .add(new ClassInfo(TARGET, DotName.of("java.lang.Object"), List.of(), 0x0001, List.of(), List.of(),
                        List.of(), ClassInfo.ClassKind.CLASS))
                .build());
    }

    @Test
    void anExtensionThatCannotBeBuiltIsSkippedInEveryPhase() {
        var lookup = lookupWithTarget();
        var loader = getClass().getClassLoader();

        var discovery = BceProcessor.processDiscovery(List.of(FailingConstructor.class), lookup);
        var enhancement = BceProcessor.processEnhancementOnly(List.of(FailingConstructor.class), List.of(Object.class),
                lookup.index(), loader);
        var replay = BceProcessor.replayEnhancementForTargets(
                List.of(Map.entry(FailingConstructor.class, Object.class)), lookup.index());

        assertTrue(discovery.bceInstances().isEmpty());
        assertTrue(enhancement.isEmpty());
        assertTrue(replay.isEmpty());
    }

    @Test
    void aDiscoveryMethodThatThrowsLeavesItsExtensionInstantiated() {
        var discovery = BceProcessor.processDiscovery(List.of(FailingDiscovery.class), lookupWithTarget());

        assertEquals(List.of(FailingDiscovery.class), List.copyOf(discovery.bceInstances().keySet()));
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
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_()));
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("gen.bce.NamesAbsent", bytes, 0, bytes.length);
            }
        };

        assertEquals(0, BceProcessor.getDeclaredMethodsSafe(loader.define()).length);
    }
}
