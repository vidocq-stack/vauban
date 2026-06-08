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
package io.vidocq.vauban.core.langmodel.types;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ClassType;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class VaubanClassType implements ClassType {

    private final DotName name;
    private final IndexLookup lookup;

    public VaubanClassType(DotName name, IndexLookup lookup) {
        this.name = Objects.requireNonNull(name);
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public ClassInfo declaration() {
        // The Vauban index contains only application/scanned classes. JDK types
        // (java.lang.String, java.util.Optional, …) and external library types
        // (e.g. annotation classes shipped in spec JARs like @ConfigProperty)
        // legitimately appear in injection-point types but are absent from the
        // index. Falling back to a synthetic stub keeps the spec contract
        // ({@code ClassType.declaration().name()} returns the FQN) without
        // forcing every referenced type to be eagerly indexed. Cf. VAU-BCE-001.
        var indexClass = lookup.getClass(name).orElseGet(() -> syntheticClassInfo(name));
        return new VaubanClassInfo(indexClass, lookup);
    }

    private static io.vidocq.vauban.indexer.model.ClassInfo syntheticClassInfo(DotName name) {
        return new io.vidocq.vauban.indexer.model.ClassInfo(
                name,
                DotName.of("java.lang.Object"),
                List.of(),
                java.lang.reflect.Modifier.PUBLIC,
                List.of(),
                List.of(),
                List.of(),
                io.vidocq.vauban.indexer.model.ClassInfo.ClassKind.CLASS);
    }

    /**
     * Returns the dot name of this class type, for internal use.
     */
    public DotName dotName() {
        return name;
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return false;
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return false;
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return null;
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return List.of();
    }

    @Override
    public String toString() {
        return name.value();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaubanClassType that)) return false;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
