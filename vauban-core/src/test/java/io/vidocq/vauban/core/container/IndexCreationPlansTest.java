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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.bean.plan.IndexCreationPlans;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-PERF-003 step 2: the creation plan worked out from the index is the one the container reads by reflection —
 * supertypes first, fields before initializers, overridden initializers and callbacks left out.
 */
@DisplayName("VAU-PERF-003 - creation plans from the index")
class IndexCreationPlansTest {

    public static class Base {
        @Inject String baseField;
        @Inject void overridden(String s) { /* no behaviour needed */ }
        @Inject void kept(Integer i) { /* no behaviour needed */ }
        @Inject private void privateInit(String s) { /* no behaviour needed */ }
        @PostConstruct void init() { /* no behaviour needed */ }
        @PreDestroy void destroy() { /* no behaviour needed */ }
    }

    public static class Middle extends Base {
        @Inject Long middleField;
        @Override void overridden(String s) { /* not an initializer any more */ }
        @Override void init() { /* overrides the callback without the annotation */ }
    }

    public static class Leaf extends Middle {
        @Inject Double leafField;
        @Inject <T extends Number> void generic(List<T> values, T[] array, int n) { /* no behaviour needed */ }
        @Inject private void privateInit(String s) { /* a private method overrides nothing */ }
        @PostConstruct void leafInit() { /* no behaviour needed */ }
    }

    private static VaubanIndex index(Class<?>... types) {
        var builder = new IndexBuilder();
        for (var type : types) {
            try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                builder.add(ClassFileScanner.scan(in.readAllBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return builder.build();
    }

    private static IndexCreationPlans.Member member(java.lang.reflect.Member m) {
        if (m instanceof Field f) {
            return new IndexCreationPlans.Member(f.getDeclaringClass().getName(), true, f.getName(), null);
        }
        var method = (Method) m;
        var sb = new StringBuilder("(");
        for (var p : method.getParameterTypes()) sb.append(p.descriptorString());
        return new IndexCreationPlans.Member(method.getDeclaringClass().getName(), false, method.getName(),
                sb.append(')').toString());
    }

    @Test
    @DisplayName("the injection order")
    void injectionOrder() {
        var index = index(Base.class, Middle.class, Leaf.class);
        var plan = IndexCreationPlans.injectionOrder(index.getClassByName(DotName.of(Leaf.class.getName()))
                .orElseThrow(), index).orElseThrow();
        var reflective = BeanInjector.injectionOrder(Leaf.class).stream().map(IndexCreationPlansTest::member).toList();

        assertEquals(Set.copyOf(reflective), Set.copyOf(plan));
        assertEquals(reflective.size(), plan.size());
        assertEquals(Base.class.getName(), plan.getFirst().declaringClass(), "supertypes first");
        assertTrue(plan.stream().noneMatch(m -> m.name().equals("overridden")), plan::toString);
        assertTrue(plan.contains(new IndexCreationPlans.Member(Leaf.class.getName(), false, "generic",
                "(Ljava/util/List;[Ljava/lang/Number;I)")), plan::toString);
    }

    @Test
    @DisplayName("the lifecycle callbacks")
    void lifecycleCallbacks() {
        var index = index(Base.class, Middle.class, Leaf.class);
        var leaf = index.getClassByName(DotName.of(Leaf.class.getName())).orElseThrow();
        for (var annotation : List.of(PostConstruct.class, PreDestroy.class)) {
            var plan = IndexCreationPlans.lifecycleCallbacks(leaf, index, DotName.of(annotation.getName()))
                    .orElseThrow();
            var reflective = BeanLifecycle.collectLifecycleMethodsInHierarchy(Leaf.class, annotation).stream()
                    .map(IndexCreationPlansTest::member).toList();
            assertEquals(new HashSet<>(reflective), new HashSet<>(plan), annotation.getSimpleName());
        }
    }

    @Test
    @DisplayName("a superclass outside the index leaves the class to reflection")
    void superclassOutsideTheIndex() {
        var index = index(Middle.class, Leaf.class);
        var leaf = index.getClassByName(DotName.of(Leaf.class.getName())).orElseThrow();

        assertTrue(IndexCreationPlans.injectionOrder(leaf, index).isEmpty());
    }
}
