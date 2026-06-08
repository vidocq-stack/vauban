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

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;
import java.util.List;

public final class VaubanBceObserverInfo implements ObserverInfo {

    private final ObserverDescriptor descriptor;
    private final IndexLookup lookup;

    public VaubanBceObserverInfo(ObserverDescriptor descriptor, IndexLookup lookup) {
        this.descriptor = descriptor;
        this.lookup = lookup;
    }

    @Override
    public Type eventType() {
        return TypeMapper.map(descriptor.eventType(), lookup);
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        return List.of();
    }

    @Override
    public ClassInfo declaringClass() {
        var indexClass = lookup.getClass(descriptor.declaringClass()).orElse(null);
        if (indexClass != null) {
            return new VaubanClassInfo(indexClass, lookup);
        }
        return null;
    }

    @Override
    public MethodInfo observerMethod() {
        return null;
    }

    @Override
    public ParameterInfo eventParameter() {
        return null;
    }

    @Override
    public BeanInfo bean() {
        return null;
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }

    @Override
    public int priority() {
        return descriptor.priority();
    }

    @Override
    public boolean isAsync() {
        return descriptor.async();
    }

    @Override
    public Reception reception() {
        return Reception.valueOf(descriptor.reception());
    }

    @Override
    public TransactionPhase transactionPhase() {
        return TransactionPhase.valueOf(descriptor.transactionPhase());
    }
}
