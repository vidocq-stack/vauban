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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A default method that a superclass's private method shadows, through the sources the processor
 * renders, on the module path (BUG-20261004-08): {@code super.hidden(...)} — and any call typed by
 * the bean class from outside its superclass's nest — resolves to the private method and fails
 * with {@code IllegalAccessError}. The {@code $$Intercepted} subclass reaches the default
 * explicitly, and the client proxy forwards through the interface.
 */
@DisplayName("Shadowed default method — processor subclass and proxy, module path")
class ShadowedDefaultModulePathTest {

    @BeforeEach
    void reset() {
        AuditInterceptor.METHODS.clear();
    }

    private static VaubanContainer boot(Class<?> beanClass) {
        return VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(beanClass)
                .build();
    }

    @Test
    @DisplayName("through the build-time subclass: intercepted, and the default body runs")
    void throughTheSubclass() throws Exception {
        try (var container = boot(ShadowedDefaultService.class)) {
            var service = container.select(ShadowedDefaultService.class);
            assertEquals(Class.forName(ShadowedDefaultService.class.getName() + "$$Intercepted"), service.getClass());
            AuditedHider hider = service;
            assertEquals("default x", hider.hidden("x"));
            assertEquals(List.of(AuditedHider.class.getDeclaredMethod("hidden", String.class)),
                    AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a shadowed default of a generic interface: listed with the bean's type arguments")
    void genericInterface() throws Exception {
        try (var container = boot(GenericShadowedService.class)) {
            var service = container.select(GenericShadowedService.class);
            assertEquals(Class.forName(GenericShadowedService.class.getName() + "$$Intercepted"), service.getClass());
            Labeled<String> labeled = service;
            assertEquals("label x", labeled.label("x"));
            assertEquals(List.of(Labeled.class.getDeclaredMethod("label", Object.class)), AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a default shadowed by a package-private method of another package: subclass and proxy reach the default")
    void crossPackagePackagePrivateShadow() throws Exception {
        // ForeignPackagePrivateTagBase.tag(String) is not the beans' member (JLS 8.4.8): Tagged.tag
        // is, and both generated classes reach it through Tagged.
        var tag = Tagged.class.getDeclaredMethod("tag", String.class);
        try (var container = boot(CrossPackageTagService.class)) {
            var service = container.select(CrossPackageTagService.class);
            assertEquals(Class.forName(CrossPackageTagService.class.getName() + "$$Intercepted"), service.getClass());
            Tagged tagged = service;
            assertEquals("default x", tagged.tag("x"));
            assertEquals(List.of(tag), AuditInterceptor.METHODS);
        }
        AuditInterceptor.METHODS.clear();
        try (var container = boot(ScopedCrossPackageTagService.class)) {
            var service = container.select(ScopedCrossPackageTagService.class);
            assertEquals(Class.forName(ScopedCrossPackageTagService.class.getName() + "_ClientProxy"), service.getClass());
            Tagged tagged = service;
            assertEquals("default y", tagged.tag("y"));
            assertEquals(List.of(tag), AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("a public superclass method with the default's descriptor is no shadow: proxy and subclass agree")
    void inheritedClassMethodIsNoShadow() throws Exception {
        try (var container = boot(ScopedPlainLabelledService.class)) {
            var service = container.select(ScopedPlainLabelledService.class);
            assertEquals(Class.forName(ScopedPlainLabelledService.class.getName() + "_ClientProxy"), service.getClass());
            assertEquals("plain x", service.label("x"));
            PlainLabeled labeled = service;
            assertEquals("plain y", labeled.label("y"));
            var declaration = PlainLabelBase.class.getDeclaredMethod("label", String.class);
            assertEquals(List.of(declaration, declaration), AuditInterceptor.METHODS);
        }
    }

    @Test
    @DisplayName("through the build-time client proxy: forwarded, intercepted, and the default body runs")
    void throughTheClientProxy() throws Exception {
        try (var container = boot(ScopedShadowedService.class)) {
            var service = container.select(ScopedShadowedService.class);
            assertEquals(Class.forName(ScopedShadowedService.class.getName() + "_ClientProxy"), service.getClass());
            AuditedHider hider = service;
            assertEquals("default y", hider.hidden("y"));
            assertEquals(List.of(AuditedHider.class.getDeclaredMethod("hidden", String.class)),
                    AuditInterceptor.METHODS);
        }
    }
}
