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
package io.vidocq.vauban.jpmsit;

import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs on the MODULE PATH (this is a named module — surefire picks the module path because
 * {@code module-info.java} exists in the main sources). The container is booted
 * programmatically and beans are obtained via {@link VaubanContainer#select(Class)} —
 * deliberately NOT through {@code @Inject} into the test instance, so nothing reflects into
 * the test class and the module needs no {@code opens}.
 *
 * <p>Each assertion below pins one historical JPMS bug that the class-path test suite could
 * not see (all of them were originally caught by downstream TCKs):</p>
 * <ul>
 *   <li>VAU-INT-001 — overloaded intercepted methods (distinct {@code $$ti$} glues);</li>
 *   <li>VAU-INT-002 — primitive-array parameters and category-2 slot arithmetic;</li>
 *   <li>VAU-INT-003 — checked exceptions through the source-rendered {@code $$Intercepted};</li>
 *   <li>VAU-INT-004 — {@code @Interceptor} bean instantiated in-module (no opens);</li>
 *   <li>VAU-INT-005 — MARKER {@code @InterceptorBinding} detected by name;</li>
 *   <li>VAU-PRX-003 — nested-class types in generated proxy/subclass descriptors;</li>
 *   <li>in-module field injection via the generated provider ({@code putfield}, no opens).</li>
 * </ul>
 */
@DisplayName("Vauban on the module path — historical JPMS bug surface, zero opens")
class ModulePathRegressionTest {

    private static VaubanContainer container;
    private static AuditedService service;

    @BeforeAll
    static void boot() {
        container = VaubanContainer.builder()
                .addBeanClass(AuditInterceptor.class)
                .addBeanClass(Collaborator.class)
                .addBeanClass(AuditedService.class)
                .build();
        service = container.select(AuditedService.class);
    }

    @AfterAll
    static void shutdown() {
        if (container != null) container.close();
    }

    @Test
    @DisplayName("the $$Intercepted subclass and the _ClientProxy are BUILD-time classes")
    void buildTimeArtifactsExist() throws Exception {
        assertNotNull(Class.forName("io.vidocq.vauban.jpmsit.AuditedService$$Intercepted"),
                "the APT must have generated AuditedService$$Intercepted at build time");
        assertNotNull(Class.forName("io.vidocq.vauban.jpmsit.AuditedService_ClientProxy"),
                "the APT must have generated AuditedService_ClientProxy at build time");
    }

    @Test
    @DisplayName("VAU-INT-001 — each overload is intercepted through its own glue")
    void overloadsAreInterceptedDistinctly() {
        AuditInterceptor.CALLS.clear();
        assertEquals("w0", service.work());
        assertEquals("w7", service.work(7));
        assertEquals("wx", service.work("x"));
        assertEquals(java.util.List.of("work/0", "work/1", "work/1"), AuditInterceptor.CALLS,
                "every overload must be routed through the interceptor (VAU-INT-004/005) "
                        + "to its own $$ti$ glue (VAU-INT-001)");
    }

    @Test
    @DisplayName("VAU-INT-002 — primitive arrays and category-2 params survive the glue")
    void primitiveArraysAndCategory2() {
        assertArrayEquals(new int[] {2, 4, 6}, service.doubled(new int[] {1, 2, 3}));
        assertEquals(1_000L + 1 + 2 + 3, service.weightedSum(1_000L, new int[] {1, 2, 3}, 1.0d));
    }

    @Test
    @DisplayName("VAU-INT-003 — a checked exception propagates unwrapped through interception")
    void checkedExceptionPropagatesUnwrapped() throws Exception {
        assertEquals("ok", service.risky(false));
        IOException thrown = assertThrows(IOException.class, () -> service.risky(true),
                "the original IOException must cross the chain unwrapped (sneaky rethrow)");
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    @DisplayName("VAU-PRX-003 — nested-class return/parameter types work through proxy + subclass")
    void nestedClassTypes() {
        assertEquals("in!", service.nested(new Outer.Inner("in")).value());
    }

    @Test
    @DisplayName("field injection happens in-module via the generated provider (no opens)")
    void fieldInjectionInModule() {
        assertEquals("hello", service.viaCollaborator());
    }

    @Test
    @DisplayName("sanity — this test really runs on the module path in a named module")
    void runsOnTheModulePath() {
        Module module = AuditedService.class.getModule();
        assertTrue(module.isNamed(), "fixtures must live in a named module, not the unnamed one");
        assertEquals("io.vidocq.vauban.jpmsit", module.getName());
        assertTrue(module.getDescriptor().opens().isEmpty(),
                "the whole point: ZERO opens directives in this module");
    }
}
