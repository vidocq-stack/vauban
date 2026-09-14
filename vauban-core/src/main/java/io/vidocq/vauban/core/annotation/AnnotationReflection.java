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
package io.vidocq.vauban.core.annotation;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Vauban may do when an annotation reaches it as an object rather than as index data:
 * {@code -Dvauban.annotations.reflection=allow|warn|forbid}.
 *
 * <p>Matching itself never reflects — it compares {@link AnnotationKey}s built from the index. Three
 * things still can: reading the members of an annotation instance, reading what an annotation type
 * declares when neither the index nor a class file describes it, and building an instance to hand to
 * application code. {@code warn} logs each one once, {@code forbid} makes it throw — which is how a
 * test pins what the generated path covers, and how a native-image user sees what is left.
 */
public enum AnnotationReflection {

    /** Reflect when needed. The default. */
    ALLOW,
    /** Reflect, and log the first time each type needs it. */
    WARN,
    /** Refuse to reflect. */
    FORBID;

    public static final String PROPERTY = "vauban.annotations.reflection";

    private static final System.Logger LOG = System.getLogger(AnnotationReflection.class.getName());
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** The mode the system property asks for, read on each call so a test can set and restore it. */
    public static AnnotationReflection current() {
        var value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            return ALLOW;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "warn" -> WARN;
            case "forbid" -> FORBID;
            default -> throw new IllegalStateException(
                    PROPERTY + " must be allow, warn or forbid, not " + value);
        };
    }

    /**
     * Called by a fallback before it reflects.
     *
     * @param what     what it is about to do, as a sentence opening: "reading the members of"
     * @param typeName the annotation type it concerns
     * @throws IllegalStateException in {@link #FORBID}
     */
    static void check(String what, String typeName) {
        var mode = current();
        if (mode == ALLOW) {
            return;
        }
        var message = PROPERTY + "=" + mode.name().toLowerCase(Locale.ROOT)
                + ": " + what + " " + typeName + " needs reflection";
        if (mode == FORBID) {
            throw new IllegalStateException(message);
        }
        if (WARNED.add(what + " " + typeName)) {
            LOG.log(System.Logger.Level.WARNING, message);
        }
    }
}
