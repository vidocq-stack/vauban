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
package io.vidocq.vauban.core.interceptor;

import io.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.Stereotype;
import jakarta.interceptor.InterceptorBinding;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corners of interceptor binding resolution in a manager built without deployment metadata: unknown binding
 * names, interceptors that declare no binding, members that cannot be read, and bindings inherited from a
 * superclass.
 */
class InterceptorManagerBindingEdgesTest {

    private static final DotName LEVEL = DotName.of(Level.class.getName());

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Level {
        String value();
    }

    @Stereotype
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Role {
    }

    @Level("base")
    @Role
    public static class Base {
    }

    public static class Child extends Base {
    }

    @Level("high")
    private static final class Carrier {
    }

    /** A hand-written {@link Level} whose member cannot be read. */
    @SuppressWarnings("ClassExplicitlyAnnotation")
    private static final class UnreadableLevel implements Level {
        @Override
        public String value() {
            throw new UnsupportedOperationException("unreadable");
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Level.class;
        }
    }

    private static InterceptorDescriptor interceptor(String name, Set<DotName> bindings, List<Annotation> instances) {
        return new InterceptorDescriptor(DotName.of(name), bindings, "around", null, 1, true, instances);
    }

    @Test
    void aBindingNameNoLoaderKnowsIsNoBinding() {
        var manager = new InterceptorManager(List.of());

        assertFalse(manager.isInterceptorBinding(DotName.of("com.acme.Missing")));
        assertTrue(manager.isInterceptorBinding(LEVEL));
    }

    @Test
    void anInterceptorDeclaringNoBindingOrWhoseMembersCannotBeComparedIsNotResolved() {
        var high = Carrier.class.getAnnotation(Level.class);
        var manager = new InterceptorManager(List.of(
                interceptor("com.acme.Unbound", Set.of(), List.of()),
                interceptor("com.acme.High", Set.of(LEVEL), List.of(high))));

        assertEquals(List.of(), manager.resolveInterceptors(List.<Annotation>of(new UnreadableLevel())));
        assertEquals(List.of("com.acme.High"), manager.resolveInterceptors(List.<Annotation>of(high)).stream()
                .map(d -> d.interceptorClass().value()).toList());
    }

    @Test
    void aSuperclassBindingIsInheritedOnlyWhenInheritedOrAStereotype() {
        var manager = new InterceptorManager(List.of());

        assertEquals(Set.of(DotName.of(Role.class.getName())), manager.findBindingsOnClass(Child.class, name -> true));
        assertEquals(Set.of(LEVEL, DotName.of(Role.class.getName())), manager.findBindingsOnClass(Base.class, name -> true));
    }
}
