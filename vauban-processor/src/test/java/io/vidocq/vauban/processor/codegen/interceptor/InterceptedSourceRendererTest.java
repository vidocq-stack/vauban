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
package io.vidocq.vauban.processor.codegen.interceptor;

import io.vidocq.vauban.core.interceptor.CtorShape;
import io.vidocq.vauban.core.interceptor.InterceptedShape;
import io.vidocq.vauban.core.interceptor.MethodShape;
import io.vidocq.vauban.core.interceptor.TypeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the processor's {@code $$Intercepted} source declares for a shadowed default method. */
@DisplayName("InterceptedSourceRenderer — the interface reaching a shadowed default method")
class InterceptedSourceRendererTest {

    @Test
    @DisplayName("lists the interface as the bean parameterises it, and names its erasure in the handle")
    void parameterisedOwner() {
        // A raw `implements p.Labeled` next to the bean's Labeled<String> fails to compile:
        // "Labeled cannot be inherited with different arguments: <> and <java.lang.String>".
        var label = new MethodShape("label", TypeRef.ofReference("java.lang.String", 0),
                List.of(TypeRef.ofReference("java.lang.Object", 0)))
                .withDefaultOwner(TypeRef.ofReference("p.Labeled", 0), "p.Labeled<java.lang.String>");
        var source = InterceptedSourceRenderer.render(
                new InterceptedShape("p.Bean", List.of(new CtorShape(List.of())), List.of(label))).source();
        assertTrue(source.contains("extends p.Bean implements p.Labeled<java.lang.String> {"), source);
        assertTrue(source.contains("$$special(p.Labeled.class, \"label\""), source);
    }
}
