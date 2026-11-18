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
package io.vidocq.vauban.core.extensions.internal;

import java.util.List;
import java.util.function.Function;

/**
 * The exceptions a failed deployment throws: one message that lists every error under a header, and the exceptions
 * the extensions threw as its cause, the first one, and suppressed, the others (VAU-BCE-003). The container and the
 * enhancement of the archives the processor did not see throw them the same way (BUG-20261118-05).
 */
public final class DeploymentFailures {

    public static final String DEFINITION_FAILED = "CDI definition validation failed:\n";
    public static final String DEPLOYMENT_FAILED = "CDI deployment validation failed:\n";

    private DeploymentFailures() {
    }

    /** {@code header}, then one {@code "  - error"} line per error. */
    public static String failureMessage(String header, List<String> errors) {
        var msg = new StringBuilder(header);
        for (var error : errors) {
            msg.append("  - ").append(error).append("\n");
        }
        return msg.toString();
    }

    /** Throws the {@code DeploymentException} of {@code errors}, the first of {@code causes} as its cause. */
    public static void failOnDeploymentErrors(List<String> errors, List<Throwable> causes) {
        failOn(errors, causes, DEPLOYMENT_FAILED, jakarta.enterprise.inject.spi.DeploymentException::new);
    }

    /**
     * Throws the exception {@code failure} makes of the message of {@code errors} under {@code header}, the first of
     * {@code causes} as its cause and the others suppressed; nothing when there is no error.
     */
    public static void failOn(List<String> errors, List<Throwable> causes, String header,
                              Function<String, RuntimeException> failure) {
        if (errors.isEmpty()) {
            return;
        }
        var thrown = failure.apply(failureMessage(header, errors));
        if (!causes.isEmpty()) {
            thrown.initCause(causes.getFirst());
            for (var extra : causes.subList(1, causes.size())) {
                thrown.addSuppressed(extra);
            }
        }
        throw thrown;
    }
}
