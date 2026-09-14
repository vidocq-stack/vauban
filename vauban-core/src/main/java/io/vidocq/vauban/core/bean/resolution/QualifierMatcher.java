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
package io.vidocq.vauban.core.bean.resolution;

import io.vidocq.vauban.core.annotation.AnnotationKey;
import io.vidocq.vauban.core.annotation.AnnotationTypes;
import io.vidocq.vauban.core.bean.model.QualifierInstance;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matches qualifier instances following CDI rules, by comparing their {@link AnnotationKey}s: a member
 * default counts as written, and {@code @Nonbinding} members, like those an extension made non-binding,
 * take no part. Matching compares index data and never invokes the members of the qualifiers it compares:
 * the container's {@link AnnotationTypes} supplies what each qualifier type declares, from the index or
 * through the container's class loaders.
 */
public final class QualifierMatcher {

    // Volatile immutable map: assigned once at startup, read-only after — thread-safe by design.
    // Only the run-time matching of VaubanBeanManager still reads it; vauban#70 moves that path to keys.
    @SuppressWarnings("java:S3077")
    private static volatile Map<String, Set<String>> customNonbindingMembers = Map.of();

    public static void setCustomNonbindingMembers(Map<String, Set<String>> nonbindingMembers) {
        customNonbindingMembers = nonbindingMembers != null ? nonbindingMembers : Map.of();
    }

    public static Set<String> getCustomNonbindingMembers(String qualifierName) {
        return customNonbindingMembers.get(qualifierName);
    }

    private final AnnotationTypes types;
    private final Map<QualifierInstance, AnnotationKey> keys = new ConcurrentHashMap<>();

    public QualifierMatcher(AnnotationTypes types) {
        this.types = Objects.requireNonNull(types);
    }

    /**
     * A matcher for a resolver built outside a container: no index, no extension, qualifier types read
     * through the thread context class loader and vauban-core's own loader.
     */
    public static QualifierMatcher standalone() {
        return new QualifierMatcher(new AnnotationTypes(null,
                Arrays.asList(Thread.currentThread().getContextClassLoader(), QualifierMatcher.class.getClassLoader()),
                Map.of()));
    }

    /**
     * Checks if a bean's qualifiers match the required qualifiers at an injection point.
     * CDI rule: every required qualifier must be present on the bean.
     * {@code @Any} matches everything. {@code @Default} matches when the bean has {@code @Default}.
     */
    public boolean matches(Set<QualifierInstance> beanQualifiers, Set<QualifierInstance> requiredQualifiers) {
        for (var required : requiredQualifiers) {
            if (required.isAny()) continue; // @Any matches everything
            if (!containsQualifier(beanQualifiers, required)) return false;
        }
        return true;
    }

    private boolean containsQualifier(Set<QualifierInstance> beanQualifiers, QualifierInstance required) {
        var requiredKey = key(required);
        for (var candidate : beanQualifiers) {
            if (key(candidate).equals(requiredKey)) return true;
        }
        return false;
    }

    private AnnotationKey key(QualifierInstance qualifier) {
        return keys.computeIfAbsent(qualifier, q -> types.key(q.annotationName(), q.members()));
    }
}
