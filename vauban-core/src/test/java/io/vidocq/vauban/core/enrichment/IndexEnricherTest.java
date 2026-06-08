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
package io.vidocq.vauban.core.enrichment;

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IndexEnricher - enriching the index with synthetic scopes")
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
    @DisplayName("enrich - adding scope to matching classes")
    class Enrich {

        @Test
        @DisplayName("adds @RequestScoped to a @Path class without a scope")
        void shouldAddScopeToPathClass() {
            var index = buildIndex(makeClass("com.example.HelloResource", PATH));
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            var classInfo = enriched.getClassByName(DotName.of("com.example.HelloResource")).orElseThrow();
            assertTrue(classInfo.hasAnnotation(REQUEST_SCOPED),
                    "The enriched class must have @RequestScoped");
            assertTrue(classInfo.hasAnnotation(PATH),
                    "The trigger annotation must be preserved");
        }

        @Test
        @DisplayName("does not override an existing CDI scope")
        void shouldNotOverrideExistingScope() {
            var index = buildIndex(makeClass("com.example.MyBean", PATH, APPLICATION_SCOPED));
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            var classInfo = enriched.getClassByName(DotName.of("com.example.MyBean")).orElseThrow();
            assertTrue(classInfo.hasAnnotation(APPLICATION_SCOPED),
                    "The existing scope must be preserved");
            assertFalse(classInfo.hasAnnotation(REQUEST_SCOPED),
                    "The enriched scope must not be added");
        }

        @Test
        @DisplayName("does not modify classes without a trigger annotation")
        void shouldNotModifyClassesWithoutTrigger() {
            var index = buildIndex(makeClass("com.example.PlainService"));
            var enriched = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            var classInfo = enriched.getClassByName(DotName.of("com.example.PlainService")).orElseThrow();
            assertTrue(classInfo.annotations().isEmpty());
        }

        @Test
        @DisplayName("enriches multiple classes in the same index")
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
        @DisplayName("supports multiple enrichment rules")
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
        @DisplayName("returns the same index when there are no rules")
        void shouldReturnSameIndexWhenNoRules() {
            var index = buildIndex(makeClass("com.example.Resource", PATH));
            var result = IndexEnricher.enrich(index, EnrichmentConfig.empty());

            assertSame(index, result, "The original index must be returned when there are no rules");
        }

        @Test
        @DisplayName("returns the same index when no class matches")
        void shouldReturnSameIndexWhenNoMatch() {
            var index = buildIndex(makeClass("com.example.PlainService"));
            var result = IndexEnricher.enrich(index, config(PATH, ScopeInfo.REQUEST));

            assertSame(index, result, "The original index must be returned when there is no match");
        }

        @Test
        @DisplayName("preserves the total number of classes in the index")
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
        @DisplayName("the first matching rule wins")
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
                    "The first rule (Path→RequestScoped) must win");
            assertFalse(classInfo.hasAnnotation(APPLICATION_SCOPED));
        }
    }
}
