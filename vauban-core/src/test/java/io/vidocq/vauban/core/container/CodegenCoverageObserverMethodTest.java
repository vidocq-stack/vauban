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

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which method the codegen coverage report attributes an observer to, found by name and declared event type: the
 * overload observing that type, up the hierarchy, else the first observing overload of that name.
 */
@DisplayName("CodegenCoverage: the method of an observer")
class CodegenCoverageObserverMethodTest {

    private static final String ON = "on";

    @Dependent
    public static class Watcher {
        public void on(int ignored) {
            // shares the name, observes nothing
        }

        public void on(@Observes String text) {
            // observes strings
        }

        public void on(@ObservesAsync Integer number) {
            // observes integers, asynchronously
        }

        public void onList(@Observes List<String> texts) {
            // observes lists
        }
    }

    @Dependent
    public static class SubWatcher extends Watcher {
    }

    private static VaubanContainer container;

    @BeforeAll
    static void boot() {
        container = VaubanContainer.builder().addBeanClass(Watcher.class).build();
    }

    @AfterAll
    static void close() {
        container.close();
    }

    private static CodegenCoverage.Operation operation(Class<?> declaring, String method, TypeInfo eventType) {
        var observer = new ObserverDescriptor(DotName.of(declaring.getName()), method, eventType, List.of(), false, 0);
        var operations = container.codegenCoverage().operations(observer);
        assertEquals(1, operations.size(), operations::toString);
        return operations.getFirst();
    }

    private static TypeInfo cls(Class<?> type) {
        return new TypeInfo.ClassType(DotName.of(type.getName()));
    }

    @Test
    @DisplayName("the overload observing the declared event type, inherited ones included")
    void matchingOverload() {
        var direct = operation(Watcher.class, ON, cls(Integer.class));
        var inherited = operation(SubWatcher.class, ON, cls(String.class));

        assertEquals(CodegenCoverage.Kind.INVOKE, direct.kind());
        assertTrue(direct.key().endsWith("#on(java.lang.Integer)"), direct::key);
        assertTrue(inherited.key().endsWith("#on(java.lang.String)"), inherited::key);
    }

    @Test
    @DisplayName("a parameterized event type is matched by its raw type")
    void parameterizedEventType() {
        var operation = operation(Watcher.class, "onList",
                new TypeInfo.ParameterizedType(DotName.of(List.class.getName()), List.of(cls(String.class))));

        assertTrue(operation.key().endsWith("#onList(java.util.List)"), operation::key);
    }

    @Test
    @DisplayName("with no overload observing the event type, the first observing overload; any for an unnamed type")
    void fallbackOverloads() {
        var otherEvent = operation(Watcher.class, ON, cls(Double.class));
        var unnamed = operation(Watcher.class, ON, new TypeInfo.TypeVariable("T", List.of()));
        var observing = Set.of("#on(java.lang.String)", "#on(java.lang.Integer)");

        assertTrue(observing.stream().anyMatch(otherEvent.key()::endsWith), otherEvent::key);
        assertTrue(observing.stream().anyMatch(unnamed.key()::endsWith), unnamed::key);
    }

    @Test
    @DisplayName("an observer whose method cannot be found runs by no provider method")
    void missingMethod() {
        var operation = operation(Watcher.class, "absent", cls(String.class));

        assertEquals(CodegenCoverage.Kind.NONE, operation.kind());
        assertEquals("observer absent()", operation.label());
    }
}
