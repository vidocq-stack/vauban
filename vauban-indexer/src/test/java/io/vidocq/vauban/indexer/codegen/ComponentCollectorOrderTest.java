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
package io.vidocq.vauban.indexer.codegen;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ComponentCollector#collect} lists a provider's entries in the order of the beans' binary
 * names, whatever order its caller hands the beans in: the callers take them from the index, whose
 * iteration order changes from one JVM run to the next (BUG-20261007-04).
 */
@DisplayName("ComponentCollector — stable entry order")
class ComponentCollectorOrderTest {

    private static ProvidedClass bean(String fqn, boolean intercepted) {
        var injected = new FieldInfo("dependency", new TypeInfo.ClassType(DotName.of("app.Dependency")), 0,
                List.of(new AnnotationInfo(DotName.of("jakarta.inject.Inject"), Map.of())));
        var classInfo = new ClassInfo(DotName.of(fqn), DotName.of("java.lang.Object"), List.of(), 0x0001,
                List.of(injected), List.of(), List.of(), ClassKind.CLASS);
        return new ProvidedClass(fqn, classInfo, true, intercepted);
    }

    private static List<ProvidedClass> beans() {
        return List.of(bean("app.Charlie", false), bean("app.Alpha", true), bean("other.Delta", false),
                bean("app.Bravo", false));
    }

    @Test
    @DisplayName("entries come in binary-name order, a bean's $$Intercepted right after it")
    void entriesInNameOrder() {
        var packages = ComponentCollector.collect(beans(), new ArrayList<>());

        assertEquals(List.of("app", "other"), packages.stream().map(PackageProvider::packageName).toList());
        var app = packages.getFirst();
        assertEquals(List.of("app.Alpha", "app.Alpha$$Intercepted", "app.Bravo", "app.Charlie"),
                app.components().stream().map(Component::fqn).toList());
        assertEquals(List.of("app.Alpha", "app.Bravo", "app.Charlie"),
                app.fields().stream().map(FieldInject::declaringClassFqn).toList());
    }

    @Test
    @DisplayName("two callers handing the same beans in different orders get the same providers")
    void sameOutputWhateverTheInputOrder() {
        var reversed = new ArrayList<>(beans());
        java.util.Collections.reverse(reversed);

        assertEquals(ComponentCollector.collect(beans(), new ArrayList<>()),
                ComponentCollector.collect(reversed, new ArrayList<>()));
    }
}
