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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observer methods a bean inherits from a superclass the index does not describe: found by reflection, with the
 * qualifiers of the observed parameter (and only those) and whether they observe asynchronously.
 */
@DisplayName("ObserverDisposerDiscovery: observers inherited from a class outside the index")
class InheritedObserverDiscoveryTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
    public @interface Loud {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Note {
    }

    /** Not indexed: its observers are only found by reflection. */
    public static class BaseListener {
        public void onLoud(@Observes @Loud @Note String message) {
            // observes loud messages
        }

        public void onLater(Integer ignored, @ObservesAsync Long number) {
            // observes longs asynchronously, second parameter
        }
    }

    @Dependent
    public static class Listener extends BaseListener {
    }

    private static List<ObserverDescriptor> observers() {
        var resource = Listener.class.getName().replace('.', '/') + ".class";
        try (var in = Listener.class.getClassLoader().getResourceAsStream(resource)) {
            var index = new IndexBuilder().add(ClassFileScanner.scan(in.readAllBytes())).build();
            return new BeanDiscovery(index).discoverObservers();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObserverDescriptor named(List<ObserverDescriptor> observers, String method) {
        return observers.stream().filter(o -> o.methodName().equals(method)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an inherited observer keeps the qualifiers of its observed parameter, not its other annotations")
    void inheritedQualifiers() {
        var loud = named(observers(), "onLoud");

        var names = loud.qualifiers().stream().map(q -> q.annotationName()).toList();
        assertTrue(names.contains(DotName.of(Loud.class.getName())), names::toString);
        assertFalse(names.contains(DotName.of(Note.class.getName())), names::toString);
        assertFalse(names.contains(DotName.of(Observes.class.getName())), names::toString);
        assertFalse(names.contains(DotName.of(Named.class.getName())), names::toString);
    }

    @Test
    @DisplayName("an inherited asynchronous observer is found by its @ObservesAsync parameter")
    void inheritedAsyncObserver() {
        var later = named(observers(), "onLater");

        assertTrue(later.async());
        assertEquals(DotName.of(Long.class.getName()),
                ((io.vidocq.vauban.indexer.model.TypeInfo.ClassType) later.eventType()).name());
    }
}
