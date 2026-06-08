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

import io.vidocq.vauban.core.bean.model.*;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DependencyGraph - dependency graph")
class DependencyGraphTest {

    static BeanDescriptor makeBean(String name, ScopeInfo scope) {
        return new BeanDescriptor(
                BeanId.of(DotName.of(name)), DotName.of(name), BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.ClassType(DotName.of(name))),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                scope, false, 0, List.of(), null);
    }

    @Test
    @DisplayName("builds a simple graph A -> B -> C")
    void shouldBuildSimpleGraph() {
        var graph = new DependencyGraph();
        var a = makeBean("A", ScopeInfo.APPLICATION);
        var b = makeBean("B", ScopeInfo.APPLICATION);
        var c = makeBean("C", ScopeInfo.APPLICATION);
        graph.addBean(a);
        graph.addBean(b);
        graph.addBean(c);
        graph.addDependency(a.id(), b.id());
        graph.addDependency(b.id(), c.id());

        assertEquals(3, graph.size());
        assertTrue(graph.getDependencies(a.id()).contains(b.id()));
        assertTrue(graph.getDependencies(b.id()).contains(c.id()));
        assertTrue(graph.detectIllegalCycles().isEmpty());
    }

    @Test
    @DisplayName("a cycle between normal-scoped beans is legal")
    void shouldAllowNormalScopedCycle() {
        var graph = new DependencyGraph();
        var a = makeBean("A", ScopeInfo.APPLICATION);
        var b = makeBean("B", ScopeInfo.APPLICATION);
        graph.addBean(a);
        graph.addBean(b);
        graph.addDependency(a.id(), b.id());
        graph.addDependency(b.id(), a.id());

        assertTrue(graph.detectIllegalCycles().isEmpty());
    }

    @Test
    @org.junit.jupiter.api.Disabled("Dependent cycle detection not yet implemented")
    @DisplayName("a cycle with a @Dependent bean is illegal")
    void shouldDetectDependentCycle() {
        var graph = new DependencyGraph();
        var a = makeBean("A", ScopeInfo.DEPENDENT);
        var b = makeBean("B", ScopeInfo.APPLICATION);
        graph.addBean(a);
        graph.addBean(b);
        graph.addDependency(a.id(), b.id());
        graph.addDependency(b.id(), a.id());

        var cycles = graph.detectIllegalCycles();
        assertFalse(cycles.isEmpty());
    }

    @Test
    @DisplayName("no cycle in an acyclic graph")
    void shouldDetectNoCyclesInDag() {
        var graph = new DependencyGraph();
        var a = makeBean("A", ScopeInfo.DEPENDENT);
        var b = makeBean("B", ScopeInfo.DEPENDENT);
        var c = makeBean("C", ScopeInfo.DEPENDENT);
        graph.addBean(a);
        graph.addBean(b);
        graph.addBean(c);
        graph.addDependency(a.id(), b.id());
        graph.addDependency(a.id(), c.id());
        graph.addDependency(b.id(), c.id());

        assertTrue(graph.detectIllegalCycles().isEmpty());
    }
}
