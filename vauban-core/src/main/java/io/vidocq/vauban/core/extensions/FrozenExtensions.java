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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.indexer.model.AnnotationInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the build compatible extensions of a deployment did, written by the build that ran them, so that the
 * container start loads it instead of running them (VAU-PERF-005).
 *
 * <p>The build that sees the whole deployment runs the extension cycle once more, the way the container start does,
 * and writes this record in the application's output: the {@linkplain #fingerprint() fingerprint} of the deployment
 * it ran on, the extensions it ran, those that {@linkplain #startExtensions() run again at start}, what
 * {@code @Discovery} registered, the annotations {@code @Enhancement} added and the synthetic beans and observers.
 * A start whose deployment has that fingerprint takes the record for the cycle; any other start runs the cycle, as
 * it does without a record. The frozen files of each archive, which the annotation processor writes, are read as
 * before: the record only stands for what the start would have run.
 *
 * <p>Three files, side by side: {@value #PATH}, {@value #ENHANCEMENTS_PATH} in the form of
 * {@link EnhancementPatchSerializer} and {@value #SYNTHETIC_PATH} in the form of {@link SyntheticMetadataSerializer}.
 * Sorted and without a date, so that a build stays reproducible.
 *
 * @param fingerprint     the fingerprint of the deployment the extensions ran on
 * @param extensions      the extensions that ran, in their order
 * @param startExtensions those that run again at start, because they asked or because the build named them: what
 *                        they did is not in the record
 * @param discovery       what {@code @Discovery} registered
 * @param enhancements    the annotations {@code @Enhancement} added, by target, as
 *                        {@link EnhancementPatchSerializer#additions} gives them
 * @param synthetic       the synthetic beans and observers, as {@link SyntheticMetadataSerializer} writes them
 */
public record FrozenExtensions(String fingerprint, List<String> extensions, Set<String> startExtensions,
        FrozenExtensions.Discovery discovery, Map<String, List<AnnotationInfo>> enhancements, Properties synthetic) {

    public static final String PATH = "META-INF/vauban-frozen-extensions.properties";
    public static final String ENHANCEMENTS_PATH = "META-INF/vauban-frozen-enhancements.properties";
    public static final String SYNTHETIC_PATH = "META-INF/vauban-frozen-synthetic.properties";

    /** Why a deployment without any build compatible extension has no record. */
    public static final String NO_EXTENSION = "the deployment has no build compatible extension";

    private static final String FORMAT = "1";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String EXTENSION = "extension";
    private static final String START = "start";
    private static final String SCANNED = "scanned";
    private static final String QUALIFIER = "qualifier";
    private static final String BINDING = "binding";
    private static final String STEREOTYPE = "stereotype";
    private static final String NONBINDING = "nonbinding";
    private static final String CONTEXT = "context";

    /**
     * What freezing the extensions of a deployment gave.
     *
     * @param frozen          whether a record was written
     * @param reason          why none was, {@code null} otherwise
     * @param extensions      the extensions the build ran, in their order
     * @param startExtensions those of them that run again at each start
     */
    public record Outcome(boolean frozen, String reason, List<String> extensions, List<String> startExtensions) {

        public Outcome {
            extensions = List.copyOf(extensions);
            startExtensions = List.copyOf(startExtensions);
        }

        /** No record, for {@code reason}: the container start runs the extensions. */
        public static Outcome notFrozen(String reason) {
            return new Outcome(false, reason, List.of(), List.of());
        }

        /** Whether there is no record because the deployment has no extension: nothing a start would run. */
        public boolean nothingToFreeze() {
            return !frozen && NO_EXTENSION.equals(reason);
        }
    }

    /**
     * What the {@code @Discovery} phase registered, by binary name.
     *
     * @param scannedClasses      the classes added through {@code ScannedClasses}
     * @param qualifiers          the annotations made qualifiers
     * @param interceptorBindings the annotations made interceptor bindings
     * @param stereotypes         the annotations made stereotypes, each with the annotations it carries
     * @param nonbindingMembers   the members made non-binding, by annotation
     * @param contexts            the custom contexts
     */
    public record Discovery(List<String> scannedClasses, List<String> qualifiers, List<String> interceptorBindings,
                            Map<String, List<String>> stereotypes, Map<String, List<String>> nonbindingMembers,
                            List<FrozenContexts.Entry> contexts) {

        public static final Discovery NONE = new Discovery(List.of(), List.of(), List.of(), Map.of(), Map.of(),
                List.of());
    }

    /**
     * The record a class loader sees.
     *
     * @param frozen the record, or {@code null} when there is none to use
     * @param note   why a record that exists is not used, {@code null} otherwise
     */
    public record Found(FrozenExtensions frozen, String note) {}

    public FrozenExtensions {
        extensions = List.copyOf(extensions);
        startExtensions = Set.copyOf(startExtensions);
    }

    // ---- reading ----

    /**
     * The record {@code loader} sees. A deployment has one application, so one record: with several, none can be
     * told to be this deployment's and none is used. One file that two loaders of the chain both see, as the boot
     * layer of a jlink image and the layer above it that holds the application again, is one record.
     */
    public static Found find(ClassLoader loader) {
        List<URL> records;
        try {
            var distinct = new LinkedHashMap<String, URL>();
            for (URL url : Collections.list(loader.getResources(PATH))) {
                distinct.putIfAbsent(url.toExternalForm(), url);
            }
            records = List.copyOf(distinct.values());
        } catch (IOException e) {
            return new Found(null, "the record of the frozen extensions cannot be looked up: " + e);
        }
        if (records.isEmpty()) {
            return new Found(null, null);
        }
        if (records.size() > 1) {
            return new Found(null, records.size() + " records of frozen extensions on the path (" + records
                    + "): none can be told to be this deployment's");
        }
        URL location = records.getFirst();
        try {
            return new Found(read(location), null);
        } catch (IOException | RuntimeException e) {
            return new Found(null, "the record of the frozen extensions " + location + " cannot be read: " + e);
        }
    }

    private static FrozenExtensions read(URL location) throws IOException {
        Properties main = load(location);
        if (!FORMAT.equals(main.getProperty(KEY_FORMAT))) {
            throw new IOException("format " + main.getProperty(KEY_FORMAT) + ", this container reads " + FORMAT);
        }
        String fingerprint = main.getProperty(KEY_FINGERPRINT);
        if (fingerprint == null) {
            throw new IOException("no fingerprint");
        }
        var contexts = new ArrayList<FrozenContexts.Entry>();
        for (String line : indexed(main, CONTEXT)) {
            String[] parts = line.split(";");
            if (parts.length != 3) {
                throw new IOException("malformed context " + line);
            }
            contexts.add(new FrozenContexts.Entry(parts[0], parts[1], Boolean.parseBoolean(parts[2])));
        }
        var discovery = new Discovery(indexed(main, SCANNED), indexed(main, QUALIFIER), indexed(main, BINDING),
                keyed(indexed(main, STEREOTYPE)), keyed(indexed(main, NONBINDING)), List.copyOf(contexts));
        Map<String, List<AnnotationInfo>> enhancements;
        try (InputStream in = sibling(location, ENHANCEMENTS_PATH).openStream()) {
            enhancements = EnhancementPatchSerializer.read(in);
        }
        return new FrozenExtensions(fingerprint, indexed(main, EXTENSION), Set.copyOf(indexed(main, START)),
                discovery, enhancements, load(sibling(location, SYNTHETIC_PATH)));
    }

    private static Properties load(URL url) throws IOException {
        var properties = new Properties();
        try (InputStream in = url.openStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    /** The file {@code path} of the archive that holds the record at {@code location}. */
    private static URL sibling(URL location, String path) throws IOException {
        String external = location.toExternalForm();
        if (!external.endsWith(PATH)) {
            throw new IOException("unexpected location " + external);
        }
        return URI.create(external.substring(0, external.length() - PATH.length()) + path).toURL();
    }

    private static List<String> indexed(Properties properties, String prefix) {
        var values = new ArrayList<String>();
        for (int i = 0; ; i++) {
            String value = properties.getProperty(prefix + "." + i);
            if (value == null) {
                return List.copyOf(values);
            }
            values.add(value);
        }
    }

    /** {@code name|a,b} lines as {@code name -> [a, b]}. */
    private static Map<String, List<String>> keyed(List<String> lines) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String line : lines) {
            int bar = line.indexOf('|');
            String rest = bar < 0 ? "" : line.substring(bar + 1);
            result.put(bar < 0 ? line : line.substring(0, bar), rest.isEmpty() ? List.of() : List.of(rest.split(",")));
        }
        return result;
    }

    // ---- writing ----

    /** Writes the record under {@code output}, the root of the application's output; the main file last. */
    public void write(Path output) {
        try {
            Files.createDirectories(output.resolve(PATH).getParent());
            var patch = new ByteArrayOutputStream();
            EnhancementPatchSerializer.write(enhancements, patch);
            Files.write(output.resolve(ENHANCEMENTS_PATH), patch.toByteArray());
            Files.writeString(output.resolve(SYNTHETIC_PATH), sorted(synthetic), StandardCharsets.UTF_8);
            Files.writeString(output.resolve(PATH), mainFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the record of the frozen extensions under " + output, e);
        }
    }

    /** Removes a record from {@code output}: a deployment that is not frozen must not keep a stale one. */
    public static void delete(Path output) {
        try {
            for (String path : List.of(PATH, ENHANCEMENTS_PATH, SYNTHETIC_PATH)) {
                Files.deleteIfExists(output.resolve(path));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot remove the record of the frozen extensions under " + output, e);
        }
    }

    private String mainFile() {
        var out = new StringBuilder("""
                # Vauban frozen extensions: what the build compatible extensions of this deployment did at build.
                # The container start loads it instead of running them when the deployment has this fingerprint.
                """);
        line(out, KEY_FORMAT, FORMAT);
        line(out, KEY_FINGERPRINT, fingerprint);
        lines(out, EXTENSION, extensions);
        lines(out, START, new TreeSet<>(startExtensions));
        lines(out, SCANNED, discovery.scannedClasses());
        lines(out, QUALIFIER, discovery.qualifiers());
        lines(out, BINDING, discovery.interceptorBindings());
        lines(out, STEREOTYPE, unkeyed(discovery.stereotypes()));
        lines(out, NONBINDING, unkeyed(discovery.nonbindingMembers()));
        lines(out, CONTEXT, discovery.contexts().stream().map(FrozenContexts.Entry::line).toList());
        return out.toString();
    }

    private static List<String> unkeyed(Map<String, List<String>> keyed) {
        var lines = new ArrayList<String>();
        keyed.forEach((name, values) -> lines.add(name + "|" + String.join(",", values)));
        return lines;
    }

    private static void lines(StringBuilder out, String prefix, Iterable<String> values) {
        int i = 0;
        for (String value : values) {
            line(out, prefix + "." + i++, value);
        }
    }

    /** One {@code key=value} line; the values are binary names and plain tokens, which need no escape. */
    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value).append('\n');
    }

    /** {@code properties} as text, keys sorted, without the date {@link Properties#store} writes. */
    private static String sorted(Properties properties) throws IOException {
        var out = new StringBuilder("# Vauban frozen extensions: the synthetic beans and observers.\n");
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            var one = new Properties();
            one.setProperty(key, properties.getProperty(key));
            var stored = new java.io.StringWriter();
            one.store(stored, null);
            for (String storedLine : stored.toString().split("\n")) {
                if (!storedLine.startsWith("#")) {
                    out.append(storedLine.strip()).append('\n');
                }
            }
        }
        return out.toString();
    }
}
