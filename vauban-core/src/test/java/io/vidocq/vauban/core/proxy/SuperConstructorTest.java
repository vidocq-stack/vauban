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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The super constructor a client proxy chains to (vauban#227): the {@code (ProxyLink)} one, else the non-private one
 * with the fewest parameters, ties broken on the parameter types rather than on an order no model guarantees.
 */
@SuppressWarnings({"unused", "java:S1186"})
class SuperConstructorTest {

    public static class StringFirst {
        public StringFirst(String value) {
        }

        public StringFirst(Integer value) {
        }
    }

    public static class IntegerFirst {
        public IntegerFirst(Integer value) {
        }

        public IntegerFirst(String value) {
        }
    }

    public static class FewestParameters {
        public FewestParameters(String first, String second) {
        }

        protected FewestParameters(String only) {
        }

        private FewestParameters() {
        }
    }

    public static class WithProxyLink {
        public WithProxyLink() {
        }

        protected WithProxyLink(ProxyLink link) {
        }
    }

    static Stream<Arguments> chosen() {
        return Stream.of(
                Arguments.of(StringFirst.class, List.of("java.lang.Integer")),
                Arguments.of(IntegerFirst.class, List.of("java.lang.Integer")),
                Arguments.of(FewestParameters.class, List.of("java.lang.String")),
                Arguments.of(WithProxyLink.class, List.of(ProxyLink.class.getName())));
    }

    @ParameterizedTest
    @MethodSource("chosen")
    void theProxyChainsToOneConstructorWhateverTheDeclarationOrder(Class<?> bean, List<String> expected) {
        var params = RuntimeClientProxyGenerator.shapeOf(bean).superCtorParams().stream().map(Object::toString).toList();
        assertEquals(expected, params);
    }
}
