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
package io.vidocq.vauban.core.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import java.lang.annotation.Annotation;
import java.util.concurrent.ConcurrentHashMap;

public final class ApplicationContext implements AlterableContext {

    private record ContextualInstance(Object instance, CreationalContext<?> ctx) {}

    private final ConcurrentHashMap<Contextual<?>, ContextualInstance> instances = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    @Override
    public Class<? extends Annotation> getScope() {
        return ApplicationScoped.class;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        var existing = instances.get(contextual);
        if (existing != null) {
            return (T) existing.instance();
        }
        if (creationalContext == null) {
            return null;
        }
        
        // CDI Spec: if another thread created the instance in the meantime, 
        // we must destroy the one we just created and return the existing one.
        var instance = contextual.create(creationalContext);
        var ci = new ContextualInstance(instance, creationalContext);
        var previous = instances.putIfAbsent(contextual, ci);
        if (previous != null) {
            // Another thread won the race. Destroy the instance we created.
            contextual.destroy(instance, creationalContext);
            // DO NOT release the creationalContext if it's the one we're still using for the bean
            // but here we created a new one in VaubanContainer.getContextualInstance
            return (T) previous.instance();
        }
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Contextual<T> contextual) {
        var ci = instances.get(contextual);
        return ci != null ? (T) ci.instance() : null;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy(Contextual<?> contextual) {
        var ci = instances.remove(contextual);
        if (ci != null) {
            ((Contextual<Object>) contextual).destroy(ci.instance(), (CreationalContext<Object>) ci.ctx());
            ci.ctx().release();
        }
    }

    public void deactivate() {
        active = false;
        for (var entry : instances.entrySet()) {
            destroy(entry.getKey());
        }
        instances.clear();
    }
}
