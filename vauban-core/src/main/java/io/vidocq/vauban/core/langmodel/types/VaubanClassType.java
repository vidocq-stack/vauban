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
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.types.ClassType;

import java.util.List;
import java.util.Objects;

public final class VaubanClassType implements ClassType, AnnotatedTypeUse {

    private final DotName name;
    private final IndexLookup lookup;
    /** The annotations on this use of the type (BUG-20261117-60); they take no part in its equality. */
    private final List<AnnotationInfo> annotations;
    /**
     * The type that encloses this one in the index, {@code Outer<String>} for {@code Outer<String>.Plain}, or
     * {@code null}: the language model has no owner, and a lookup of the type needs it (BUG-20261118-56). It takes no
     * part in the equality, as the language model compares types.
     */
    private final TypeInfo indexOwner;

    /**
     * @param lookup the index to describe the class with, or {@code null} for a class an extension
     *               named — a class literal it passed to {@code AnnotationBuilder}, say, which names a
     *               class without the index having to describe it
     */
    public VaubanClassType(DotName name, IndexLookup lookup) {
        this(name, lookup, List.of());
    }

    /** The class {@code name}, used with {@code annotations} on it (BUG-20261117-60). */
    public VaubanClassType(DotName name, IndexLookup lookup, List<AnnotationInfo> annotations) {
        this(name, lookup, annotations, null);
    }

    /** The class {@code name} of the index, a member of {@code indexOwner} when not {@code null} (BUG-20261118-56). */
    public VaubanClassType(DotName name, IndexLookup lookup, List<AnnotationInfo> annotations, TypeInfo indexOwner) {
        this.name = Objects.requireNonNull(name);
        this.lookup = lookup;
        this.annotations = List.copyOf(annotations);
        this.indexOwner = indexOwner;
    }

    /** The type that encloses this one in the index, or {@code null} (BUG-20261118-56). */
    public TypeInfo indexOwner() {
        return indexOwner;
    }

    @Override
    public List<AnnotationInfo> annotations() {
        return annotations;
    }

    /**
     * The class, as {@link IndexLookup#declaration} describes it the first time the language model needs more than
     * its name. The Vauban index holds the deployment's classes only: a JDK type ({@code java.lang.String}), a class of
     * a library ({@code @ConfigProperty}, Jakarta Data's {@code CrudRepository}) or one the deployment did not add
     * appears in the types of the extensions without being indexed. It is described from its class file at start and
     * frozen, and from the compiler's model in the processor (VAU-BCE-008, BUG-20261117-31). One nothing describes,
     * and a class an extension named without a lookup, is a stub that keeps its name (VAU-BCE-001).
     */
    @Override
    public ClassInfo declaration() {
        return VaubanClassInfo.of(name, lookup);
    }

    /**
     * Returns the dot name of this class type, for internal use.
     */
    public DotName dotName() {
        return name;
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
