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
package io.vidocq.vauban.moduleit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start of a container loads few classes of its own making (vauban#347). A lambda, a method reference or a
 * {@code switch} on types is a hidden class the JVM spins when the line first runs, at about twice the cost of
 * loading a class of a jar, and no CDS archive holds it: between 2026-10-28 and 2026-11-18 the start path grew by some
 * 290 classes, more than half of them the container's lambdas, and the start slowed down with them. So the start path
 * is written with loops, and this test holds the count.
 *
 * <p>It starts {@link StartProbe} in a JVM of its own, on the module path: an application layer, then a container,
 * as a Vidocq application starts. It reads the classes that start loaded, and asserts numbers of classes, never a
 * time.
 */
@DisplayName("The start of a container loads few classes of its own making — module path")
class StartLoadsFewClassesModulePathTest {

    /**
     * The hidden classes of Vauban the start of {@link StartProbe} may load: 308 on 2026-11-18 before vauban#347 (278
     * lambdas, 30 {@code switch} on types). Since: 27 lambdas, the callbacks an API asks for (a {@code ScopedValue}
     * call, a stored factory, a {@code computeIfAbsent} on a concurrent map, a sort), and 28 {@code switch} on types.
     * Lower it when the start loses some; a change that needs to raise it adds work to every start: write a loop
     * instead.
     */
    private static final int BUDGET = 55;

    private static final String VAUBAN = "io.vidocq.vauban.";
    /** Where the address of a hidden class starts, in its name. */
    private static final String HIDDEN = "/0x";
    private static final String SOURCE = " source: ";

    @TempDir
    static Path work;

    /** The lines of the {@code class+load} log of the probe's start. */
    private static List<String> loaded;

    @BeforeAll
    static void startTheProbe() throws IOException, InterruptedException {
        Path log = work.resolve("class-load.log");
        Path output = work.resolve("output.txt");
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xlog:class+load=info:file=" + log,
                "-Djava.io.tmpdir=" + work,
                "--module-path", System.getProperty("jdk.module.path"),
                "--module", "io.vidocq.vauban.moduleit/" + StartProbe.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the probe did not end");
        String printed = Files.readString(output);
        assertEquals(0, process.exitValue(), printed);
        assertTrue(printed.contains("layer: com.example.probe.Service, com.example.probe.ApiImpl"), printed);
        assertTrue(printed.contains("started: hello"), printed);
        loaded = Files.readAllLines(log, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the hidden classes hosted by Vauban stay within the budget")
    void theStartStaysWithinItsBudgetOfHiddenClasses() {
        Map<String, Integer> byHost = hiddenClassesByHost(loaded);
        int total = 0;
        var breakdown = new StringBuilder();
        for (var host : byHost.entrySet()) {
            total += host.getValue();
            breakdown.append('\n').append(host.getValue()).append(' ').append(host.getKey());
        }
        assertTrue(total <= BUDGET, "the start loaded " + total + " hidden classes of Vauban, the budget is " + BUDGET
                + ". By host class:" + breakdown);
    }

    /** Without a record of frozen extensions, nothing reads the fingerprint of the deployment: it is not computed. */
    @Test
    @DisplayName("a start without a record computes no checksum of its classes")
    void aStartWithoutARecordComputesNoChecksum() {
        String checksums = "io.vidocq.vauban.core.container.ClassChecksums";
        for (String line : loaded) {
            int at = line.indexOf(checksums);
            assertTrue(at < 0 || line.indexOf(SOURCE) < at, "the start loaded " + checksums + ": " + line);
        }
    }

    /**
     * The number of hidden classes each class of Vauban hosts, by host and kind, in the lines of a {@code class+load}
     * log: a lambda ({@code Host$$Lambda/0x…}) or the dispatcher of a {@code switch} on types
     * ({@code Host$$TypeSwitch/0x…}).
     */
    private static Map<String, Integer> hiddenClassesByHost(List<String> lines) {
        var byHost = new TreeMap<String, Integer>();
        for (String line : lines) {
            int from = line.indexOf(VAUBAN);
            int hidden = line.indexOf(HIDDEN);
            int source = line.indexOf(SOURCE);
            if (from < 0 || hidden < from || source < hidden || line.startsWith(VAUBAN + "moduleit.", from)) {
                continue;
            }
            byHost.merge(line.substring(from, hidden), 1, Integer::sum);
        }
        return byHost;
    }
}
