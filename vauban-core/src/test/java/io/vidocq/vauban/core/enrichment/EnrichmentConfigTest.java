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
    @DisplayName("load - parsing de fichier properties")
    class Load {

        @Test
        @DisplayName("parse une regle simple enrich.Path=RequestScoped")
        void shouldParseSingleRule() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped");

            assertEquals(1, config.rules().size());
            var rule = config.rules().getFirst();
            assertEquals(DotName.of("jakarta.ws.rs.Path"), rule.triggerAnnotation());
            assertEquals(ScopeInfo.REQUEST, rule.targetScope());
        }

        @Test
        @DisplayName("parse plusieurs regles")
        void shouldParseMultipleRules() throws Exception {
            var config = parse("""
                    enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
                    enrich.jakarta.ws.rs.ext.Provider=jakarta.enterprise.context.ApplicationScoped
                    """);

            assertEquals(2, config.rules().size());
        }

        @Test
        @DisplayName("ignore les cles sans prefix enrich.")
        void shouldIgnoreNonEnrichKeys() throws Exception {
            var config = parse("""
                    other.key=value
                    enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
                    some.setting=true
                    """);

            assertEquals(1, config.rules().size());
        }

        @Test
        @DisplayName("ignore les cles vides")
        void shouldIgnoreEmptyKeys() throws Exception {
            var config = parse("enrich.=jakarta.enterprise.context.RequestScoped");
            assertEquals(0, config.rules().size());
        }

        @Test
        @DisplayName("ignore les valeurs vides")
        void shouldIgnoreEmptyValues() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=");
            assertEquals(0, config.rules().size());
        }

        @Test
        @DisplayName("ignore les commentaires")
        void shouldIgnoreComments() throws Exception {
            var config = parse("""
                    # Bean enrichment rules
                    enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped
                    # Another comment
                    """);

            assertEquals(1, config.rules().size());
        }

        @Test
        @DisplayName("fichier vide retourne config vide")
        void shouldReturnEmptyForEmptyFile() throws Exception {
            var config = parse("");
            assertTrue(config.rules().isEmpty());
        }
    }

    @Nested
    @DisplayName("resolveScope - resolution des scopes connus")
    class ResolveScope {

        @Test
        @DisplayName("ApplicationScoped est normal")
        void shouldResolveApplicationScoped() throws Exception {
            var config = parse("enrich.X=jakarta.enterprise.context.ApplicationScoped");
            assertEquals(ScopeInfo.APPLICATION, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("RequestScoped est normal")
        void shouldResolveRequestScoped() throws Exception {
            var config = parse("enrich.X=jakarta.enterprise.context.RequestScoped");
            assertEquals(ScopeInfo.REQUEST, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("Dependent est pseudo-scope")
        void shouldResolveDependent() throws Exception {
            var config = parse("enrich.X=jakarta.enterprise.context.Dependent");
            assertEquals(ScopeInfo.DEPENDENT, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("Singleton est pseudo-scope")
        void shouldResolveSingleton() throws Exception {
            var config = parse("enrich.X=jakarta.inject.Singleton");
            assertEquals(ScopeInfo.SINGLETON, config.rules().getFirst().targetScope());
        }

        @Test
        @DisplayName("scope custom est traite comme normal par defaut")
        void shouldResolveCustomScopeAsNormal() throws Exception {
            var config = parse("enrich.X=com.example.MyCustomScope");
            var scope = config.rules().getFirst().targetScope();
            assertEquals(DotName.of("com.example.MyCustomScope"), scope.annotationName());
            assertTrue(scope.isNormal());
        }
    }

    @Nested
    @DisplayName("triggerAnnotationNames - noms pour APT")
    class TriggerAnnotationNames {

        @Test
        @DisplayName("retourne les FQCNs des annotations trigger")
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
        @DisplayName("config vide retourne ensemble vide")
        void shouldReturnEmptyForEmptyConfig() {
            assertTrue(EnrichmentConfig.empty().triggerAnnotationNames().isEmpty());
        }
    }

    @Nested
    @DisplayName("ruleFor - recherche par annotation")
    class RuleFor {

        @Test
        @DisplayName("trouve la regle pour une annotation connue")
        void shouldFindRuleForKnownAnnotation() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped");

            var rule = config.ruleFor(DotName.of("jakarta.ws.rs.Path"));
            assertTrue(rule.isPresent());
            assertEquals(ScopeInfo.REQUEST, rule.get().targetScope());
        }

        @Test
        @DisplayName("retourne vide pour une annotation inconnue")
        void shouldReturnEmptyForUnknownAnnotation() throws Exception {
            var config = parse("enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped");

            assertTrue(config.ruleFor(DotName.of("com.example.Unknown")).isEmpty());
        }
    }

    @Nested
    @DisplayName("merge - fusion de configs")
    class Merge {

        @Test
        @DisplayName("fusionne deux configs sans conflit")
        void shouldMergeDisjointConfigs() throws Exception {
            var a = parse("enrich.A=jakarta.enterprise.context.RequestScoped");
            var b = parse("enrich.B=jakarta.enterprise.context.ApplicationScoped");

            var merged = a.merge(b);
            assertEquals(2, merged.rules().size());
        }

        @Test
        @DisplayName("la seconde config gagne en cas de doublon")
        void shouldPreferSecondOnConflict() throws Exception {
            var a = parse("enrich.A=jakarta.enterprise.context.RequestScoped");
            var b = parse("enrich.A=jakarta.enterprise.context.ApplicationScoped");

            var merged = a.merge(b);
            assertEquals(1, merged.rules().size());
            assertEquals(ScopeInfo.APPLICATION, merged.rules().getFirst().targetScope());
        }
    }

    @Nested
    @DisplayName("empty - config vide")
    class Empty {

        @Test
        @DisplayName("empty() retourne une config sans regles")
        void shouldReturnEmptyConfig() {
            var config = EnrichmentConfig.empty();
            assertTrue(config.rules().isEmpty());
            assertTrue(config.triggerAnnotationNames().isEmpty());
            assertTrue(config.triggerAnnotations().isEmpty());
        }
    }
}
