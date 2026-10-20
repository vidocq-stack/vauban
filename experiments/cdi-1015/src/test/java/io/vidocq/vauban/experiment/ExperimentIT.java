/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package io.vidocq.vauban.experiment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Maven builds the fixtures; this test only launches and checks their isolated JVMs. */
class ExperimentIT {
    private static final Path TARGET = Path.of(System.getProperty("experiment.directory"), "target");
    private static final List<String> SCENARIOS = List.of(
            "external-authorized", "external-unauthorized", "external-closed");
    private static final String CDI_JAR = "jakarta.enterprise.jakarta.enterprise.cdi-api-4.1.0.jar";
    private static final String CDI_SHA256 = "c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699";

    @BeforeAll
    static void recordInputs() throws Exception {
        // Snapshots can change; record the exact jars used by this run.
        Files.createDirectories(TARGET.resolve("logs"));
        List<String> inventory = new ArrayList<>();
        inventory.add("Java: " + System.getProperty("java.runtime.version"));
        inventory.add("Each line records SHA-256 and a path relative to target/.");
        for (String directory : List.of("dependencies", "runtime-deps", "library-api", "libraries")) {
            try (Stream<Path> jars = Files.list(TARGET.resolve(directory))) {
                for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                    inventory.add(sha256(jar) + "  " + TARGET.relativize(jar));
                }
            }
        }
        Files.write(TARGET.resolve("dependency-sha256.txt"), inventory);
    }

    static Stream<String> scenarios() {
        String selected = System.getProperty("scenario", "all");
        if (!selected.equals("all") && !SCENARIOS.contains(selected)) {
            throw new IllegalArgumentException("Unknown scenario: " + selected + "; choose " + SCENARIOS);
        }
        return SCENARIOS.stream().filter(name -> selected.equals("all") || selected.equals(name));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void runScenario(String scenario) throws Exception {
        String variant = scenario.equals("external-closed") ? "closed" : "relay";
        Path externalJar = TARGET.resolve("libraries/external-" + variant + ".jar");
        String originalLibraryHash = sha256(externalJar);
        // Openings outlive containers, so each scenario needs a fresh JVM.
        // The application loads the independent jar and generates proxies at runtime.
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-ea", "-XX:+DisableAttachMechanism", "--module-path",
                modulePath("runtime-deps", TARGET.relativize(externalJar).toString(), "modules/external-app"),
                "--module", "experiment.external.app/experiment.external.app.Main", scenario);

        Path log = TARGET.resolve("logs/" + scenario + ".log");
        Files.writeString(log, String.join(" ", command) + System.lineSeparator() + System.lineSeparator());
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        // Inherited launcher settings must not silently change the access being tested.
        List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")
                .forEach(builder.environment()::remove);
        Process child = builder.start();
        boolean finished = child.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
        assertTrue(finished, () -> scenario + " timed out; see " + log);
        String output = Files.readString(log);
        assertEquals(0, child.exitValue(), () -> scenario + " failed:\n" + output);
        // The child prints PASS only after checking its expected success or access refusal.
        String marker = "PASS " + scenario;
        assertTrue(output.lines().anyMatch(line -> line.equals(marker) || line.startsWith(marker + ":")),
                () -> "Missing scenario success marker:\n" + output);
        // Runtime class generation must leave the library jar untouched.
        assertEquals(originalLibraryHash, sha256(externalJar), "The external library jar was modified");
    }

    @Test
    void experimentalJarsPreserveEveryOriginalEntry() throws Exception {
        assertEquals(CDI_SHA256, sha256(TARGET.resolve("dependencies").resolve(CDI_JAR)),
                "The CDI input must be the released 4.1.0 binary");
        checkEntries(CDI_JAR);
        checkEntries("io.vidocq.vauban.vauban-core-" + System.getProperty("vauban.version") + ".jar");
    }

    @Test
    void externalLibraryContainsOnlyStandardApiDependenciesAndOriginalClasses() throws Exception {
        for (String variant : List.of("relay", "closed")) {
            Path jar = TARGET.resolve("libraries/external-" + variant + ".jar");
            var descriptor = ModuleFinder.of(jar).find("experiment.external.beans").orElseThrow().descriptor();
            assertEquals(Set.of("java.base", "jakarta.cdi", "jakarta.inject", "microprofile.fault.tolerance.api"),
                    descriptor.requires().stream().map(r -> r.name()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(descriptor.provides().isEmpty(), "External library must not register container services");
            try (var archive = new JarFile(jar.toFile())) {
                assertEquals(Set.of("module-info.class", "experiment/external/beans/FallbackService.class",
                                "experiment/external/beans/FallbackState.class"),
                        archive.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class"))
                                .collect(java.util.stream.Collectors.toSet()),
                        "No generated provider, proxy or helper belongs in the published library");
            }
        }
        try (var apis = Files.list(TARGET.resolve("library-api"))) {
            assertTrue(apis.allMatch(path -> path.getFileName().toString().startsWith("jakarta.")
                            || path.getFileName().toString().equals("microprofile-fault-tolerance-api-4.1.jar")),
                    "The external library compiler path must contain only standard API jars");
        }
    }

    private static void checkEntries(String filename) throws IOException {
        // Helpers may be added; original classes and module descriptors must stay identical.
        try (JarFile original = new JarFile(TARGET.resolve("dependencies").resolve(filename).toFile());
             JarFile extended = new JarFile(TARGET.resolve("runtime-deps").resolve(filename).toFile())) {
            for (var entry : original.stream().toList()) {
                var copy = extended.getJarEntry(entry.getName());
                assertNotNull(copy, () -> filename + " lost " + entry.getName());
                try (var before = original.getInputStream(entry); var after = extended.getInputStream(copy)) {
                    assertArrayEquals(before.readAllBytes(), after.readAllBytes(),
                            () -> filename + " changed " + entry.getName());
                }
            }
            List<String> additions = extended.stream().map(e -> e.getName())
                    .filter(name -> original.getJarEntry(name) == null).toList();
            assertTrue(additions.stream().anyMatch(name -> name.endsWith(".class")),
                    () -> filename + " has no experimental classes");
            assertTrue(additions.stream().allMatch(name -> name.endsWith(".class") || name.endsWith("/")),
                    () -> filename + " has unexpected additions: " + additions);
        }
    }

    private static String modulePath(String... entries) {
        return String.join(File.pathSeparator,
                Stream.of(entries).map(entry -> TARGET.resolve(entry).toString()).toList());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
