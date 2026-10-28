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
package io.vidocq.vauban.processor.codegen.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.interceptor.TypeRef;
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The source client proxy (vauban#227): a delegate typed by the bean, forwarded without a cast, and a
 * constructor that declares what its super constructor declares, nothing more.
 */
class ClientProxySourceContractTest {

    private static final ClientProxyShape.ProxyMethodShape LOAD = new ClientProxyShape.ProxyMethodShape("load",
            TypeRef.ofReference("java.lang.String"), List.of(TypeRef.ofPrimitive(TypeRef.Primitive.INT)), List.of(), false);

    @Test
    @DisplayName("the delegate is a Supplier of the bean, and each call reaches the bean without a cast")
    void typedDelegate() {
        var source = ClientProxySourceRenderer.render(new ClientProxyShape("app.Store", List.of(), List.of(LOAD)))
                .source();

        assertTrue(source.contains("    private final java.util.function.Supplier<? extends app.Store> $$delegate;\n"),
                source);
        assertTrue(source.contains("    public Store_ClientProxy(java.util.function.Supplier<? extends app.Store> d) {\n"),
                source);
        assertTrue(source.contains("        return $$delegate.get().load(p0);\n"), source);
        assertFalse(source.contains("(app.Store) $$delegate"), source);
    }

    @Test
    @DisplayName("the constructor declares the checked exceptions of the super constructor it calls, not Throwable")
    void superConstructorExceptions() {
        var plain = ClientProxySourceRenderer.render(new ClientProxyShape("app.Store", List.of(), List.of(LOAD)))
                .source();
        assertFalse(plain.contains("Throwable"), plain);

        var throwing = ClientProxySourceRenderer.render(new ClientProxyShape("app.Store",
                List.of(TypeRef.ofReference("java.lang.String")), List.of(LOAD),
                List.of(TypeRef.ofReference("java.io.IOException"),
                        TypeRef.ofReference("java.util.concurrent.TimeoutException")))).source();
        assertTrue(throwing.contains("""
                (java.util.function.Supplier<? extends app.Store> d) \
                throws java.io.IOException, java.util.concurrent.TimeoutException {
                        super((java.lang.String) null);
                """), throwing);
        assertFalse(throwing.contains("Throwable"), throwing);
    }
}
