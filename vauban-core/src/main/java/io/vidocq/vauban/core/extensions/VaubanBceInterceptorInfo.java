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
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import jakarta.enterprise.inject.build.compatible.spi.DisposerInfo;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.ScopeInfo;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class VaubanBceInterceptorInfo implements InterceptorInfo {

    private final InterceptorDescriptor descriptor;
    private final IndexLookup lookup;
    /** The interceptor class described as a managed bean, for its bean types, qualifiers and injection points. */
    private final VaubanBceBeanInfo asBean;

    public VaubanBceInterceptorInfo(InterceptorDescriptor descriptor, IndexLookup lookup) {
        this(descriptor, null, lookup);
    }

    /**
     * @param asBean the interceptor class as the managed bean it would be, which gives the interceptor its bean types,
     *               qualifiers and injection points (BUG-20261117-04); {@code null} when it cannot be one: none
     */
    public VaubanBceInterceptorInfo(InterceptorDescriptor descriptor, BeanDescriptor asBean, IndexLookup lookup) {
        this.descriptor = descriptor;
        this.lookup = lookup;
        this.asBean = asBean == null ? null : new VaubanBceBeanInfo(asBean, lookup);
    }

    @Override
    public Collection<AnnotationInfo> interceptorBindings() {
        return descriptor.bindings().stream()
                .map(b -> (AnnotationInfo) new SimpleAnnotationInfo(b.value(), Map.of()))
                .toList();
    }

    @Override
    public boolean intercepts(InterceptionType type) {
        return switch (type) {
            case AROUND_INVOKE -> descriptor.aroundInvokeMethod() != null;
            case AROUND_CONSTRUCT -> descriptor.aroundConstructMethod() != null;
            default -> false;
        };
    }

    @Override
    public ScopeInfo scope() {
        return new VaubanBceScopeInfo(
                new io.vidocq.vauban.core.bean.model.ScopeInfo(
                        io.vidocq.vauban.indexer.model.DotName.of("jakarta.enterprise.context.Dependent"), false),
                lookup);
    }

    /** The bean types of the interceptor class, as a managed bean's (BUG-20261117-04). */
    @Override
    public Collection<Type> types() {
        return asBean == null ? List.of() : asBean.types();
    }

    /** The qualifiers of the interceptor class, as a managed bean's (BUG-20261117-04). */
    @Override
    public Collection<AnnotationInfo> qualifiers() {
        return asBean == null ? List.of() : asBean.qualifiers();
    }

    @Override
    public ClassInfo declaringClass() {
        var indexClass = lookup.getClass(descriptor.interceptorClass()).orElse(null);
        if (indexClass != null) {
            return new VaubanClassInfo(indexClass, lookup);
        }
        return null;
    }

    @Override
    public boolean isClassBean() {
        return true;
    }

    @Override
    public boolean isProducerMethod() {
        return false;
    }

    @Override
    public boolean isProducerField() {
        return false;
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
        return false;
    }

    @Override
    public Integer priority() {
        return descriptor.enabled() ? descriptor.priority() : null;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public DisposerInfo disposer() {
        return null;
    }

    @Override
    public Collection<StereotypeInfo> stereotypes() {
        return List.of();
    }

    /** The injection points of the interceptor class, as a managed bean's (BUG-20261117-04). */
    @Override
    public Collection<InjectionPointInfo> injectionPoints() {
        return asBean == null ? List.of() : asBean.injectionPoints();
    }
}
