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

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;import org.junit.jupiter.api.DisplayName;
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
 * Tests for BCE Enhancement processing in Vauban.
 *
 * <h2>Vidocq context</h2>
 * Vidocq uses a Build Compatible Extension ({@code RestScopeExtension})
 * that automatically adds {@code @RequestScoped} to classes annotated
 * with {@code @Path} that have no CDI scope:
 * <pre>
 *   {@literal @}Enhancement(types = Object.class, withAnnotations = Path.class)
 *   public void addDefaultScope(ClassConfig clazz) {
 *       clazz.addAnnotation(RequestScoped.class);
 *   }
 * </pre>
 * With 3 JAX-RS resources ({@code HelloResource}, {@code Hello2Resource},
 * {@code HelloSimpleJaxRSResource}), only the first received the scope.
 * The other two were never processed, hence never discovered as CDI
 * beans, and the {@code JerseyBridge} could not find them.
 *
 * <h2>Two identified bugs</h2>
 * <ol>
 *   <li><b>matchesClass</b>: with {@code types = Object.class} (the default
 *       CDI wildcard) and {@code withSubtypes = false} (the default),
 *       the check did {@code Object.class.equals(targetClass)} → always
 *       {@code false} for archive classes (classes without a CDI scope).
 *       Existing beans used {@code isAssignableFrom} and were not
 *       affected.</li>
 *   <li><b>applyEnhancements</b>: it never created new
 *       {@code BeanDescriptor}s for classes that received a scope via
 *       Enhancement. Only already-discovered beans were modified.</li>
 * </ol>
 *
 * <h2>Why the TCK does not cover this case</h2>
 * The CDI 4.1 TCK tests BCEs with beans that already have a scope or a
 * stereotype. It does not test the scenario where an {@code @Enhancement}
 * <em>promotes</em> a non-bean class to a bean by adding a scope.
 * This pattern is nevertheless common in frameworks (MicroProfile REST,
 * SmallRye JAX-RS) that integrate JAX-RS with CDI via BCEs.
 */
@DisplayName("BCE Enhancement processing")
class BceEnhancementTest {

    // ---- Helpers ----

    /**
     * Call the package-private matchesClass(Class<?>[], boolean, Class<?>) directly.
     */
    private static boolean invokeMatchesClass(Class<?>[] types, boolean withSubtypes, Class<?> targetClass)
            throws Exception {
        return BceTypeMatcher.matchesClass(types, withSubtypes, targetClass);
    }

    /**
     * Invoke the package-private static extractEnhancedScope(List) via reflection
     * (the test lives in another package).
     */
    private static ScopeInfo invokeExtractEnhancedScope(List<VaubanClassConfig> configs) throws Exception {
        Class<?> registrar = Class.forName("io.vidocq.vauban.core.container.SyntheticComponentRegistrar");
        Method m = registrar.getDeclaredMethod("extractEnhancedScope", java.util.List.class);
        m.setAccessible(true);
        return (ScopeInfo) m.invoke(null, configs);
    }

    /**
     * Creates a minimal VaubanClassConfig from an indexer ClassInfo
     * for the Enhancement phase (public constructor VaubanClassConfig(ClassInfo)).
     */
    private static VaubanClassConfig makeClassConfig() {
        var indexClassInfo = new io.vidocq.vauban.indexer.model.ClassInfo(
                DotName.of("com.example.Dummy"),
                DotName.of("java.lang.Object"),
                List.of(),
                0x0001,
                List.of(),
                List.of(),
                List.of(),
                io.vidocq.vauban.indexer.model.ClassInfo.ClassKind.CLASS
        );
        var builder = new IndexBuilder();
        builder.add(indexClassInfo);
        var lookup = new io.vidocq.vauban.core.langmodel.IndexLookup(builder.build());
        var langClassInfo = new io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo(
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
     * Vidocq bug 1: {@code matchesClass(Object.class, false, X)} always
     * returned {@code false} for archive classes. {@code Object.class}
     * is the default wildcard of {@code @Enhancement.types()} and must
     * match all classes, regardless of {@code withSubtypes}.
     */
    @Nested
    @DisplayName("matchesClass - type matching")
    class MatchesClass {

        @Test
        @DisplayName("Object.class without subtypes matches any class (wildcard)")
        void shouldMatchAnyClassWhenObjectWithoutSubtypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, false, Dog.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, false, String.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, false, Integer.class));
        }

