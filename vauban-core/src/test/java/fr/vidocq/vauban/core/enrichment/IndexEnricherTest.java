package fr.vidocq.vauban.core.enrichment;

import fr.vidocq.vauban.core.bean.model.ScopeInfo;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.AnnotationInfo;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IndexEnricher - enrichissement de l'index avec scopes synthetiques")
class IndexEnricherTest {

    private static final DotName PATH = DotName.of("jakarta.ws.rs.Path");
    private static final DotName REQUEST_SCOPED = DotName.of("jakarta.enterprise.context.RequestScoped");
    private static final DotName APPLICATION_SCOPED = DotName.of("jakarta.enterprise.context.ApplicationScoped");
    private static final DotName PROVIDER = DotName.of("jakarta.ws.rs.ext.Provider");

    private static ClassInfo makeClass(String name, DotName... annotations) {
        var annList = new java.util.ArrayList<AnnotationInfo>();
        for (var ann : annotations) {
            annList.add(new AnnotationInfo(ann, Map.of()));
        }
        return new ClassInfo(
                DotName.of(name),
                DotName.of("java.lang.Object"),
                List.of(),
                0x0001, // public
                List.of(),
                List.of(),
                annList,
                ClassInfo.ClassKind.CLASS
        );
    }

    private static VaubanIndex buildIndex(ClassInfo... classes) {
        var builder = new IndexBuilder();
        for (var ci : classes) builder.add(ci);
        return builder.build();
    }

    private static EnrichmentConfig config(DotName trigger, ScopeInfo scope) {
        return new EnrichmentConfig(List.of(
                new EnrichmentConfig.EnrichmentRule(trigger, scope)
        ));
    }

    @Nested
    @DisplayName("enrich - ajout de scope aux classes matchantes")
    class Enrich {

        @Test
        @DisplayName("ajoute @RequestScoped a une classe @Path sans scope")
        void shouldAddScopeToPathClass() {
            var index = buildIndex(makeClass("com.example.HelloResource", PATH));
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            var classInfo = enriched.getClassByName(DotName.of("com.example.HelloResource")).orElseThrow();
            assertTrue(classInfo.hasAnnotation(REQUEST_SCOPED),
                    "La classe enrichie doit avoir @RequestScoped");
            assertTrue(classInfo.hasAnnotation(PATH),
                    "L'annotation trigger doit etre conservee");
        }

        @Test
        @DisplayName("n'ecrase pas un scope CDI existant")
        void shouldNotOverrideExistingScope() {
            var index = buildIndex(makeClass("com.example.MyBean", PATH, APPLICATION_SCOPED));
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            var classInfo = enriched.getClassByName(DotName.of("com.example.MyBean")).orElseThrow();
            assertTrue(classInfo.hasAnnotation(APPLICATION_SCOPED),
                    "Le scope existant doit etre conserve");
            assertFalse(classInfo.hasAnnotation(REQUEST_SCOPED),
                    "Le scope enrichi ne doit pas etre ajoute");
        }

        @Test
        @DisplayName("ne modifie pas les classes sans annotation trigger")
        void shouldNotModifyClassesWithoutTrigger() {
            var index = buildIndex(makeClass("com.example.PlainService"));
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            var classInfo = enriched.getClassByName(DotName.of("com.example.PlainService")).orElseThrow();
            assertTrue(classInfo.annotations().isEmpty());
        }

        @Test
        @DisplayName("enrichit plusieurs classes dans le meme index")
        void shouldEnrichMultipleClasses() {
            var index = buildIndex(
                    makeClass("com.example.Resource1", PATH),
                    makeClass("com.example.Resource2", PATH),
                    makeClass("com.example.Service")
            );
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            assertTrue(enriched.getClassByName(DotName.of("com.example.Resource1"))
                    .orElseThrow().hasAnnotation(REQUEST_SCOPED));
            assertTrue(enriched.getClassByName(DotName.of("com.example.Resource2"))
                    .orElseThrow().hasAnnotation(REQUEST_SCOPED));
            assertFalse(enriched.getClassByName(DotName.of("com.example.Service"))
                    .orElseThrow().hasAnnotation(REQUEST_SCOPED));
        }

        @Test
        @DisplayName("supporte plusieurs regles d'enrichissement")
        void shouldSupportMultipleRules() {
            var index = buildIndex(
                    makeClass("com.example.Resource", PATH),
                    makeClass("com.example.MyProvider", PROVIDER)
            );
            var rules = new EnrichmentConfig(List.of(
                    new EnrichmentConfig.EnrichmentRule(PATH, ScopeInfo.REQUEST),
                    new EnrichmentConfig.EnrichmentRule(PROVIDER, ScopeInfo.APPLICATION)
            ));

            var enriched = IndexEnricher.enrich(index, rules);

            assertTrue(enriched.getClassByName(DotName.of("com.example.Resource"))
                    .orElseThrow().hasAnnotation(REQUEST_SCOPED));
            assertTrue(enriched.getClassByName(DotName.of("com.example.MyProvider"))
                    .orElseThrow().hasAnnotation(APPLICATION_SCOPED));
        }

        @Test
        @DisplayName("retourne le meme index si aucune regle")
        void shouldReturnSameIndexWhenNoRules() {
            var index = buildIndex(makeClass("com.example.Resource", PATH));
            var result = IndexEnricher.enrich(index, EnrichmentConfig.empty());

            assertSame(index, result, "L'index original doit etre retourne si pas de regles");
        }

        @Test
        @DisplayName("retourne le meme index si aucune classe ne matche")
        void shouldReturnSameIndexWhenNoMatch() {
            var index = buildIndex(makeClass("com.example.PlainService"));
            var result = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            assertSame(index, result, "L'index original doit etre retourne si pas de match");
        }

        @Test
        @DisplayName("preserve le nombre total de classes dans l'index")
        void shouldPreserveClassCount() {
            var index = buildIndex(
                    makeClass("com.example.A", PATH),
                    makeClass("com.example.B"),
                    makeClass("com.example.C", PATH)
            );
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            assertEquals(3, enriched.size());
        }

        @Test
        @DisplayName("la premiere regle qui matche gagne")
        void shouldApplyFirstMatchingRule() {
            // Class has both @Path and @Provider
            var index = buildIndex(makeClass("com.example.Dual", PATH, PROVIDER));
            var rules = new EnrichmentConfig(List.of(
                    new EnrichmentConfig.EnrichmentRule(PATH, ScopeInfo.REQUEST),
                    new EnrichmentConfig.EnrichmentRule(PROVIDER, ScopeInfo.APPLICATION)
            ));

            var enriched = IndexEnricher.enrich(index, rules);
            var classInfo = enriched.getClassByName(DotName.of("com.example.Dual")).orElseThrow();

            assertTrue(classInfo.hasAnnotation(REQUEST_SCOPED),
                    "La premiere regle (Path→RequestScoped) doit gagner");
            assertFalse(classInfo.hasAnnotation(APPLICATION_SCOPED));
        }
    }
}
