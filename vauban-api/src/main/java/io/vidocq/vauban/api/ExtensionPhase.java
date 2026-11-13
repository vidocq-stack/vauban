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
package io.vidocq.vauban.api;

/**
 * Whether the build compatible extension running on this thread runs while the application
 * compiles or while the container starts.
 *
 * <p>CDI Lite lets an implementation run an extension at either moment and tells the extension
 * neither. Vauban runs it at both: the annotation processor runs it to validate the deployment and
 * freeze what it produces, and the container runs it again when it starts. Most extensions do the
 * same work both times. One that checks the deployment's environment — a configuration value, a
 * resource the application will open — would find the build machine's at compile time, not the one
 * the application will run on: it asks {@link #isBuildTime()} and leaves that check to the start.
 *
 * <p>A build that freezes what the extensions produce (the container start then loads it instead of
 * running them) takes the question as the extension saying it left work for the start: an extension
 * that asked runs again at each start, the others do not. So an extension asks only where its work
 * depends on where the application starts, and keeps everything else out of that path.
 *
 * <p>The answer belongs to the thread, for the duration of the call that opened the phase: an
 * extension running anywhere else, or after that call returns, runs at container start.
 */
public final class ExtensionPhase {

    private static final ScopedValue<Boolean> BUILD_TIME = ScopedValue.newInstance();

    /** Told each time an extension asks {@link #isBuildTime()}, while {@link #tellWhenAsked} runs. */
    private static final ScopedValue<Runnable> ASKED = ScopedValue.newInstance();

    private ExtensionPhase() {}

    /** {@code true} while Vauban runs extensions as part of compiling the application. */
    public static boolean isBuildTime() {
        if (ASKED.isBound()) {
            ASKED.get().run();
        }
        return BUILD_TIME.orElse(false);
    }

    /**
     * Runs {@code operation} and calls {@code asked} each time {@link #isBuildTime()} is asked on this
     * thread meanwhile. For the container, which runs one extension at a time this way and so learns
     * which of them left work for the start.
     */
    public static <R, X extends Throwable> R tellWhenAsked(Runnable asked,
            ScopedValue.CallableOp<? extends R, X> operation) throws X {
        return ScopedValue.where(ASKED, asked).call(operation);
    }

    /**
     * Runs {@code operation} as build-time work: every extension it runs on this thread sees
     * {@link #isBuildTime()} return {@code true}. For the tools that run extensions while an
     * application builds — the Vauban annotation processor and the Maven plugins.
     */
    public static <R, X extends Throwable> R atBuildTime(ScopedValue.CallableOp<? extends R, X> operation)
            throws X {
        return ScopedValue.where(BUILD_TIME, true).call(operation);
    }
}
