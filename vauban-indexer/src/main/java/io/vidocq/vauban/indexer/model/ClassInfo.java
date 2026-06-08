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
package io.vidocq.vauban.indexer.model;

import java.util.List;
import java.util.Optional;

public record ClassInfo(
        DotName name,
        DotName superName,
        List<DotName> interfaces,
        int accessFlags,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        List<AnnotationInfo> annotations,
        ClassKind kind
) {

    public enum ClassKind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    }

    public ClassInfo {
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        annotations = List.copyOf(annotations);
    }

    public boolean isPublic() {
        return (accessFlags & 0x0001) != 0;
    }

    public boolean isAbstract() {
        return (accessFlags & 0x0400) != 0;
    }

    public boolean isFinal() {
        return (accessFlags & 0x0010) != 0;
    }

    public boolean isInterface() {
        return kind == ClassKind.INTERFACE || kind == ClassKind.ANNOTATION;
    }

    public boolean isAnnotation() {
        return kind == ClassKind.ANNOTATION;
    }

    public boolean isEnum() {
        return kind == ClassKind.ENUM;
    }

    public boolean isRecord() {
        return kind == ClassKind.RECORD;
    }

    public Optional<AnnotationInfo> annotation(DotName annotationName) {
        return annotations.stream().filter(a -> a.name().equals(annotationName)).findFirst();
    }

    public boolean hasAnnotation(DotName annotationName) {
        return annotations.stream().anyMatch(a -> a.name().equals(annotationName));
    }
}
