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
package io.vidocq.vauban.core.bean.resolution;

import io.vidocq.vauban.core.annotation.AnnotationTypes;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot validation resolves injection points with {@link QualifierMatcher}. It compares the keys
 * {@link AnnotationTypes} builds, so a member default counts as written and {@code @Nonbinding} members
 * are ignored, whichever class loader defined the qualifier type.
 */
@DisplayName("vauban#70: QualifierMatcher compares normalized keys")
class QualifierMatcherTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Graded {
        String value() default "standard";

        @Nonbinding String note() default "";
    }

    private static final DotName GRADED = DotName.of(Graded.class.getName());

    private final QualifierMatcher matcher = new QualifierMatcher(
            new AnnotationTypes(null, List.of(QualifierMatcherTest.class.getClassLoader()), Map.of()));

    private static QualifierInstance graded(Map<String, AnnotationValue> members) {
        return new QualifierInstance(GRADED, members);
    }

    @Test
    @DisplayName("a bean relying on the default matches the default written out")
    void implicitBeanExplicitPoint() {
        assertTrue(matcher.matches(Set.of(graded(Map.of()), QualifierInstance.ANY),
                Set.of(graded(Map.of("value", new AnnotationValue.StringVal("standard"))))));
    }

    @Test
    @DisplayName("the default written out on the bean matches a point relying on it")
    void explicitBeanImplicitPoint() {
        assertTrue(matcher.matches(Set.of(graded(Map.of("value", new AnnotationValue.StringVal("standard")))),
                Set.of(graded(Map.of()))));
    }

    @Test
    @DisplayName("another value does not match")
    void anotherValue() {
        assertFalse(matcher.matches(Set.of(graded(Map.of())),
                Set.of(graded(Map.of("value", new AnnotationValue.StringVal("premium"))))));
    }

    @Test
    @DisplayName("@Nonbinding members are ignored")
    void nonbindingMember() {
        assertTrue(matcher.matches(Set.of(graded(Map.of("note", new AnnotationValue.StringVal("bean")))),
                Set.of(graded(Map.of("note", new AnnotationValue.StringVal("point"))))));
    }

    @Test
    @DisplayName("@Any matches every bean")
    void any() {
        assertTrue(matcher.matches(Set.of(graded(Map.of())), Set.of(QualifierInstance.ANY)));
    }
}
