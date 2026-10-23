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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Stereotype;
import jakarta.inject.Named;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stereotypes and interceptors the index describes only in part: stereotypes read by reflection, transitively, some
 * of them indexed and one that cannot be loaded at all; interceptors whose {@code @Priority}, bindings or around
 * methods only reflection reveals.
 */
@DisplayName("StereotypeResolver and InterceptorDiscovery: what reflection adds to the index")
class StereotypeAndInterceptorReflectionTest {

    private static final String MISSING = "io.vidocq.vauban.core.bean.discovery.NoSuchStereotype";

    @Stereotype
    @Alternative
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AlternativeStereotype {
    }

    @Stereotype
    @AlternativeStereotype
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NestedAlternative {
    }

    @Stereotype
    @Named
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NamedStereotype {
    }

    @Stereotype
    @NamedStereotype
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NestedNamed {
    }

    @Stereotype
    @ApplicationScoped
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ScopedStereotype {
    }

    @Stereotype
    @ScopedStereotype
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NestedScoped {
    }

    @Stereotype
    @Priority(5)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface PrioritizedStereotype {
    }

    @Stereotype
    @PrioritizedStereotype
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NestedPrioritized {
    }

    @NestedAlternative
    public static class AlternativeBean {
    }

    @NestedNamed
    public static class NamedBean {
    }

    @NestedScoped
    public static class ScopedBean {
    }

    @Alternative
    @NestedPrioritized
    public static class PrioritizedBean {
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Watched {
    }

    public static class BaseInterceptor {
        @AroundInvoke
        public Object inherited(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Watched
    @Alternative
    @Deprecated
    @Interceptor
    @Priority(42)
    public static class WatchInterceptor extends BaseInterceptor {
        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    private static ClassInfo scan(Class<?> type) {
        var resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return ClassFileScanner.scan(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BeanDiscovery discovery(ClassInfo... classes) {
        var builder = new IndexBuilder();
        for (var info : classes) {
            builder.add(info);
        }
        return new BeanDiscovery(builder.build());
    }

    @Test
    @DisplayName("a stereotype read by reflection makes a bean an alternative, named, scoped or prioritized, transitively")
    void reflectedStereotypes() {
        var discovery = discovery(scan(AlternativeBean.class), scan(NamedBean.class), scan(ScopedBean.class),
                scan(PrioritizedBean.class), scan(NamedStereotype.class));

        assertTrue(discovery.buildManagedBean(scan(AlternativeBean.class)).isAlternative());
        assertEquals("namedBean", discovery.buildManagedBean(scan(NamedBean.class)).name());
        assertEquals(ScopeInfo.APPLICATION, discovery.buildManagedBean(scan(ScopedBean.class)).scope());
        assertEquals(5, discovery.buildManagedBean(scan(PrioritizedBean.class)).priority());
    }

    @Test
    @DisplayName("a custom stereotype that cannot be loaded adds nothing to the bean")
    void unloadableStereotype() {
        var bean = new ClassInfo(DotName.of(NamedBean.class.getName()), DotName.of("java.lang.Object"), List.of(),
                0x0001, List.of(), List.of(), List.of(new AnnotationInfo(DotName.of(Dependent.class.getName()), Map.of()),
                new AnnotationInfo(DotName.of(MISSING), Map.of()),
                new AnnotationInfo(DotName.of(MISSING + "Other"), Map.of())), ClassInfo.ClassKind.CLASS);
        var discovery = discovery(bean);
        discovery.setCustomStereotypes(Set.of(DotName.of(MISSING)));

        var descriptor = discovery.buildManagedBean(bean);

        assertFalse(descriptor.isAlternative());
        assertNull(descriptor.name());
        assertEquals(ScopeInfo.DEPENDENT, descriptor.scope());
    }

    @Test
    @DisplayName("an interceptor's @Priority, around methods and extension bindings missing from the index are read by reflection")
    void reflectedInterceptor() {
        var indexed = scan(WatchInterceptor.class);
        var partial = new ClassInfo(indexed.name(), null, List.of(), indexed.accessFlags(), List.of(), List.of(),
                List.of(new AnnotationInfo(DotName.of(Interceptor.class.getName()), Map.of()),
                        new AnnotationInfo(DotName.of(Watched.class.getName()), Map.of())),
                ClassInfo.ClassKind.CLASS);
        var discovery = discovery(partial);
        discovery.setCustomInterceptorBindings(Set.of(DotName.of(Alternative.class.getName()),
                DotName.of(Deprecated.class.getName()), DotName.of(Interceptor.class.getName())));

        var interceptors = discovery.discoverInterceptors();

        assertEquals(1, interceptors.size(), interceptors::toString);
        var interceptor = interceptors.getFirst();
        assertEquals(42, interceptor.priority());
        assertTrue(interceptor.enabled());
        assertEquals("around", interceptor.aroundInvokeMethod());
        assertNull(interceptor.aroundConstructMethod());
        assertTrue(interceptor.bindings().contains(DotName.of(Watched.class.getName())), interceptor::toString);
        assertTrue(interceptor.bindings().contains(DotName.of(Deprecated.class.getName())),
                "a binding an extension declared is read by reflection");
        assertFalse(interceptor.bindings().contains(DotName.of(Alternative.class.getName())),
                "a built-in CDI annotation read by reflection is never a binding");
    }
}
