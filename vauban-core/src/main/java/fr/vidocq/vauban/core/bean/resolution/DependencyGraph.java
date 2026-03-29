package fr.vidocq.vauban.core.bean.resolution;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.BeanId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Directed acyclic graph of bean dependencies.
 * Detects circular dependencies (allowed for normal-scoped beans, forbidden for {@code @Dependent}).
 */
public final class DependencyGraph {

    private final Map<BeanId, Set<BeanId>> adjacency = new LinkedHashMap<>();
    private final Map<BeanId, BeanDescriptor> beanMap = new LinkedHashMap<>();

    public void addBean(BeanDescriptor bean) {
        beanMap.put(bean.id(), bean);
        adjacency.putIfAbsent(bean.id(), new LinkedHashSet<>());
    }

    public void addDependency(BeanId from, BeanId to) {
        adjacency.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
    }

    /**
     * Detects circular dependencies that are deployment errors.
     * Circular deps between normal-scoped beans are OK (resolved via proxies).
     * Circular deps involving at least one {@code @Dependent} bean are errors.
     *
     * @return list of cycles that are errors (involving dependent beans)
     */
    public List<List<BeanId>> detectIllegalCycles() {
        var illegalCycles = new ArrayList<List<BeanId>>();
        var visited = new LinkedHashSet<BeanId>();
        var inStack = new LinkedHashSet<BeanId>();

        for (var beanId : adjacency.keySet()) {
            if (!visited.contains(beanId)) {
                detectCycles(beanId, visited, inStack, new ArrayList<>(), illegalCycles);
            }
        }
        return illegalCycles;
    }

    private void detectCycles(BeanId current, Set<BeanId> visited,
            LinkedHashSet<BeanId> inStack, List<BeanId> path, List<List<BeanId>> illegalCycles) {
        visited.add(current);
        inStack.add(current);
        path.add(current);

        for (var dep : adjacency.getOrDefault(current, Set.of())) {
            if (inStack.contains(dep)) {
                // Found a cycle - extract it
                var cycle = extractCycle(path, dep);
                if (isIllegalCycle(cycle)) {
                    illegalCycles.add(cycle);
                }
            } else if (!visited.contains(dep)) {
                detectCycles(dep, visited, inStack, path, illegalCycles);
            }
        }

        path.removeLast();
        inStack.remove(current);
    }

    private List<BeanId> extractCycle(List<BeanId> path, BeanId cycleStart) {
        var cycle = new ArrayList<BeanId>();
        boolean found = false;
        for (var id : path) {
            if (id.equals(cycleStart)) found = true;
            if (found) cycle.add(id);
        }
        cycle.add(cycleStart); // close the cycle
        return cycle;
    }

    /**
     * A cycle is illegal if any bean in the cycle is {@code @Dependent} (pseudo-scope).
     */
    private boolean isIllegalCycle(List<BeanId> cycle) {
        return cycle.stream()
                .map(beanMap::get)
                .filter(Objects::nonNull)
                .anyMatch(b -> !b.scope().isNormal());
    }

    public Set<BeanId> getDependencies(BeanId beanId) {
        return Collections.unmodifiableSet(adjacency.getOrDefault(beanId, Set.of()));
    }

    public int size() {
        return beanMap.size();
    }
}
