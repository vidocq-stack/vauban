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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VaubanAnnotationInfo implements jakarta.enterprise.lang.model.AnnotationInfo {

    private final io.vidocq.vauban.indexer.model.AnnotationInfo indexAnnotation;
    private final IndexLookup lookup;

    public VaubanAnnotationInfo(io.vidocq.vauban.indexer.model.AnnotationInfo indexAnnotation, IndexLookup lookup) {
        this.indexAnnotation = indexAnnotation;
        this.lookup = lookup;
    }

    /** The index annotation this one wraps — the conversions of {@link LangModelAnnotations} unwrap it. */
    public io.vidocq.vauban.indexer.model.AnnotationInfo indexAnnotation() {
        return indexAnnotation;
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo declaration() {
        var classInfo = lookup.requireClass(indexAnnotation.name());
        return new VaubanClassInfo(classInfo, lookup);
    }

    /**
     * Override the spec's default {@code name()} (which delegates to {@link #declaration()})
     * so that callers can read the annotation FQN without forcing the declaring class
     * to be present in the Vauban index. {@code @ConfigProperty}, {@code @ConfigProperties}
     * and most spec-defined qualifiers ship in their own JARs and are *not* added to the
     * application index — only the bytecode-resolved name from the index annotation
     * record is needed to identify them. Cf. VAU-BCE-001.
     */
    @Override
    public String name() {
        return indexAnnotation.name().value();
    }

    @Override
    public boolean hasMember(String name) {
        return indexAnnotation.hasMember(name);
    }

    @Override
    public AnnotationMember member(String name) {
        var value = indexAnnotation.member(name);
        return value != null ? new VaubanAnnotationMember(value, lookup) : null;
    }

    @Override
    public Map<String, AnnotationMember> members() {
        var result = new LinkedHashMap<String, AnnotationMember>();
        indexAnnotation.members().forEach((k, v) -> result.put(k, new VaubanAnnotationMember(v, lookup)));
        return Map.copyOf(result);
    }

    @Override
    public String toString() {
        return "@" + indexAnnotation.name().value();
    }
}
