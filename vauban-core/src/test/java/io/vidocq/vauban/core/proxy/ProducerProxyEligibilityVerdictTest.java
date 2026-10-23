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
package io.vidocq.vauban.core.proxy;

import io.vidocq.vauban.api.ProxyLink;
import io.vidocq.vauban.core.proxy.ProducerProxyEligibility.Reason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The verdict {@link ProducerProxyEligibility} gives a produced type: the constructor the proxy chains to, then
 * each method the proxy would forward.
 */
@SuppressWarnings({"unused", "java:S100", "java:S1186", "java:S2094"})
class ProducerProxyEligibilityVerdictTest {

    public static class PrivateConstructorOnly {
        private PrivateConstructorOnly() {
        }
    }

    public static class PackageConstructor {
        PackageConstructor() {
        }
    }

    public static class ProtectedConstructor {
        protected ProtectedConstructor() {
        }
    }

    public static class ProxyLinkConstructor {
        private ProxyLinkConstructor() {
        }

        public ProxyLinkConstructor(String first, String second) {
        }

        protected ProxyLinkConstructor(ProxyLink link) {
        }
    }

    public abstract static class AbstractBase {
        public abstract void work();
    }

    public static class ImplementsAbstract extends AbstractBase {
        @Override
        public void work() {
        }
    }

    public static class ProtectedMethod {
        protected void work() {
        }
    }

    public static class PackagePrivateMethod {
        void work() {
        }
    }

    public static class FinalMethod {
        public final void work() {
        }
    }

    public static class NeverForwarded implements Comparable<NeverForwarded> {
        static void helper() {
        }

        private void secret() {
        }

        public void $$generated() {
        }

        @Override
        @SuppressWarnings({"deprecation", "removal", "java:S1113"})
        protected void finalize() {
        }

        @Override
        @SuppressWarnings("java:S2975")
        protected Object clone() {
            return this;
        }

        @Override
        public int compareTo(NeverForwarded other) {
            return 0;
        }
    }

    public static class FinalizeWithParameter {
        protected void finalize(int round) {
        }
    }

    public static class CloneWithParameter {
        protected Object clone(int depth) {
            return this;
        }
    }

    static Stream<Arguments> verdicts() {
        return Stream.of(
                Arguments.of(PrivateConstructorOnly.class, Reason.NO_ACCESSIBLE_CTOR),
                Arguments.of(PackageConstructor.class, Reason.NO_ACCESSIBLE_CTOR),
                Arguments.of(ProtectedConstructor.class, Reason.ELIGIBLE),
                Arguments.of(ProxyLinkConstructor.class, Reason.ELIGIBLE),
                Arguments.of(ImplementsAbstract.class, Reason.ELIGIBLE),
                Arguments.of(ProtectedMethod.class, Reason.PROTECTED_VIRTUALS),
                Arguments.of(PackagePrivateMethod.class, Reason.PACKAGE_PRIVATE_VIRTUALS),
                Arguments.of(FinalMethod.class, Reason.FINAL_VIRTUALS),
                Arguments.of(NeverForwarded.class, Reason.ELIGIBLE),
                Arguments.of(FinalizeWithParameter.class, Reason.PROTECTED_VIRTUALS),
                Arguments.of(CloneWithParameter.class, Reason.PROTECTED_VIRTUALS));
    }

    @ParameterizedTest
    @MethodSource("verdicts")
    void aProducedTypeIsProxyableOnlyWithAReachableConstructorAndPublicForwardedMethods(Class<?> type,
            Reason expected) {
        assertEquals(expected, ProducerProxyEligibility.of(type));
    }
}
