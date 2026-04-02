package fr.vidocq.vauban.core.bean.resolution;

import fr.vidocq.vauban.core.bean.model.*;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DependencyGraph - graphe de dependances")
class DependencyGraphTest {

    static BeanDescriptor makeBean(String name, ScopeInfo scope) {
        return new BeanDescriptor(
                BeanId.of(DotName.of(name)), DotName.of(name), BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.ClassType(DotName.of(name))),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                scope, false, 0, List.of(), null);
    }

    @Test
    @DisplayName("construit un graphe simple A -> B -> C")
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
    @DisplayName("cycle entre beans normal-scoped est legal")
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
    @DisplayName("cycle avec un bean @Dependent est illegal")
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
    @DisplayName("pas de cycle dans un graphe acyclique")
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
