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

import io.vidocq.vauban.core.interceptor.fixtures.GoldenBean;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ONE-SHOT dumper: writes the golden {@code .class} snapshots to
 * {@code src/test/resources/golden-intercepted/<simpleName>.class}.
 *
 * <p>This test is {@link Disabled} by default and must be run manually once
 * to (re-)generate the golden files whenever the emitter changes intentionally.
 * After running, commit the updated {@code .class} files and re-enable
 * {@link GoldenBytecodeTest} to enforce byte-for-byte equivalence.
 *
 * <p>Run with: {@code ./mvnw -pl vauban-core -Dtest=GoldenSnapshotDumper#dumpGoldenSnapshots test}
 */
@Disabled("One-shot golden snapshot writer — enable locally to regenerate .class files")
@DisplayName("Golden snapshot dumper (one-shot)")
class GoldenSnapshotDumper {

    @Test
    @DisplayName("writes GoldenBean$$Intercepted.class to src/test/resources/golden-intercepted/")
    void dumpGoldenSnapshots() throws Exception {
        Class<?>[] fixtures = {GoldenBean.class};

        // Locate the test-resources directory relative to this class's source location.
        // We look for the resources dir starting from the module root.
        Path resourcesDir = resolveGoldenDir();
        Files.createDirectories(resourcesDir);

        for (Class<?> fixture : fixtures) {
            var generated = InterceptorSubclassGenerator.generate(fixture);
            String simpleName = fixture.getSimpleName() + "$$Intercepted";
            Path out = resourcesDir.resolve(simpleName + ".class");
            Files.write(out, generated.bytecode());
            System.out.println("Wrote " + out + " (" + generated.bytecode().length + " bytes)");
        }
    }

    private static Path resolveGoldenDir() throws Exception {
        // Walk up from the test .class location to find vauban-core module root
        URL classUrl = GoldenSnapshotDumper.class.getProtectionDomain().getCodeSource().getLocation();
        Path testClasses = Paths.get(classUrl.toURI());
        // testClasses = .../vauban-core/target/test-classes
        Path moduleRoot = testClasses.getParent().getParent();
        return moduleRoot.resolve("src/test/resources/golden-intercepted");
    }
}
