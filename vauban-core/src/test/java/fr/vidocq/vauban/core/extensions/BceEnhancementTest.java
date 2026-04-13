package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.core.bean.model.ScopeInfo;
import fr.vidocq.vauban.core.container.VaubanContainerBuilder;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests pour le traitement BCE Enhancement dans Vauban.
 *
 * <h2>Contexte Vidocq</h2>
 * Vidocq utilise une Build Compatible Extension ({@code RestScopeExtension})
 * qui ajoute automatiquement {@code @RequestScoped} aux classes annotees
 * {@code @Path} sans scope CDI :
 * <pre>
 *   {@literal @}Enhancement(types = Object.class, withAnnotations = Path.class)
 *   public void addDefaultScope(ClassConfig clazz) {
 *       clazz.addAnnotation(RequestScoped.class);
 *   }
 * </pre>
 * Avec 3 ressources JAX-RS ({@code HelloResource}, {@code Hello2Resource},
 * {@code HelloSimpleJaxRSResource}), seule la premiere recevait le scope.
 * Les deux autres n'etaient jamais traitees, donc jamais decouvertes comme
 * beans CDI, et le {@code JerseyBridge} ne les trouvait pas.
 *
 * <h2>Deux bugs identifies</h2>
 * <ol>
 *   <li><b>matchesClass</b> : avec {@code types = Object.class} (le wildcard
 *       CDI par defaut) et {@code withSubtypes = false} (le defaut),
 *       le check faisait {@code Object.class.equals(targetClass)} → toujours
 *       {@code false} pour les archive classes (classes sans scope CDI).
 *       Les beans existants utilisaient {@code isAssignableFrom} et n'etaient
 *       pas affectes.</li>
 *   <li><b>applyEnhancements</b> : ne creait jamais de nouveaux
 *       {@code BeanDescriptor} pour les classes qui recevaient un scope via
 *       Enhancement. Seuls les beans deja decouverts etaient modifies.</li>
 * </ol>
 *
 * <h2>Pourquoi le TCK ne couvre pas ce cas</h2>
 * Le TCK CDI 4.1 teste les BCE avec des beans qui ont deja un scope ou un
 * stereotype. Il ne teste pas le scenario ou une {@code @Enhancement}
 * <em>promeut</em> une classe non-bean en bean en ajoutant un scope.
 * Ce pattern est pourtant courant dans les frameworks (MicroProfile REST,
 * SmallRye JAX-RS) qui integrent JAX-RS avec CDI via des BCE.
 */
@DisplayName("BCE Enhancement processing")
class BceEnhancementTest {

    // ---- Helpers ----

    /**
     * Invoke the private static matchesClass(Class<?>[], boolean, Class<?>) via reflection.
     */
    private static boolean invokeMatchesClass(Class<?>[] types, boolean withSubtypes, Class<?> targetClass)
            throws Exception {
        Method m = BceProcessor.class.getDeclaredMethod("matchesClass", Class[].class, boolean.class, Class.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, types, withSubtypes, targetClass);
    }

    /**
     * Invoke the private static extractEnhancedScope(List) via reflection.
     */
    private static ScopeInfo invokeExtractEnhancedScope(List<VaubanClassConfig> configs) throws Exception {
        Method m = VaubanContainerBuilder.class.getDeclaredMethod("extractEnhancedScope", java.util.List.class);
        m.setAccessible(true);
        return (ScopeInfo) m.invoke(null, configs);
    }

    /**
     * Cree un VaubanClassConfig minimal a partir d'un ClassInfo indexer
     * pour la phase Enhancement (constructeur public VaubanClassConfig(ClassInfo)).
     */
    private static VaubanClassConfig makeClassConfig() {
        var indexClassInfo = new fr.vidocq.vauban.indexer.model.ClassInfo(
                DotName.of("com.example.Dummy"),
                DotName.of("java.lang.Object"),
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind.CLASS
        );
        var builder = new IndexBuilder();
        builder.add(indexClassInfo);
        var lookup = new fr.vidocq.vauban.core.langmodel.IndexLookup(builder.build());
        var langClassInfo = new fr.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo(
                indexClassInfo, lookup);
        return new VaubanClassConfig(langClassInfo);
    }

