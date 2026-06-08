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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;

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
                detectCycles(beanId, visited, inStack, new ArrayList<>(), illegalCycles, 0);
            }
        }
        return illegalCycles;
    }

    private static final int MAX_RECURSION_DEPTH = 500;

    private void detectCycles(BeanId current, Set<BeanId> visited,
            LinkedHashSet<BeanId> inStack, List<BeanId> path, List<List<BeanId>> illegalCycles, int depth) {
        if (depth > MAX_RECURSION_DEPTH) {
            // Safety break to avoid StackOverflowError in extremely large or complex graphs
            return;
        }
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
                detectCycles(dep, visited, inStack, path, illegalCycles, depth + 1);
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
     * A cycle is illegal only if ALL beans in the cycle are {@code @Dependent} (pseudo-scope).
     * If any bean is normal-scoped, the client proxy breaks the cycle.
     */
    private boolean isIllegalCycle(List<BeanId> cycle) {
        return cycle.stream()
                .map(beanMap::get)
                .filter(Objects::nonNull)
                .allMatch(b -> !b.scope().isNormal());
    }

    public Set<BeanId> getDependencies(BeanId beanId) {
        return Collections.unmodifiableSet(adjacency.getOrDefault(beanId, Set.of()));
    }

    public int size() {
        return beanMap.size();
    }
}
