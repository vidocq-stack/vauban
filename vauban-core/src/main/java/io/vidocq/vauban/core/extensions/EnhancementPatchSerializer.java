package io.vidocq.vauban.core.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Serializes and deserializes the <em>result</em> of {@code @Enhancement} phases — a patch
 * mapping each target class to the fully-qualified names of the annotations the BCE added.
 *
 * <p>The runtime only needs the added-annotation FQNs (it applies them as
 * {@code new AnnotationInfo(DotName.of(fqn), Map.of())}). Persisting this patch at build
 * time lets the container apply the enhancement <strong>without re-instantiating the
 * BCE</strong> on the module path — which is what previously forced application modules to
 * {@code opens <pkg> to io.vidocq.vauban.core}.
 *
 * <p>Zero external dependencies — JDK only. The written form is sorted and timestamp-free
 * so builds stay reproducible (AOT/CDS friendly).
 *
 * <h2>Format</h2>
 * <pre>
 * # one line per enhanced class: &lt;target FQN&gt;=&lt;added annotation FQNs, comma-separated&gt;
 * com.example.HelloResource=jakarta.enterprise.context.RequestScoped
 * com.example.Repo$Nested=jakarta.enterprise.context.ApplicationScoped,jakarta.inject.Singleton
 * </pre>
 */
public final class EnhancementPatchSerializer {

    public static final String PATCH_PATH = "META-INF/vauban-enhancements.properties";

    private EnhancementPatchSerializer() {}

    /**
     * Writes the patch as sorted {@code target=annFqn,annFqn} lines. Targets with no added
     * annotation are skipped. Class FQNs contain no Properties-special characters, so the
     * keys/values are written verbatim.
     */
    public static void write(Map<String, List<String>> patch, OutputStream os) throws IOException {
        Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        writer.write("# Vauban @Enhancement patch — target FQN = added annotation FQNs (CSV)\n");
        writer.write("# Generated at build time; applied at runtime without re-instantiating the BCE.\n");
        for (var entry : new TreeMap<>(patch).entrySet()) {
            var annotations = entry.getValue();
            if (annotations == null || annotations.isEmpty()) continue;
            writer.write(entry.getKey());
            writer.write('=');
            writer.write(String.join(",", annotations));
            writer.write('\n');
        }
        writer.flush();
    }

    /**
     * Reads the patch into {@code target FQN -> [annotation FQN...]}. The annotation order
     * within each target is preserved; the target iteration order is not significant.
     */
    public static Map<String, List<String>> read(InputStream is) throws IOException {
        var props = new Properties();
        props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        var patch = new LinkedHashMap<String, List<String>>();
        for (var name : props.stringPropertyNames()) {
            var annotations = parseCsv(props.getProperty(name));
            if (!annotations.isEmpty()) {
                patch.put(name, annotations);
            }
        }
        return patch;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (var part : Arrays.asList(csv.split(","))) {
            var stripped = part.strip();
            if (!stripped.isEmpty()) out.add(stripped);
        }
        return out;
    }
}
