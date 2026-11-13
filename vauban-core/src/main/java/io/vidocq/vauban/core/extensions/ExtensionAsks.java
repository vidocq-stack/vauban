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

import io.vidocq.vauban.api.ExtensionPhase;

import java.util.Set;

/**
 * Which build compatible extensions asked {@link ExtensionPhase#isBuildTime()} while they ran.
 *
 * <p>An extension asks when part of its work depends on where the application starts: it does that
 * part only when the container runs it at start. A build that freezes what the extensions produce
 * (VAU-PERF-005) must therefore run such an extension again at start, and only it. The build records
 * the extensions that asked {@link #recordingInto into a set}; {@link BceProcessor} runs each step of
 * each extension through {@link #of}, which names the extension when it asks. Nothing is recorded,
 * and nothing is paid, outside a recording.
 */
public final class ExtensionAsks {

    private static final ScopedValue<Set<Class<?>>> RECORD = ScopedValue.newInstance();

    private ExtensionAsks() {}

    /** A step of an extension, which may fail. */
    @FunctionalInterface
    interface Step<X extends Exception> {
        void run() throws X;
    }

    /** Runs {@code operation}; the extensions that ask whether they run at build time meanwhile go to {@code asked}. */
    public static <R, X extends Throwable> R recordingInto(Set<Class<?>> asked,
            ScopedValue.CallableOp<? extends R, X> operation) throws X {
        return ScopedValue.where(RECORD, asked).call(operation);
    }

    /** Runs {@code step}, a step of {@code extension}: if it asks during a recording, the extension is recorded. */
    static <X extends Exception> void of(Class<?> extension, Step<X> step) throws X {
        if (!RECORD.isBound()) {
            step.run();
            return;
        }
        Set<Class<?>> asked = RECORD.get();
        ExtensionPhase.<Void, X>tellWhenAsked(() -> asked.add(extension), () -> {
            step.run();
            return null;
        });
    }

    /**
     * Whether the extensions run at build time, asked by the container for itself: the extension it is running
     * did not ask, and is not recorded.
     */
    static boolean isBuildTime() {
        return ExtensionPhase.<Boolean, RuntimeException>tellWhenAsked(() -> { }, ExtensionPhase::isBuildTime);
    }
}
