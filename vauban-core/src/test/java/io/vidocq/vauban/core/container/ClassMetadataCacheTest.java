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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the container reads off a class on the path that serves a request — its annotations, whether a type is an
 * interceptor binding or an interceptor, the annotations on its methods and constructors, the generic type of a
 * field, an interceptor's {@code @PostConstruct} — is read once per class and reused, not read again by reflection at
 * each creation of a request-scoped or dependent bean (VAU-PERF-009).
 */
class ClassMetadataCacheTest {

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @interface Bound {
    }

    @Bound
    @Retention(RetentionPolicy.RUNTIME)
    @interface Meta {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Plain {
    }

    @Meta
    @Plain
    static class Service {
        @Inject List<String> names;

        @Inject
        Service() {
        }

        @Bound
        void work() {
        }

        @Plain
        void other() {
        }
    }

    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    @Bound
    static class SomeInterceptor {
        @PostConstruct
        void init() {
        }

        void notACallback() {
        }
    }

    @Test
    void theAnnotationsOfAClassAreReadOnceAndAreUnmodifiable() {
        var first = ClassMetadataCache.annotationsOf(Service.class);

        assertSame(first, ClassMetadataCache.annotationsOf(Service.class));
        assertEquals(Set.of(Meta.class, Plain.class),
                Set.copyOf(first.stream().map(a -> (Class<?>) a.annotationType()).toList()));
        assertThrows(UnsupportedOperationException.class, () -> first.add(null));
    }

    @Test
    void anInterceptorBindingTypeIsRecognizedByItsDeclaration() {
        assertTrue(ClassMetadataCache.isDeclaredInterceptorBinding(Bound.class));
        assertTrue(ClassMetadataCache.isDeclaredInterceptorBinding(Bound.class));
        assertFalse(ClassMetadataCache.isDeclaredInterceptorBinding(Plain.class));
        assertFalse(ClassMetadataCache.isDeclaredInterceptorBinding(Meta.class),
                "a meta-annotation carrying a binding is not a binding itself");
    }

    @Test
    void aQualifierTypeIsRecognizedByItsDeclaration() {
        assertTrue(ClassMetadataCache.isDeclaredQualifier(jakarta.inject.Named.class));
        assertTrue(ClassMetadataCache.isDeclaredQualifier(jakarta.inject.Named.class));
        assertFalse(ClassMetadataCache.isDeclaredQualifier(Plain.class));
    }

    @Test
    void anInterceptorClassIsRecognizedByItsAnnotation() {
        assertTrue(ClassMetadataCache.isInterceptorClass(SomeInterceptor.class));
        assertFalse(ClassMetadataCache.isInterceptorClass(Service.class));
    }

    @Test
    void theAnnotationsOnTheDeclaredMethodsAreReadOnce() {
        var first = ClassMetadataCache.methodAnnotationsOf(Service.class);

        assertSame(first, ClassMetadataCache.methodAnnotationsOf(Service.class));
        assertEquals(Set.of(Bound.class, Plain.class),
                Set.copyOf(first.stream().map(a -> (Class<?>) a.annotationType()).toList()));
        assertThrows(UnsupportedOperationException.class, () -> first.add(null));
    }

    @Test
    void theDeclaredConstructorsAreReadOnce() {
        var first = ClassMetadataCache.constructorsOf(Service.class);

        assertSame(first, ClassMetadataCache.constructorsOf(Service.class));
        assertEquals(1, first.size());
        assertThrows(UnsupportedOperationException.class, () -> first.add(null));
    }

    @Test
    void theAnnotationsOfAConstructorAreReadOnce() throws NoSuchMethodException {
        var constructor = Service.class.getDeclaredConstructor();
        var first = ClassMetadataCache.annotationsOf(constructor);

        assertSame(first, ClassMetadataCache.annotationsOf(constructor));
        assertEquals(List.of(Inject.class), first.stream().<Class<?>>map(a -> a.annotationType()).toList());
    }

    @Test
    void theGenericTypeOfAFieldIsReadOnce() throws NoSuchFieldException {
        var field = Service.class.getDeclaredField("names");
        Type first = ClassMetadataCache.genericTypeOf(field);

        assertSame(first, ClassMetadataCache.genericTypeOf(field));
        assertEquals(field.getGenericType(), first);
    }

    @Test
    void anInterceptorsPostConstructIsFoundOnceAndOnlyWhenItHasOne() {
        var callback = ClassMetadataCache.interceptorPostConstruct(SomeInterceptor.class);

        assertTrue(callback.isPresent());
        assertEquals("init", callback.get().getName());
        assertSame(callback, ClassMetadataCache.interceptorPostConstruct(SomeInterceptor.class));
        assertTrue(ClassMetadataCache.interceptorPostConstruct(Service.class).isEmpty());
    }
}
