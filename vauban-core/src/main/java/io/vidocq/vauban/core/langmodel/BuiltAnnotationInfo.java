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

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.util.Map;

public final class BuiltAnnotationInfo implements AnnotationInfo {

    private final Class<? extends Annotation> annotationType;
    private final Map<String, AnnotationMember> memberMap;

    public BuiltAnnotationInfo(Class<? extends Annotation> annotationType, Map<String, AnnotationMember> memberMap) {
        this.annotationType = annotationType;
        this.memberMap = Map.copyOf(memberMap);
    }

    @Override
    public ClassInfo declaration() {
        throw new UnsupportedOperationException(
                "declaration() not supported on built annotations (no index available)");
    }

    @Override
    public String name() {
        return annotationType.getName();
    }

    @Override
    public boolean hasMember(String name) {
        return memberMap.containsKey(name);
    }

    @Override
    public AnnotationMember member(String name) {
        return memberMap.get(name);
    }

    @Override
    public Map<String, AnnotationMember> members() {
        return memberMap;
    }

    public Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    @Override
    public String toString() {
        return "@" + annotationType.getName();
    }
}
