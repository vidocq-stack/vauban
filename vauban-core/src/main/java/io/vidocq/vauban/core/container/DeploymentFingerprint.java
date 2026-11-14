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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.extensions.FrozenExtensions;
import io.vidocq.vauban.indexer.model.ClassInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * What tells one deployment from another, for the extensions frozen at build (VAU-PERF-005): the bean classes, by
 * name and by what the extensions see of them, the build compatible extensions among them by their code as well, and
 * the discovery settings of the builder. The build writes it beside what the extensions did; a start loads that only
 * for the same fingerprint, so a class added or removed since, a change an extension could see, or a change to an
 * extension, sends the start back to running the extensions.
 *
 * <p>The body of a bean's method and the debug information of a class file do not count (see {@link ClassChecksums}):
 * a {@code jlink} image, whose classes lost their debug information, keeps the record of its build
 * (BUG-20261113-17). The models are the ones the builder scans to index the classes, so the fingerprint costs a pass
 * over what is already in memory. The beans the embedding runtime adds itself are left out by the builder, which
 * never gives them here.
 */
final class DeploymentFingerprint {

    private final TreeMap<String, String> classes = new TreeMap<>();
    private final String settings;

    /** @param settings the builder settings that change what discovery finds, as one line */
    DeploymentFingerprint(String settings) {
        this.settings = settings;
    }

    /**
     * One bean class, by what the extensions see of it; {@code model} is null for a class without a class file among
     * the resources, which counts by its name.
     */
    void add(String className, ClassInfo model) {
        classes.put(className, model == null ? "-" : Long.toHexString(ClassChecksums.ofModel(model)));
    }

    /** One build compatible extension, by what the extensions see of it and by its code. */
    void addExtension(String className, ClassInfo model, byte[] classFile) {
        if (model == null) {
            add(className, null);
        } else {
            classes.put(className, Long.toHexString(ClassChecksums.ofModel(model)) + "+"
                    + Long.toHexString(ClassChecksums.ofCode(classFile)));
        }
    }

    /** What the fingerprint is computed from, for the record to keep and a refused start to compare. */
    FrozenExtensions.Deployment deployment() {
        return new FrozenExtensions.Deployment(settings, classes);
    }

    /** The fingerprint: the same for the same classes whatever the order they came in. */
    String value() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(settings.getBytes(StandardCharsets.UTF_8));
            classes.forEach((name, checksum) ->
                    digest.update(("\n" + name + ":" + checksum).getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required algorithm of every Java platform", e);
        }
    }
}
