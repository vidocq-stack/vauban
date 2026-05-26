package io.vidocq.vauban.core.enrichment;

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EnrichmentConfig - parsing vauban-apt.properties")
class EnrichmentConfigTest {

    private static EnrichmentConfig parse(String content) throws IOException {
        return EnrichmentConfig.load(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    @DisplayName("load - properties file parsing")
    class Load {

        @Test
        @DisplayName("parses a simple rule enrich.Path=RequestScoped")
        void shouldParseSingleRule() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped");

            assertEquals(1, config.rules().size());
            var rule = config.rules().getFirst();
            assertEquals(DotName.of("jakarta.ws.rs.Path"), rule.triggerAnnotation());
            assertEquals(ScopeInfo.REQUEST, rule.targetScope());
        }

        @Test
        @DisplayName("parses multiple rules")
        void shouldParseMultipleRules() throws Exception {
            var config = parse("""
                    enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
                    enrich.jakarta.ws.rs.ext.Provider=jakarta.enterprise.context.ApplicationScoped
                    """);

            assertEquals(2, config.rules().size());
        }

        @Test
        @DisplayName("ignores keys without the enrich. prefix")
        void shouldIgnoreNonEnrichKeys() throws Exception {
            var config = parse("""
                    other.key=value
                    enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
                    some.setting=true
                    """);

            assertEquals(1, config.rules().size());
        }

        @Test
        @DisplayName("ignores empty keys")
        void shouldIgnoreEmptyKeys() throws Exception {
            var config = parse("enrich.=jakarta.enterprise.context.RequestScoped");
            assertEquals(0, config.rules().size());
        }

        @Test
        @DisplayName("ignores empty values")
        void shouldIgnoreEmptyValues() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=");
            assertEquals(0, config.rules().size());
        }

        @Test
        @DisplayName("ignores comments")
        void shouldIgnoreComments() throws Exception {
            var config = parse("""
                    # Bean enrichment rules
                    enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
                    # Another comment
                    """);

            assertEquals(1, config.rules().size());
        }

        @Test
        @DisplayName("empty file returns empty config")
        void shouldReturnEmptyForEmptyFile() throws Exception {
            var config = parse("");
            assertTrue(config.rules().isEmpty());
        }
    }

    @Nested
    @DisplayName("resolveScope - resolution of known scopes")
    class ResolveScope {

        @Test
        @DisplayName("ApplicationScoped is normal")
        void shouldResolveApplicationScoped() throws Exception {
            var config = parse("enrich.X=jakarta.enterprise.context.ApplicationScoped");
            assertEquals(ScopeInfo.APPLICATION, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("RequestScoped is normal")
        void shouldResolveRequestScoped() throws Exception {
            var config = parse("enrich.X=jakarta.enterprise.context.RequestScoped");
            assertEquals(ScopeInfo.REQUEST, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("Dependent is a pseudo-scope")
        void shouldResolveDependent() throws Exception {
            var config = parse("enrich.X=jakarta.enterprise.context.Dependent");
            assertEquals(ScopeInfo.DEPENDENT, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("Singleton is a pseudo-scope")
        void shouldResolveSingleton() throws Exception {
            var config = parse("enrich.X=jakarta.inject.Singleton");
            assertEquals(ScopeInfo.SINGLETON, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("a custom scope is treated as normal by default")
        void shouldResolveCustomScopeAsNormal() throws Exception {
            var config = parse("enrich.X=com.example.MyCustomScope");
            var scope = config.rules().getFirst().targetScope();
            assertEquals(DotName.of("com.example.MyCustomScope"), scope.annotationName());
            assertTrue(scope.isNormal());
        }
    }

    @Nested
    @DisplayName("triggerAnnotationNames - names for APT")
    class TriggerAnnotationNames {

        @Test
        @DisplayName("returns the FQCNs of the trigger annotations")
        void shouldReturnTriggerFqcns() throws Exception {
            var config = parse("""
                    enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
                    enrich.jakarta.ws.rs.ext.Provider=jakarta.enterprise.context.ApplicationScoped
                    """);

            var names = config.triggerAnnotationNames();
            assertEquals(2, names.size());
            assertTrue(names.contains("jakarta.ws.rs.Path"));
            assertTrue(names.contains("jakarta.ws.rs.ext.Provider"));
        }

        @Test
        @DisplayName("empty config returns an empty set")
        void shouldReturnEmptyForEmptyConfig() {
            assertTrue(EnrichmentConfig.empty().triggerAnnotationNames().isEmpty());
        }
    }

    @Nested
    @DisplayName("ruleFor - lookup by annotation")
    class RuleFor {

        @Test
        @DisplayName("finds the rule for a known annotation")
        void shouldFindRuleForKnownAnnotation() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped");

            var rule = config.ruleFor(DotName.of("jakarta.ws.rs.Path"));
            assertTrue(rule.isPresent());
            assertEquals(ScopeInfo.REQUEST, rule.get().targetScope());
        }

        @Test
        @DisplayName("returns empty for an unknown annotation")
        void shouldReturnEmptyForUnknownAnnotation() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped");

            assertTrue(config.ruleFor(DotName.of("com.example.Unknown")).isEmpty());
        }
    }

    @Nested
    @DisplayName("merge - merging configs")
    class Merge {

        @Test
        @DisplayName("merges two configs without conflict")
        void shouldMergeDisjointConfigs() throws Exception {
            var a = parse("enrich.A=jakarta.enterprise.context.RequestScoped");
            var b = parse("enrich.B=jakarta.enterprise.context.ApplicationScoped");

            var merged = a.merge(b);
            assertEquals(2, merged.rules().size());
        }

        @Test
        @DisplayName("the second config wins on a duplicate")
        void shouldPreferSecondOnConflict() throws Exception {
            var a = parse("enrich.A=jakarta.enterprise.context.RequestScoped");
            var b = parse("enrich.A=jakarta.enterprise.context.ApplicationScoped");

            var merged = a.merge(b);
            assertEquals(1, merged.rules().size());
            assertEquals(ScopeInfo.APPLICATION, merged.rules().getFirst().targetScope());
        }
    }

    @Nested
    @DisplayName("empty - empty config")
    class Empty {

        @Test
        @DisplayName("empty() returns a config with no rules")
        void shouldReturnEmptyConfig() {
            var config = EnrichmentConfig.empty();
            assertTrue(config.rules().isEmpty());
            assertTrue(config.triggerAnnotationNames().isEmpty());
            assertTrue(config.triggerAnnotations().isEmpty());
        }
    }
}
