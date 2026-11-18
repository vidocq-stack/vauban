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
package io.vidocq.vauban.tck.langmodel;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.jboss.cdi.lang.model.tck.LangModelVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the language model TCK as its Javadoc asks: from an extension of the deployment, on the {@code ClassInfo} of
 * {@link LangModelVerifier}, which {@code @Discovery} adds. The outcome of each check is kept for the test to report.
 */
public class VerifyingExtension implements BuildCompatibleExtension {

    private static final AtomicReference<List<VerifierSteps.Outcome>> OUTCOMES = new AtomicReference<>();

    /** Forgets the outcomes of the last verification. */
    static void reset() {
        OUTCOMES.set(null);
    }

    /** The outcomes of the verification since the last {@link #reset()}, {@code null} if none ran. */
    static List<VerifierSteps.Outcome> outcomes() {
        return OUTCOMES.get();
    }

    @Discovery
    public void discovery(ScannedClasses classes) {
        classes.add(LangModelVerifier.class.getName());
    }

    /** Verifies once, should the container enhance the class twice. */
    @Enhancement(types = LangModelVerifier.class, withSubtypes = false)
    public void verify(ClassInfo clazz) {
        if (OUTCOMES.get() == null) {
            OUTCOMES.compareAndSet(null, VerifierSteps.run(clazz));
        }
    }
}
