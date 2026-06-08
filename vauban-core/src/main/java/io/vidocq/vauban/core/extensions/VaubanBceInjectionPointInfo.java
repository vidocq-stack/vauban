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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanFieldInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;

/**
 * Adapts Vauban's internal {@link InjectionPointInfo} (model) to the BCE
 * {@link jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo} contract.
 *
 * <p>Cf. CDI Lite 4.1 §16.2 (Build Compatible Extensions). Required for
 * {@code @Registration} methods that read {@code BeanInfo.injectionPoints()}
 * — typical pattern of MicroProfile Config's {@code ConfigCdiExtension}
 * (Ravel) which collects every {@code @ConfigProperty} injection point
 * during {@code @Registration} and synthesizes a {@code SyntheticBean}
 * per type during {@code @Synthesis}.</p>
 */
public final class VaubanBceInjectionPointInfo
        implements jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo {

    private final InjectionPointInfo ip;
    private final DotName declaringClassName;
    private final IndexLookup lookup;

    public VaubanBceInjectionPointInfo(InjectionPointInfo ip,
                                        DotName declaringClassName,
                                        IndexLookup lookup) {
        this.ip = ip;
        this.declaringClassName = declaringClassName;
        this.lookup = lookup;
    }

    @Override
    public Type type() {
        return TypeMapper.map(ip.requiredType(), lookup);
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        return ip.qualifiers().stream()
                .map(this::toAnnotationInfo)
                .toList();
    }

    @Override
    public DeclarationInfo declaration() {
        var indexClass = lookup.getClass(declaringClassName).orElse(null);
        if (indexClass == null) return null;
        var classInfo = new VaubanClassInfo(indexClass, lookup);

        // Best-effort: locate the FieldInfo for FIELD-kind injection points by
        // parsing the description ("field <simpleName>.<fieldName>"). For
        // method/constructor parameters we do not yet expose ParameterInfo —
        // fallback to the declaring ClassInfo, which still satisfies the
        // contract (DeclarationInfo is the supertype).
        if (ip.kind() == InjectionPointInfo.InjectionKind.FIELD) {
            var desc = ip.description();
            int dotIdx = desc.lastIndexOf('.');
            if (dotIdx > 0 && desc.startsWith("field ")) {
                var fieldName = desc.substring(dotIdx + 1);
                for (var field : indexClass.fields()) {
                    if (field.name().equals(fieldName)) {
                        return new VaubanFieldInfo(field, classInfo, lookup);
                    }
                }
            }
        }
        return classInfo;
    }

    private AnnotationInfo toAnnotationInfo(QualifierInstance q) {
        var indexAnnotation = new io.vidocq.vauban.indexer.model.AnnotationInfo(
                q.annotationName(), q.members());
        return new VaubanAnnotationInfo(indexAnnotation, lookup);
    }
}
