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
package io.vidocq.vauban.moduleit;

/**
 * A private nested type of this package in an inherited member's signature (BUG-20261004-09,
 * {@code n11a}): {@link SecretLabeledBase} binds {@link ReceiverLabeled} to the private
 * {@link Secret}, so a bean extending it inherits the default {@code label(Secret)} as its member.
 * Only the body of this class may name {@code Secret} (JLS 6.6.1); a generated client proxy, another
 * top-level class of the package, may not.
 */
public class PrivateNestedHolder {

    private static class Secret {
        @Override
        public String toString() {
            return "secret";
        }
    }

    public static class SecretLabeledBase implements ReceiverLabeled<Secret> {
    }

    /**
     * A class method whose parameter type is {@link Secret}: a bean extending it inherits
     * {@code take(Secret)}, which a generated top-level class cannot declare in source
     * (BUG-20261004-09, {@code n11b}).
     */
    public static class SecretTaker {
        public String take(Secret secret) {
            return getClass().getSimpleName() + " took " + secret;
        }
    }

    /** {@code bean.label(…)} as this class writes it, with an argument only it can make. */
    public static String callLabel(SecretLabeledBase bean) {
        return bean.label(new Secret());
    }

    /** {@code taker.take(…)} as this class writes it, with an argument only it can make. */
    public static String callTake(SecretTaker taker) {
        return taker.take(new Secret());
    }
}
