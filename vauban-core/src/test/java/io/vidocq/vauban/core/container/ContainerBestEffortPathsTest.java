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

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.api.ModuleLookupGrant;
import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Best-effort paths of the container: component providers whose service declaration is broken or that refuse to
 * grant their lookup, interceptor binding types that cannot be loaded, and destroying a request-scoped instance once
 * its context is no longer active.
 */
@DisplayName("Container best-effort paths: providers, interceptor bindings and inactive contexts")
class ContainerBestEffortPathsTest {

    private static final String SERVICE = "META-INF/services/" + VaubanComponentProvider.class.getName();

    /** Named as a generated main provider; refuses to grant its lookup. */
    public static final class _VaubanComponents implements VaubanComponentProvider {
        @Override
        public Object create(String className) {
            return null;
        }

        @Override
        public void grantModuleLookup(ModuleLookupGrant grant) {
            throw new IllegalStateException("no lookup granted");
        }
    }

    /** Serves a service declaration naming a provider that does not exist. */
    private static final class BrokenServiceLoader extends ClassLoader {
        private final URL declaration;

        BrokenServiceLoader(URL declaration) {
            super(ContainerBestEffortPathsTest.class.getClassLoader());
            this.declaration = declaration;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return name.equals(SERVICE) ? Collections.enumeration(List.of(declaration)) : super.getResources(name);
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audited {
    }

    @Audited
    @Interceptor
    public static class AuditInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @RequestScoped
    public static class RequestData {
        public String id() {
            return "request";
        }
    }

    @ApplicationScoped
    public static class Shared {
        public String id() {
            return "shared";
        }
    }

    @Test
    @DisplayName("a broken provider declaration is skipped, the explicit providers kept")
    void brokenServiceDeclaration(@TempDir Path dir) throws IOException {
        var declaration = Files.writeString(dir.resolve("providers"), "com.example.MissingProvider\n");
        var explicit = new _VaubanComponents();

        var providers = ComponentProviders.load(new BrokenServiceLoader(declaration.toUri().toURL()), List.of(explicit));

        assertSame(explicit, providers.providers().getFirst());
    }

    @Test
    @DisplayName("a main provider that refuses its lookup gets no test companion")
    void providerRefusingItsLookup() {
        var provider = new _VaubanComponents();

        assertEquals(List.of(provider), ComponentProviders.withTestCompanions(List.of(provider)));
    }

    @Test
    @DisplayName("an interceptor binding type that cannot be loaded is not one of the interceptor's bindings")
    void unloadableBindingType() {
        var descriptor = new InterceptorDescriptor(DotName.of(AuditInterceptor.class.getName()),
                Set.of(DotName.of(Audited.class.getName()), DotName.of("com.example.MissingBinding")), "around", 10);

        var bindings = new VaubanInterceptor<>(descriptor).getInterceptorBindings();

        assertEquals(1, bindings.size(), bindings::toString);
        assertEquals(Audited.class, bindings.iterator().next().annotationType());
    }

    @Test
    @DisplayName("destroying a request-scoped instance after its request ended is a no-op, through Instance or a handle")
    void destroyAfterTheRequestEnded() {
        try (var container = VaubanContainer.builder().addBeanClass(RequestData.class).build()) {
            var instance = container.getBeanManager().createInstance().select(RequestData.class);
            var request = container.requestContext();
            request.activate();
            var data = instance.get();
            var handle = instance.getHandle();
            handle.get();
            assertEquals("request", data.id());
            request.deactivate();

            instance.destroy(data);
            handle.destroy();

            assertTrue(container.isRunning());
        }
    }

    @Test
    @DisplayName("iterating an Instance of a normal-scoped bean tracks no dependent instance")
    void iterateNormalScopedInstance() {
        try (var container = VaubanContainer.builder().addBeanClass(Shared.class).build()) {
            var instance = container.getBeanManager().createInstance().select(Shared.class);

            var ids = new java.util.ArrayList<String>();
            instance.forEach(shared -> ids.add(shared.id()));

            assertEquals(List.of("shared"), ids);
        }
    }
}
