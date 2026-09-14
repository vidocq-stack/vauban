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

import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.List;
import java.util.Optional;

/**
 * What an annotation type declares about its members: the value each one defaults to, and whether it
 * is {@code @Nonbinding}. It is data read from the type's {@link ClassInfo}, whichever scanner produced
 * it, so matching needs neither the type's {@code Class} nor reflection.
 *
 * @param name    the binary name of the annotation type
 * @param members the members, in declaration order when read from a class file
 */
public record AnnotationTypeInfo(DotName name, List<Member> members) {

    static final DotName NONBINDING = DotName.of("jakarta.enterprise.util.Nonbinding");

    public AnnotationTypeInfo {
        members = List.copyOf(members);
    }

    /**
     * One member of an annotation type.
     *
     * @param name         the member name
     * @param defaultValue the declared default, or {@code null} when the member has none
     * @param nonbinding   whether the member is annotated {@code @Nonbinding}
     */
    public record Member(String name, AnnotationValue defaultValue, boolean nonbinding) {
    }

    /**
     * Reads the members of an indexed annotation type.
     *
     * @throws IllegalArgumentException when {@code type} is not an annotation type
     */
    public static AnnotationTypeInfo of(ClassInfo type) {
        if (!type.isAnnotation()) {
            throw new IllegalArgumentException(type.name() + " is not an annotation type");
        }
        var members = type.methods().stream()
                .filter(method -> method.parameters().isEmpty() && !method.isStatic() && !method.isSynthetic())
                .map(method -> new Member(method.name(), method.defaultValue(),
                        method.annotations().stream().anyMatch(annotation -> annotation.name().equals(NONBINDING))))
                .toList();
        return new AnnotationTypeInfo(type.name(), members);
    }

    public Optional<Member> member(String memberName) {
        return members.stream().filter(member -> member.name().equals(memberName)).findFirst();
    }
}
