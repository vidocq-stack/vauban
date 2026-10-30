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

import io.vidocq.vauban.processor.VaubanProcessor;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Build Compatible Extension that logs while Vauban runs it at build time is heard through javac's messages, one
 * line each in Maven's output, instead of java.util.logging's two-line format in the machine's language
 * (BUG-20261030-26 of vidocq: Cassini's scope extension at every build).
 */
class BceLoggingTest {

    @TempDir
    Path workDir;

    /** Logs one INFO and one WARNING line for each {@code @Named} class, as Cassini's scope extension does. */
    public static class LoggingBce implements BuildCompatibleExtension {
        private static final System.Logger LOG = System.getLogger(LoggingBce.class.getName());

        @Enhancement(types = Object.class, withAnnotations = jakarta.inject.Named.class)
        public void log(ClassConfig clazz) {
            LOG.log(System.Logger.Level.INFO, "{0} has no CDI scope, defaulting to @Dependent", clazz.info().name());
            LOG.log(System.Logger.Level.WARNING, "{0} looks odd", clazz.info().name());
        }
    }

    @Test
    void whatAnExtensionLogsAtBuildTimeIsAJavacMessage() throws IOException {
        var processor = new VaubanProcessor();
        processor.overrideBceClasses(List.of(LoggingBce.class));

        var result = ProcessorCompiler.compile(workDir, Map.of("app/Greeter.java", """
                package app;

                @jakarta.inject.Named
                @jakarta.enterprise.context.ApplicationScoped
                public class Greeter {
                }
                """), List.of(), processor);

        var messages = String.join("\n", result.messages());
        assertTrue(result.success(), messages);
        assertTrue(result.mentions("NOTE: [Vauban BCE] app.Greeter has no CDI scope, defaulting to @Dependent"),
                messages);
        assertTrue(result.mentions("WARNING: [Vauban BCE] app.Greeter looks odd"), messages);
    }
}
