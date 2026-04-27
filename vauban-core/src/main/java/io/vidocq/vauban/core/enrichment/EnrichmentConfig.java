package io.vidocq.vauban.core.enrichment;

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Configuration for compile-time bean enrichment via {@code vauban-apt.properties}.
 *
 * <p>Enrichment rules map a trigger annotation (e.g. {@code jakarta.ws.rs.Path})
 * to a CDI scope (e.g. {@code jakarta.enterprise.context.RequestScoped}).
 * Classes annotated with the trigger that have no CDI scope receive the
 * configured scope, promoting them to CDI beans without requiring a BCE.</p>
 *
 * <h2>Properties format</h2>
 * <pre>
 * enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
 * enrich.jakarta.ws.rs.ext.Provider=jakarta.enterprise.context.ApplicationScoped
 * </pre>
 */
public record EnrichmentConfig(List<EnrichmentRule> rules) {

    private static final String PREFIX = "enrich.";

    private static final Map<String, ScopeInfo> KNOWN_SCOPES = Map.of(
            "jakarta.enterprise.context.ApplicationScoped", ScopeInfo.APPLICATION,
            "jakarta.enterprise.context.RequestScoped", ScopeInfo.REQUEST,
            "jakarta.enterprise.context.Dependent", ScopeInfo.DEPENDENT,
            "jakarta.inject.Singleton", ScopeInfo.SINGLETON
    );

    private static final Set<String> NORMAL_SCOPE_NAMES = Set.of(
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped",
            "jakarta.enterprise.context.ConversationScoped"
    );

    public record EnrichmentRule(DotName triggerAnnotation, ScopeInfo targetScope) {}

    public EnrichmentConfig {
        rules = List.copyOf(rules);
    }

    /**
     * Loads enrichment rules from a properties file input stream.
     * Only keys starting with {@code enrich.} are processed.
     *
     * @param is the input stream (closed by caller)
     * @return the parsed configuration
     * @throws IOException if the stream cannot be read
     */
    public static EnrichmentConfig load(InputStream is) throws IOException {
        var props = new Properties();
        props.load(is);

        var rules = new ArrayList<EnrichmentRule>();
        for (var key : props.stringPropertyNames()) {
            if (!key.startsWith(PREFIX)) continue;

            var annotationFqcn = key.substring(PREFIX.length()).strip();
            var scopeFqcn = props.getProperty(key).strip();

            if (annotationFqcn.isEmpty() || scopeFqcn.isEmpty()) continue;

            var trigger = DotName.of(annotationFqcn);
            var scope = resolveScope(scopeFqcn);
            rules.add(new EnrichmentRule(trigger, scope));
        }

        return new EnrichmentConfig(rules);
    }

    /** Returns an empty configuration with no enrichment rules. */
    public static EnrichmentConfig empty() {
        return new EnrichmentConfig(List.of());
    }

    /** Returns the trigger annotation FQCNs as strings, for use in APT's getSupportedAnnotationTypes. */
    public Set<String> triggerAnnotationNames() {
        var names = new LinkedHashSet<String>();
        for (var rule : rules) {
            names.add(rule.triggerAnnotation().value());
        }
        return Set.copyOf(names);
    }

    /** Returns the trigger annotations as DotNames. */
    public Set<DotName> triggerAnnotations() {
        var names = new LinkedHashSet<DotName>();
        for (var rule : rules) {
            names.add(rule.triggerAnnotation());
        }
        return Set.copyOf(names);
    }

    /**
     * Finds the enrichment rule for the given trigger annotation.
     *
     * @param annotation the annotation to look up
     * @return the matching rule, or empty if no rule matches
     */
    public Optional<EnrichmentRule> ruleFor(DotName annotation) {
        for (var rule : rules) {
            if (rule.triggerAnnotation().equals(annotation)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /**
     * Merges this config with another. Rules from both configs are combined.
     * In case of duplicate trigger annotations, rules from {@code other} win.
     */
    public EnrichmentConfig merge(EnrichmentConfig other) {
        var merged = new LinkedHashMap<DotName, EnrichmentRule>();
        for (var rule : this.rules) {
            merged.put(rule.triggerAnnotation(), rule);
        }
        for (var rule : other.rules) {
            merged.put(rule.triggerAnnotation(), rule);
        }
        return new EnrichmentConfig(List.copyOf(merged.values()));
    }

    private static ScopeInfo resolveScope(String scopeFqcn) {
        var known = KNOWN_SCOPES.get(scopeFqcn);
        if (known != null) return known;

        // Custom scope: assume normal if name matches known patterns, else normal by default
        boolean isNormal = NORMAL_SCOPE_NAMES.contains(scopeFqcn)
                || !scopeFqcn.contains("Singleton")
                && !scopeFqcn.contains("Dependent");
        return new ScopeInfo(DotName.of(scopeFqcn), isNormal);
    }
}
