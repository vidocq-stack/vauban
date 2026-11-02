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

import io.vidocq.vauban.core.bean.validation.IndexClassValidator;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Stereotype;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-PERF-008: the definition rules read from the index report what the reflective rules report, class by class;
 * a class the index cannot decide is left to reflection.
 */
@DisplayName("VAU-PERF-008 - definition rules on the index")
class IndexClassValidatorTest {

    @Stereotype @Named("named") @Retention(RetentionPolicy.RUNTIME)
    @interface NamedStereotype {}

    @Stereotype @RequestScoped @Retention(RetentionPolicy.RUNTIME)
    @interface RequestStereotype {}

    @Stereotype @ApplicationScoped @Retention(RetentionPolicy.RUNTIME)
    @interface ApplicationStereotype {}

    @Dependent
    public static class Valid {
        @Inject Event<String> event;
        @Inject Instance<List<String>> instances;
        @Produces List<String> names() { return List.of(); }
    }

    @Dependent
    public static class RawEvent {
        @Inject Event event;
        @Inject void init(Instance instance) { /* no behaviour needed */ }
    }

    @Dependent
    public static class WildcardProducer {
        @Produces List<?> any() { return List.of(); }
        @Produces <T> T[] array() { return null; }
    }

    @Dependent
    public static class GenericInitializer {
        @Inject <T> void init(List<T> values) { /* no behaviour needed */ }
    }

    @ApplicationScoped
    public static class PublicField {
        public String value;
    }

    @Dependent @Typed(Runnable.class)
    public static class NotASupertype {}

    @Dependent
    public static class DisposerWithInjectionPoint {
        @Produces String make() { return ""; }
        void dispose(@Disposes String value, InjectionPoint point) { /* no behaviour needed */ }
    }

    @NamedStereotype
    public static class StereotypeWithName {}

    @RequestStereotype @ApplicationStereotype
    public static class ConflictingScopes {}

    @ApplicationScoped
    public static class GenericScoped<T> {}

    @Dependent
    public static class NamedWithoutValue {
        @Inject void init(@Named String value) { /* no behaviour needed */ }
    }

    /** Its superclass is not in the index: the index cannot tell which annotations it inherits. */
    @Dependent
    public static class OutsideTheIndex extends java.util.AbstractList<String> {
        @Override public String get(int index) { return null; }
        @Override public int size() { return 0; }
    }

    private static final List<Class<?>> CLASSES = List.of(NamedStereotype.class, RequestStereotype.class,
            ApplicationStereotype.class, Valid.class, RawEvent.class, WildcardProducer.class,
            GenericInitializer.class, PublicField.class, NotASupertype.class, DisposerWithInjectionPoint.class,
            StereotypeWithName.class, ConflictingScopes.class, GenericScoped.class, NamedWithoutValue.class,
            OutsideTheIndex.class);

    private static VaubanIndex index() {
        var builder = new IndexBuilder();
        for (var type : CLASSES) {
            try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                builder.add(ClassFileScanner.scan(in.readAllBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return builder.build();
    }

    private static IndexClassValidator validator(VaubanIndex index) {
        return new IndexClassValidator(index, name -> {
            try {
                return Class.forName(name, false, IndexClassValidatorTest.class.getClassLoader());
            } catch (ClassNotFoundException _) {
                return null;
            }
        }, name -> false);
    }

    @ParameterizedTest
    @ValueSource(classes = {Valid.class, RawEvent.class, WildcardProducer.class, GenericInitializer.class,
            PublicField.class, NotASupertype.class, DisposerWithInjectionPoint.class, StereotypeWithName.class,
            ConflictingScopes.class, GenericScoped.class, NamedWithoutValue.class})
    @DisplayName("the index reports what reflection reports")
    void sameErrorsAsReflection(Class<?> beanClass) {
        var index = index();
        var fromIndex = validator(index).validate(index.getClassByName(DotName.of(beanClass.getName())).orElseThrow())
                .orElseThrow();
        Set<String> reflective = new TreeSet<>(ReflectionValidator.validateWithReflection(List.of(beanClass)));

        assertEquals(reflective, new TreeSet<>(fromIndex));
        assertEquals(beanClass != Valid.class, !fromIndex.isEmpty(), fromIndex::toString);
    }

    @Test
    @DisplayName("a class whose superclass is not in the index is left to reflection")
    void undecidableIsLeftToReflection() {
        var index = index();
        var info = index.getClassByName(DotName.of(OutsideTheIndex.class.getName())).orElseThrow();

        assertTrue(validator(index).validate(info).isEmpty());
    }

    @Test
    @DisplayName("an index with erased member types is left to reflection")
    void erasedIndexIsLeftToReflection() {
        var index = index();
        var scanned = index.getClassByName(DotName.of(RawEvent.class.getName())).orElseThrow();
        var erased = new io.vidocq.vauban.indexer.model.ClassInfo(scanned.name(), scanned.superName(),
                scanned.interfaces(), scanned.accessFlags(), scanned.fields(), scanned.methods(), scanned.annotations(),
                scanned.kind(), scanned.simpleName(), scanned.typeParameters());

        assertFalse(erased.genericMembers());
        assertTrue(validator(index).validate(erased).isEmpty());
    }
}
