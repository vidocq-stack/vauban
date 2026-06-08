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

import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.lang.model.types.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects synthetic bean and observer definitions during @Synthesis phase.
 */
public final class VaubanSyntheticComponents implements SyntheticComponents {

    private final List<VaubanSyntheticBeanBuilder<?>> beanDefinitions = new ArrayList<>();
    private final List<VaubanSyntheticObserverBuilder<?>> observerDefinitions = new ArrayList<>();

    @Override
    public <T> SyntheticBeanBuilder<T> addBean(Class<T> implementationClass) {
        var builder = new VaubanSyntheticBeanBuilder<>(implementationClass);
        beanDefinitions.add(builder);
        return builder;
    }

    @Override
    public <T> SyntheticObserverBuilder<T> addObserver(Class<T> eventType) {
        var builder = new VaubanSyntheticObserverBuilder<>(eventType);
        observerDefinitions.add(builder);
        return builder;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> SyntheticObserverBuilder<T> addObserver(Type eventType) {
        var builder = new VaubanSyntheticObserverBuilder<T>(eventType);
        observerDefinitions.add(builder);
        return builder;
    }

    public List<VaubanSyntheticBeanBuilder<?>> getBeanDefinitions() {
        return beanDefinitions;
    }

    public List<VaubanSyntheticObserverBuilder<?>> getObserverDefinitions() {
        return observerDefinitions;
    }
}
