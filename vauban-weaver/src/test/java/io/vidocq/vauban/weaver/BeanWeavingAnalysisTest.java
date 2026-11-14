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
package io.vidocq.vauban.weaver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The load-time weaving gate shared by the agent tier and the Vauban class loader: a bean class needs the
 * {@code (ProxyLink)} marker when it lacks one and its client proxy cannot do without it (BUG-20261114-01: a class
 * recompiled without Vauban, as an IDE does, under the client proxy a woven build shipped for it).
 */
@DisplayName("BeanWeavingAnalysis.needsMarker — what the client proxy shipped for a bean expects")
class BeanWeavingAnalysisTest {

    private static final String BEAN = "com.example.weave.BookResource";
    private static final String PROXY = BEAN + "_ClientProxy";

    /** Serves the given classes and records every name asked for. */
    private static final class RecordingResolver implements BeanWeavingAnalysis.ByteResolver {
        private final Map<String, byte[]> classes;
        private final List<String> asked = new ArrayList<>();

        RecordingResolver(Map<String, byte[]> classes) {
            this.classes = classes;
        }

        @Override
        public byte[] bytesOf(String binaryName) {
            asked.add(binaryName);
            return classes.get(binaryName);
        }
    }

    @Test
    @DisplayName("a no-arg class replaced under the proxy a woven build shipped for it needs the marker")
    void classReplacedUnderItsWovenProxyNeedsTheMarker() {
        var wovenProxy = ProxyLinkWeaver.retargetProxyConstructor(Fixtures.proxyChainingNoArgCtor(PROXY, BEAN));
        var resolver = new RecordingResolver(Map.of(PROXY, wovenProxy));

        assertTrue(BeanWeavingAnalysis.needsMarker(BEAN, Fixtures.beanWithNoArgCtor(BEAN), resolver),
                "its client proxy chains to <init>(ProxyLink): without the marker, its first instance throws"
                        + " NoSuchMethodError");
        assertEquals(List.of(PROXY), resolver.asked, "the proxy is found next to the bean, by its name");
    }

    @Test
    @DisplayName("a no-arg class whose proxy chains its no-arg constructor needs nothing")
    void proxyChainingTheNoArgConstructorNeedsNothing() {
        var resolver = new RecordingResolver(Map.of(PROXY, Fixtures.proxyChainingNoArgCtor(PROXY, BEAN)));

        assertFalse(BeanWeavingAnalysis.needsMarker(BEAN, Fixtures.beanWithNoArgCtor(BEAN), resolver));
    }

    @Test
    @DisplayName("a no-arg class without a client proxy needs nothing")
    void noArgClassWithoutProxyNeedsNothing() {
        assertFalse(BeanWeavingAnalysis.needsMarker(BEAN, Fixtures.beanWithNoArgCtor(BEAN),
                new RecordingResolver(Map.of())));
    }

    @Test
    @DisplayName("a proxy chaining the marker of another class does not count")
    void markerOfAnotherClassDoesNotCount() {
        var other = "com.example.weave.Other";
        var proxyOfOther = ProxyLinkWeaver.retargetProxyConstructor(Fixtures.proxyChainingNoArgCtor(PROXY, other));

        assertFalse(BeanWeavingAnalysis.needsMarker(BEAN, Fixtures.beanWithNoArgCtor(BEAN),
                new RecordingResolver(Map.of(PROXY, proxyOfOther))));
    }

    @Test
    @DisplayName("a class built with the marker needs nothing and reads nothing more")
    void wovenClassReadsNothingMore() {
        var woven = ProxyLinkWeaver.addMarkerConstructor(Fixtures.beanWithNoArgCtor(BEAN),
                ProxyLinkWeaver.SuperChain.NO_ARG);
        var resolver = new RecordingResolver(Map.of());

        assertFalse(BeanWeavingAnalysis.needsMarker(BEAN, woven, resolver));
        assertEquals(List.of(), resolver.asked, "the normal path must not read the proxy");
    }

    @Test
    @DisplayName("a class without a no-arg constructor needs the marker without reading its proxy")
    void classWithoutNoArgNeedsTheMarkerWithoutReadingItsProxy() {
        var resolver = new RecordingResolver(Map.of());

        assertTrue(BeanWeavingAnalysis.needsMarker(BEAN, Fixtures.beanWithBusinessCtorOnly(BEAN), resolver));
        assertEquals(List.of(), resolver.asked);
    }
}
