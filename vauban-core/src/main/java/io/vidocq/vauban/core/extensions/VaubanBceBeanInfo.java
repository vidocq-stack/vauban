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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.DisposerInfo;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;
import java.util.List;

/**
 * Adapts Vauban's {@link BeanDescriptor} to the BCE {@link BeanInfo} interface.
 */
public final class VaubanBceBeanInfo implements BeanInfo {

    private final BeanDescriptor descriptor;
    private final IndexLookup lookup;

    public VaubanBceBeanInfo(BeanDescriptor descriptor, IndexLookup lookup) {
        this.descriptor = descriptor;
        this.lookup = lookup;
    }

    @Override
    public jakarta.enterprise.inject.build.compatible.spi.ScopeInfo scope() {
        return new VaubanBceScopeInfo(descriptor.scope(), lookup);
    }

    @Override
    public Collection<Type> types() {
        return descriptor.types().stream()
                .map(t -> TypeMapper.map(t, lookup))
                .toList();
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        return descriptor.qualifiers().stream()
                .map(q -> (AnnotationInfo) new SimpleAnnotationInfo(q.annotationName().value(), java.util.Map.of()))
                .toList();
    }

    @Override
    public ClassInfo declaringClass() {
        var indexClass = lookup.getClass(descriptor.beanClass()).orElse(null);
        if (indexClass != null) {
            return new VaubanClassInfo(indexClass, lookup);
        }
        return null;
    }

    @Override
    public boolean isClassBean() {
        return descriptor.kind() == BeanDescriptor.BeanKind.MANAGED;
    }

    @Override
    public boolean isProducerMethod() {
        return descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD;
    }

    @Override
    public boolean isProducerField() {
        return descriptor.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD;
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }

    @Override
    public MethodInfo producerMethod() {
        return null;
    }

    @Override
    public FieldInfo producerField() {
        return null;
    }

    @Override
    public boolean isAlternative() {
        return descriptor.isAlternative();
    }

    @Override
    public Integer priority() {
        return descriptor.priority() > 0 ? descriptor.priority() : null;
    }

    @Override
    public String name() {
        return descriptor.name();
    }

    @Override
    public DisposerInfo disposer() {
        return null;
    }

    @Override
    public Collection<StereotypeInfo> stereotypes() {
        return List.of();
    }

    @Override
    public Collection<jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo> injectionPoints() {
        return descriptor.injectionPoints().stream()
                .map(ip -> (jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo)
                        new VaubanBceInjectionPointInfo(ip, descriptor.beanClass(), lookup))
                .toList();
    }

    public BeanDescriptor descriptor() {
        return descriptor;
    }
}
