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

import io.vidocq.vauban.core.annotation.AnnotationTypeInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.util.HashMap;
import java.util.Map;

public final class VaubanAnnotationInfo implements jakarta.enterprise.lang.model.AnnotationInfo {

    private final io.vidocq.vauban.indexer.model.AnnotationInfo indexAnnotation;
    private final IndexLookup lookup;
    /** The members with the defaults of the annotation type, read the first time a member is asked for. */
    private Map<String, AnnotationValue> values;

    public VaubanAnnotationInfo(io.vidocq.vauban.indexer.model.AnnotationInfo indexAnnotation, IndexLookup lookup) {
        this.indexAnnotation = indexAnnotation;
        this.lookup = lookup;
    }

    /** The index annotation this one wraps — the conversions of {@link LangModelAnnotations} unwrap it. */
    public io.vidocq.vauban.indexer.model.AnnotationInfo indexAnnotation() {
        return indexAnnotation;
    }

    /**
     * The annotation type, described as the index would describe it when the index does not hold it, as for any class
     * outside the deployment (BUG-20261117-31). One nothing describes throws the {@code IllegalArgumentException} it
     * threw for any annotation type outside the index.
     */
    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo declaration() {
        return new VaubanClassInfo(lookup.requireDeclaration(indexAnnotation.name()), lookup);
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
        return values().containsKey(name);
    }

    @Override
    public AnnotationMember member(String name) {
        var value = values().get(name);
        return value != null ? new VaubanAnnotationMember(value, lookup) : null;
    }

    @Override
    public Map<String, AnnotationMember> members() {
        var result = new HashMap<String, AnnotationMember>();
        values().forEach((k, v) -> result.put(k, new VaubanAnnotationMember(v, lookup)));
        return Map.copyOf(result);
    }

    /**
     * The members this annotation writes, and each other member of its type with the default the type declares, as
     * reflection shows an annotation (BUG-20261117-55). The index records the members an annotation writes, and the
     * defaults on its type's methods; a type nothing describes gives the written members alone. Immutable, so that
     * the unsynchronized first read publishes it whole to every thread. Read on first access: a read outside an
     * extension phase, at start, finds an annotation type outside the index described by nothing, and shows the
     * written members alone.
     */
    private Map<String, AnnotationValue> values() {
        var known = values;
        if (known == null) {
            known = withDefaults();
            values = known;
        }
        return known;
    }

    private Map<String, AnnotationValue> withDefaults() {
        var written = indexAnnotation.members();
        if (lookup == null) {
            // An annotation built without an index, as a test builds one: nothing describes its type.
            return written;
        }
        ReadClasses.note(indexAnnotation.name());
        var type = lookup.find(indexAnnotation.name()).filter(ClassInfo::isAnnotation);
        if (type.isEmpty()) {
            return written;
        }
        var result = new HashMap<>(written);
        for (var member : AnnotationTypeInfo.of(type.get()).members()) {
            if (member.defaultValue() != null) {
                result.putIfAbsent(member.name(), member.defaultValue());
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public String toString() {
        return "@" + indexAnnotation.name().value();
    }
}