        @Test
        @DisplayName("Object.class with subtypes matches any class")
        void shouldMatchAnyClassWhenObjectWithSubtypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, true, Dog.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, true, String.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Object.class}, true, Unrelated.class));
        }

        @Test
        @DisplayName("a specific type without subtypes matches only the exact class")
        void shouldMatchOnlyExactClassWhenNoSubtypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, false, Animal.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{Animal.class}, false, Dog.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{Animal.class}, false, Unrelated.class));
        }

        @Test
        @DisplayName("a specific type with subtypes matches the subclasses")
        void shouldMatchSubtypesWhenEnabled() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Animal.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Dog.class));
            assertTrue(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Cat.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{Animal.class}, true, Unrelated.class));
        }

        @Test
        @DisplayName("an empty array matches no class")
        void shouldMatchNothingWhenTypesEmpty() throws Exception {
            assertFalse(invokeMatchesClass(new Class<?>[0], false, Dog.class));
            assertFalse(invokeMatchesClass(new Class<?>[0], true, Dog.class));
        }

        @Test
        @DisplayName("multiple types - matches if at least one type matches")
        void shouldMatchIfAnyTypeMatches() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{String.class, Animal.class}, false, Animal.class));
            assertFalse(invokeMatchesClass(new Class<?>[]{String.class, Integer.class}, false, Animal.class));
        }

        @Test
        @DisplayName("Object.class among other types acts as a wildcard")
        void shouldMatchWhenObjectAmongOtherTypes() throws Exception {
            assertTrue(invokeMatchesClass(new Class<?>[]{String.class, Object.class}, false, Unrelated.class));
        }
    }

    // ======================================================================
    // Group 2: extractEnhancedScope
    // ======================================================================

    /**
     * Vidocq bug 2: after Enhancement, non-bean classes that received
     * a scope were never converted into a BeanDescriptor.
     * {@code extractEnhancedScope} detects the scope added by the BCE to
     * create the new bean with the correct scope (normal vs pseudo).
     */
    @Nested
    @DisplayName("extractEnhancedScope - scope extraction from the Enhancement configs")
    class ExtractEnhancedScope {

        @Test
        @DisplayName("config with @RequestScoped returns ScopeInfo normal=true")
        void shouldReturnRequestScopedWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(DotName.of("jakarta.enterprise.context.RequestScoped"), scope.annotationName());
            assertTrue(scope.isNormal());
        }

        @Test
        @DisplayName("config with @ApplicationScoped returns ScopeInfo normal=true")
        void shouldReturnApplicationScopedWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.enterprise.context.ApplicationScoped.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(DotName.of("jakarta.enterprise.context.ApplicationScoped"), scope.annotationName());
            assertTrue(scope.isNormal());
        }

        @Test
        @DisplayName("config with @Dependent returns ScopeInfo.DEPENDENT")
        void shouldReturnDependentWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.enterprise.context.Dependent.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(ScopeInfo.DEPENDENT, scope);
        }

        @Test
        @DisplayName("config with @Singleton returns ScopeInfo.SINGLETON")
        void shouldReturnSingletonWhenAdded() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(jakarta.inject.Singleton.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNotNull(scope);
            assertEquals(ScopeInfo.SINGLETON, scope);
        }

        @Test
        @DisplayName("config without a scope annotation returns null")
        void shouldReturnNullWhenNoScopeAnnotation() throws Exception {
            var config = makeClassConfig();
            // No annotation added

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNull(scope);
        }

        @Test
        @DisplayName("config with a non-scope annotation returns null")
        void shouldReturnNullWhenNonScopeAnnotation() throws Exception {
            var config = makeClassConfig();
            config.addAnnotation(SuppressWarnings.class);

            var scope = invokeExtractEnhancedScope(List.of(config));

            assertNull(scope);
        }

        @Test
        @DisplayName("an empty config list returns null")
        void shouldReturnNullWhenEmptyConfigList() throws Exception {
            var scope = invokeExtractEnhancedScope(List.of());

            assertNull(scope);
        }

        @Test
        @DisplayName("the first config with a scope wins over the following ones")
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
        @DisplayName("a config without a scope followed by a config with a scope returns the scope")
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
    // Group 3: VaubanClassConfig - verification of the addAnnotation behavior
    // ======================================================================

    @Nested
    @DisplayName("VaubanClassConfig - annotation modifications")
    class ClassConfigAnnotations {

        @Test
        @DisplayName("addAnnotation(Class) adds the annotation to the list")
        void shouldTrackAddedAnnotationClass() {
            var config = makeClassConfig();

            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);

            assertTrue(config.getAddedAnnotations().contains(jakarta.enterprise.context.RequestScoped.class));
            assertTrue(config.isModified());
        }

        @Test
        @DisplayName("a config without modification is not marked as modified")
        void shouldNotBeModifiedWhenNoChanges() {
            var config = makeClassConfig();

            assertFalse(config.isModified());
        }

        @Test
        @DisplayName("multiple added annotations are all present")
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
        @DisplayName("a duplicate addAnnotation does not create a duplicate (Set)")
        void shouldNotDuplicateAnnotations() {
            var config = makeClassConfig();

            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);
            config.addAnnotation(jakarta.enterprise.context.RequestScoped.class);

            assertEquals(1, config.getAddedAnnotations().size());
        }

        @Test
        @DisplayName("removeAllAnnotations marks the config as modified")
        void shouldBeModifiedAfterRemoveAll() {
            var config = makeClassConfig();

            config.removeAllAnnotations();

            assertTrue(config.isModified());
            assertTrue(config.isAllAnnotationsRemoved());
        }
    }

    // ======================================================================
    // Group 4: Class-level @InterceptorBinding added via Enhancement
    // ======================================================================

    /**
     * Heisenberg / MicroProfile Fault Tolerance bug: a BCE
     * {@code @Enhancement} that added an interceptor binding via
     * {@code ClassConfig.addAnnotation(SomeBinding.class)} was silently
     * ignored. Only qualifier annotations were propagated to
     * {@code BeanDescriptor.interceptorBindings()}; interceptor bindings
     * added at class-level disappeared → no matching {@code @Interceptor}
     * could be activated.
     *
     * <p>Canonical use case: a marker binding (e.g.
     * {@code @FaultToleranceBinding}) added by a BCE to any class carrying
     * {@code @Retry} / {@code @Timeout} / etc. — a pattern used by
     * SmallRye Fault Tolerance and Heisenberg.</p>
     */
    @Nested
    @DisplayName("class-level @InterceptorBinding propagation via Enhancement")
    class ClassLevelInterceptorBindingPropagation {

        @jakarta.interceptor.InterceptorBinding
        @Retention(RetentionPolicy.RUNTIME)
        @interface MarkerBinding {}

        @Retention(RetentionPolicy.RUNTIME)
        @interface NotABinding {}

        private io.vidocq.vauban.core.bean.model.BeanDescriptor makeBean() {
            var id = new io.vidocq.vauban.core.bean.model.BeanId("com.example.Dummy");
            return new io.vidocq.vauban.core.bean.model.BeanDescriptor(
                    id,
                    DotName.of("com.example.Dummy"),
                    io.vidocq.vauban.core.bean.model.BeanDescriptor.BeanKind.MANAGED,
                    java.util.Set.of(),
                    java.util.Set.of(),
                    null,
                    false,
                    0,
                    java.util.List.of(),
                    null,
                    java.util.Set.of(),
                    java.util.Set.of(),
                    java.util.List.of()
            );
        }

        @Test
        @DisplayName("@InterceptorBinding added at class-level is propagated to the BeanDescriptor")
        void shouldPropagateClassLevelInterceptorBinding() {
            var config = makeClassConfig();
            config.addAnnotation(MarkerBinding.class);

            var bean = makeBean();
            var result = BceProcessor.applyEnhancements(
                    java.util.List.of(bean),
                    java.util.Map.of(bean.beanClass(), java.util.List.of(config))
            );

            assertEquals(1, result.size());
            var bindings = result.get(0).interceptorBindings();
            assertTrue(bindings.contains(DotName.of(MarkerBinding.class.getName())),
                    "Expected interceptor binding to be propagated; bindings=" + bindings);
        }

        @Test
        @DisplayName("a non-binding annotation added at class-level is not propagated as a binding")
        void shouldNotPropagateNonBindingAnnotation() {
            var config = makeClassConfig();
            config.addAnnotation(NotABinding.class);

            var bean = makeBean();
            var result = BceProcessor.applyEnhancements(
                    java.util.List.of(bean),
                    java.util.Map.of(bean.beanClass(), java.util.List.of(config))
            );

            assertEquals(1, result.size());
            assertFalse(result.get(0).interceptorBindings()
                            .contains(DotName.of(NotABinding.class.getName())),
                    "Non-binding annotations must not leak into interceptorBindings");
        }
    }
}
