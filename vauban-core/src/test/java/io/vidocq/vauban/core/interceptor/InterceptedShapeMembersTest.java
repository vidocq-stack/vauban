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

import io.vidocq.vauban.api.ProxyLink;
import io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator.GeneratedInterceptedClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Which constructors and inherited methods the {@code $$Intercepted} subclass mirrors, and the value semantics of
 * the generated class.
 */
class InterceptedShapeMembersTest {

    @SuppressWarnings("unused")
    public static class Base {
        public void inherited() {
            // intercepted through the subclass
        }

        public void overridden() {
            // overridden below
        }

        public final void fixed() {
            // final: never intercepted
        }
    }

    @SuppressWarnings("unused")
    public static class Bean extends Base {
        private Bean(int hidden) {
        }

        public Bean(ProxyLink link) {
        }

        public Bean(String name) {
        }

        @Override
        public void overridden() {
            // declared here
        }
    }

    @Test
    void privateAndProxyLinkConstructorsAreNotMirrored() {
        var shape = InterceptorSubclassGenerator.fromClass(Bean.class);

        assertEquals(List.of(List.of(TypeRef.ofReference("java.lang.String"))),
                shape.constructors().stream().map(CtorShape::params).toList());
    }

    @Test
    void anInheritedMethodIsMirroredOnceAndAFinalOneNever() {
        var names = InterceptorSubclassGenerator.fromClass(Bean.class).methods().stream().map(MethodShape::name).toList();

        assertEquals(List.of("overridden", "inherited"), names);
    }

    @Test
    void generatedClassesCompareByNameAndBytes() {
        var generated = new GeneratedInterceptedClass("com.acme.Bean$$Intercepted", new byte[] {1, 2});

        assertEquals(new GeneratedInterceptedClass("com.acme.Bean$$Intercepted", new byte[] {1, 2}), generated);
        assertNotEquals(new GeneratedInterceptedClass("com.acme.Other", new byte[] {1, 2}), generated);
        assertNotEquals(new GeneratedInterceptedClass("com.acme.Bean$$Intercepted", new byte[] {3}), generated);
        assertNotEquals(generated, "com.acme.Bean$$Intercepted");
    }
}