    // ---- Test classes for matchesClass ----

    static class Animal {}
    static class Dog extends Animal {}
    static class Cat extends Animal {}
    static class Unrelated {}

    // ---- Annotation for integration tests ----

    @Retention(RetentionPolicy.RUNTIME)
    @interface SomeMarker {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface AnotherMarker {}

    // ======================================================================
    // Group 1: matchesClass — Object.class wildcard fix
    // ======================================================================

    /**
     * Bug 1 Vidocq : {@code matchesClass(Object.class, false, X)} retournait
     * toujours {@code false} pour les archive classes. {@code Object.class}
     * est le wildcard par defaut de {@code @Enhancement.types()} et doit
     * matcher toutes les classes, independamment de {@code withSubtypes}.
     */
    @Nested
    @DisplayName("matchesClass - correspondance de types")
    class MatchesClass {

        @Test
        @DisplayName("Object.class sans sous-types correspond a n'importe quelle classe (wildcard)")
        void shouldMatchAnyClassWhenObjectWithoutSubtypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, false, Dog.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, false, String.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, false, Integer.class));
        }

        @Test
        @DisplayName("Object.class avec sous-types correspond a n'importe quelle classe")
        void shouldMatchAnyClassWhenObjectWithSubtypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, true, Dog.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, true, String.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, true, Unrelated.class));
        }

        @Test
        @DisplayName("type specifique sans sous-types ne correspond qu'a la classe exacte")
        void shouldMatchOnlyExactClassWhenNoSubtypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, false, Animal.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{Animal.class}, false, Dog.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{Animal.class}, false, Unrelated.class));
        }

        @Test
        @DisplayName("type specifique avec sous-types correspond aux sous-classes")
        void shouldMatchSubtypesWhenEnabled() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Animal.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Dog.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Cat.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Unrelated.class));
        }

        @Test
        @DisplayName("tableau vide ne correspond a aucune classe")
        void shouldMatchNothingWhenTypesEmpty() throws Exception {
            assertFalse(invokeMatchesClass(new Class<?>[0], false, Dog.class));
            assertFalse(invokeMatchesClass(new Class<?>[0], true, Dog.class));
        }

        @Test
        @DisplayName("plusieurs types - correspond si au moins un type matche")
        void shouldMatchIfAnyTypeMatches() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{String.class, Animal.class}, false, Animal.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{String.class, Integer.class}, false, Animal.class));
        }

        @Test
        @DisplayName("Object.class parmi d'autres types agit comme wildcard")
        void shouldMatchWhenObjectAmongOtherTypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{String.class, Object.class}, false, Unrelated.class));
        }
    }

    // ======================================================================
    // Group 2: extractEnhancedScope
    // ======================================================================

    /**
     * Bug 2 Vidocq : apres Enhancement, les classes non-beans qui recevaient
     * un scope n'etaient jamais converties en BeanDescriptor.
     * {@code extractEnhancedScope} detecte le scope ajoute par la BCE pour
     * creer le nouveau bean avec le bon scope (normal vs pseudo).
     */
    @Nested
    @DisplayName("extractEnhancedScope - extraction du scope depuis les configs Enhancement")
    class ExtractEnhancedScope {

        @Test
        @DisplayName("config avec @RequestScoped retourne ScopeInfo normal=true")
        void shouldReturnRequestScopedWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(DotName.of("jakarta.enterprise.context.RequestScoped"), scope.annotationName());
            assertTrue(scope.isNormal());
        }

        @Test
        @DisplayName("config avec @ApplicationScoped retourne ScopeInfo normal=true")
        void shouldReturnApplicationScopedWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.enterprise.context.ApplicationScoped.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(DotName.of("jakarta.enterprise.context.ApplicationScoped"), scope.annotationName());
            assertTrue(scope.isNormal());
        }

        @Test
        @DisplayName("config avec @Dependent retourne ScopeInfo.DEPENDENT")
        void shouldReturnDependentWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.enterprise.context.Dependent.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(ScopeInfo.DEPENDENT, scope);
        }

        @Test
        @DisplayName("config avec @Singleton retourne ScopeInfo.SINGLETON")
        void shouldReturnSingletonWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.inject.Singleton.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(ScopeInfo.SINGLETON, scope);
        }

        @Test
        @DisplayName("config sans annotation de scope retourne null")
        void shouldReturnNullWhenNoScopeAnnotation() throws Exception {
            var config = makeClassConfig();
            // Aucune annotation ajoutee

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNull(scope);
        }

        @Test
        @DisplayName("config avec annotation non-scope retourne null")
        void shouldReturnNullWhenNonScopeAnnotation() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(SuppressWarnings.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNull(scope);
        }

        @Test
        @DisplayName("liste vide de configs retourne null")
        void shouldReturnNullWhenEmptyConfigList() throws Exception {
            var scope = invokeExtractEnhancedScope(List.of());

            assertNull(scope);
        }

        @Test
        @DisplayName("premiere config avec scope gagne sur les suivantes")
        void shouldReturnFirstScopeWhenMultipleConfigs() throws Exception {
            var config1 = makeClassConfig();
            config1.addAnnotation(jakarta.enterprise.context.RequestScoped.class);

            var config2 = makeClassConfig();
            config2.addAnnotation(jakarta.enterprise.context.ApplicationScoped.class);

            var scope = invokeExtractEnhancedScope(List.of(config1, config2));

            assertNotNull(scope);
            assertEquals(DotName.of("jakarta.enterprise.context.RequestScoped"), scope.annotationName());
        }

        @Test
        @DisplayName("config sans scope suivie d'une config avec scope retourne le scope")
        void shouldSkipNonScopeAndReturnFirstScope() throws Exception {
            var config1 = makeClassConfig();
            config1.addAnnotation(SuppressWarnings.class);

            var config2 = makeClassConfig();
            config2.addAnnotation(jakarta.enterprise.context.ApplicationScoped.class);

            var scope = invokeExtractEnhancedScope(List.of(config1, config2));

            assertNotNull(scope);
            assertEquals(DotName.of("jakarta.enterprise.context.ApplicationScoped"), scope.annotationName());
        }
    }

    // ======================================================================
    // Group 3: VaubanClassConfig - verification du comportement addAnnotation
    // ======================================================================

    @Nested
    @DisplayName("VaubanClassConfig - modifications d'annotations")
    class ClassConfigAnnotations {

        @Test
        @DisplayName("addAnnotation(Class) ajoute l'annotation a la liste")
        void shouldTrackAddedAnnotationClass() {
            var config = makeClassConfig();

            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);

            assertTrue(config.getAddedAnnotations().contains(jakarta.enterprise.context.RequestScoped.class));
            assertTrue(config.isModified());
        }

        @Test
        @DisplayName("config sans modification n'est pas marquee modifiee")
        void shouldNotBeModifiedWhenNoChanges() {
            var config = makeClassConfig();

            assertFalse(config.isModified());
        }

        @Test
        @DisplayName("plusieurs annotations ajoutees sont toutes presentes")
        void shouldTrackMultipleAddedAnnotations() {
            var config = makeClassConfig();

            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);
            config.addAnnotation(jakarta.inject.Named.class);

            var added = config.getAddedAnnotations();
            assertEquals(2, added.size());
            assertTrue(added.contains(jakarta.enterprise.context.RequestScoped.class));
            assertTrue(added.contains(jakarta.inject.Named.class));
        }

        @Test
        @DisplayName("addAnnotation en doublon ne cree pas de duplicata (Set)")
        void shouldNotDuplicateAnnotations() {
            var config = makeClassConfig();

            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);
            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);

            assertEquals(1, config.getAddedAnnotations().size());
        }

        @Test
        @DisplayName("removeAllAnnotations marque la config comme modifiee")
        void shouldBeModifiedAfterRemoveAll() {
            var config = makeClassConfig();

            config.removeAllAnnotations();

            assertTrue(config.isModified());
            assertTrue(config.isAllAnnotationsRemoved());
        }
    }
}
