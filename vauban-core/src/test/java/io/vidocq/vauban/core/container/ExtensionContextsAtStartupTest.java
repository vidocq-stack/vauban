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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The contexts build compatible extensions register ({@code MetaAnnotations.addContext}) are installed before the
 * container fires {@code @Initialized(ApplicationScoped.class)} and {@code Startup}: an observer of those events
 * reaches a bean of such a scope, as it reaches an {@code @ApplicationScoped} one. Mansart's {@code @TransactionScoped}
 * is one: a {@code Startup} observer that writes through a JTA entity manager failed with "No context registered for
 * scope jakarta.transaction.TransactionScoped".
 */
@DisplayName("VaubanContainer: the contexts of build compatible extensions at startup")
class ExtensionContextsAtStartupTest {

    @NormalScope
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    public @interface Ledgered {
    }

    /** The context of {@link Ledgered}, always active, one instance per bean. */
    public static class LedgeredContext implements AlterableContext {
        private final Map<Contextual<?>, Object> instances = new ConcurrentHashMap<>();

        @Override
        public Class<? extends Annotation> getScope() {
            return Ledgered.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
            return (T) instances.computeIfAbsent(contextual, c -> contextual.create(creationalContext));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Contextual<T> contextual) {
            return (T) instances.get(contextual);
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void destroy(Contextual<?> contextual) {
            instances.remove(contextual);
        }
    }

    public static class LedgeredScopeExtension implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addContext(Ledgered.class, true, LedgeredContext.class);
        }
    }

    @Ledgered
    public static class Entries {
        public String id() {
            return "entries";
        }
    }

    /** Reads {@link Entries} as the container starts. */
    @ApplicationScoped
    public static class StartupReader {
        @Inject
        Entries entries;

        private String atInitialized;
        private String atStartup;

        public void initialized(@Observes @Initialized(ApplicationScoped.class) Object event) {
            atInitialized = entries.id();
        }

        public void startup(@Observes Startup event) {
            atStartup = entries.id();
        }

        public String atInitialized() {
            return atInitialized;
        }

        public String atStartup() {
            return atStartup;
        }
    }

    @Test
    @DisplayName("an observer of the startup events reaches a bean of a scope an extension registered")
    void startupObserversReachAnExtensionScope() {
        try (var container = VaubanContainer.builder()
                .addBeanClass(LedgeredScopeExtension.class)
                .addBeanClass(Entries.class)
                .addBeanClass(StartupReader.class)
                .build()) {

            var reader = container.select(StartupReader.class);

            assertEquals("entries", reader.atInitialized());
            assertEquals("entries", reader.atStartup());
        }
    }
}
