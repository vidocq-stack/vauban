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
package io.vidocq.vauban.processor.apt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The note that tells a module to declare its generated provider (VAU-APT-004). A runtime that turns the
 * module's {@code META-INF/services} entries into {@code provides} when it loads the module, as Vidocq does,
 * needs no such declaration: it turns the advice off with {@code -Avauban.providesHint=false}.
 */
class ProvidesHintTest {

    private static final Map<String, String> BEAN = Map.of("app/Greeter.java", """
            package app;

            @jakarta.enterprise.context.ApplicationScoped
            public class Greeter {
            }
            """);

    private static final String ADVICE =
            "add to module-info: provides io.vidocq.vauban.api.VaubanComponentProvider with app._VaubanComponents;";

    @Test
    void byDefaultTheNoteTellsTheModuleToDeclareItsProvider(@TempDir Path dir) throws IOException {
        var result = ProcessorCompiler.compile(dir, BEAN);

        assertTrue(result.success(), result.messages().toString());
        assertTrue(result.mentions(ADVICE), result.messages().toString());
    }

    @Test
    void theOptionTurnsTheAdviceOffAndKeepsTheNote(@TempDir Path dir) throws IOException {
        var result = ProcessorCompiler.compile(dir, BEAN, List.of("-Avauban.providesHint=false"));

        assertTrue(result.success(), result.messages().toString());
        assertTrue(result.mentions("[Vauban] Generated app._VaubanComponents (1 component(s)"),
                result.messages().toString());
        assertFalse(result.mentions("add to module-info"), result.messages().toString());
        assertFalse(result.mentions("not recognized"), "the processor declares the option: "
                + result.messages());
    }
}
