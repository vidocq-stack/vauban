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
package io.vidocq.vauban.naming;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.core.container.VaubanContainer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The declared names are checked when the container starts, as an injection point is: a wrong declaration fails
 * the start, naming the name, rather than the first lookup of it.
 */
class DeclarationValidationTest {

    private static VaubanContainer start(Class<?>... beans) {
        var builder = VaubanContainer.builder().addBeanClass(DefaultNamingService.class);
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        return builder.build();
    }

    /** Every message of the failure and its causes, joined. */
    private static String startFails(Class<?>... beans) {
        var failure = assertThrows(RuntimeException.class, () -> start(beans).close());
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return String.join(" / ", messages);
    }

    private static void assertMentions(String message, String... parts) {
        for (String part : parts) {
            assertTrue(message.contains(part), "'" + part + "' missing from: " + message);
        }
    }

    @Test
    void validDeclarationsStart() {
        assertDoesNotThrow(() -> start(OrdersDataSource.class, OrdersNames.class, LinkNames.class).close());
    }

    @Test
    void aNameDeclaredTwiceFailsTheStart() {
        assertMentions(startFails(OrdersDataSource.class, OrdersNames.class, RivalOrdersNames.class),
                "java:comp/env/jdbc/orders", "declared twice", OrdersNames.class.getName(),
                RivalOrdersNames.class.getName());
    }

    @Test
    void anUnsatisfiedBeanFailsTheStart() {
        assertMentions(startFails(OrdersNames.class), "java:comp/env/jdbc/orders", "@Named", "no bean");
    }

    @Test
    void anAmbiguousBeanFailsTheStart() {
        assertMentions(startFails(OrdersDataSource.class, DefaultDataSource.class, AnyDataSourceNames.class),
                "java:comp/env/jdbc/any", "several beans");
    }

    @Test
    void aDependentBeanFailsTheStart() {
        assertMentions(startFails(DependentDataSource.class, ScratchNames.class), "java:comp/env/jdbc/scratch",
                "@Dependent");
    }

    @Test
    void aLinkToAnUndeclaredNameFailsTheStart() {
        assertMentions(startFails(LinkNames.class), "java:comp/env/jdbc/legacy", "java:comp/env/jdbc/orders",
                "not declared");
    }

    @Test
    void aLoopOfLinksFailsTheStart() {
        assertMentions(startFails(LoopNames.class), "java:comp/env/a", "java:comp/env/b", "loop");
    }

    @Test
    void aNonQualifierAnnotationFailsTheStart() {
        assertMentions(startFails(DefaultDataSource.class, NotAQualifierNames.class), "java:comp/env/jdbc/odd",
                NotAQualifier.class.getName(), "not a qualifier");
    }

    @Test
    void everyWrongDeclarationIsReportedAtOnce() {
        assertMentions(startFails(OrdersNames.class, LoopNames.class), "java:comp/env/jdbc/orders",
                "java:comp/env/a");
    }
}
