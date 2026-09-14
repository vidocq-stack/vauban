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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Safety net for vauban#70: the qualifier instances a bean exposes through {@link Bean#getQualifiers()}
 * are {@link Annotation}s, so application code compares them with literals and stores them in hash
 * sets. They must honour the {@link Annotation#equals} and {@link Annotation#hashCode} contract, member
 * values included. The expected instance is the one the JDK builds from the bean class itself.
 *
 * <p>Each qualifier carries one member kind, so a defect in one kind cannot hide behind another. A test
 * disabled with a BUG id reproduces a defect logged in {@code BUG.md}.
 */
@DisplayName("vauban#70 safety net: Bean#getQualifiers() instances honour java.lang.annotation.Annotation")
class BeanQualifiersContractTest {

    public enum Hue { RED, BLUE }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public @interface Inner {
        String value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Simple {
        String value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Hued {
        Hue value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Noted {
        String value();

        @Nonbinding String note();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Codes {
        int[] value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Wrapped {
        Inner value();
    }

    public interface Shape {
    }

    @Simple("s") @Hued(Hue.RED) @Noted(value = "v", note = "n") @Codes({1, 2}) @Wrapped(@Inner("i"))
    @Dependent
    public static class Specimen implements Shape {
    }

    /** Same binding value as {@link Specimen}'s {@code @Noted}, another non-binding note. Never a bean. */
    @Noted(value = "v", note = "other")
    static final class OtherNote {
    }

    private VaubanContainer container;

    @BeforeEach
    void boot() {
        container = VaubanContainer.builder().addBeanClass(Specimen.class).build();
    }

    @AfterEach
    void close() {
        container.close();
    }

    /** The instance the container exposes for {@code type} on {@link Specimen}. */
    private static Annotation containerBuilt(Class<? extends Annotation> type) {
        var beanManager = CDI.current().getBeanManager();
        Bean<?> bean = beanManager.resolve(beanManager.getBeans(Shape.class, Any.Literal.INSTANCE));
        return bean.getQualifiers().stream()
                .filter(qualifier -> qualifier.annotationType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no @" + type.getSimpleName() + " among " + bean.getQualifiers()));
    }

    /** The instance the JDK builds for {@code type} on {@link Specimen}. */
    private static <A extends Annotation> A jdkBuilt(Class<A> type) {
        return Specimen.class.getAnnotation(type);
    }

    @Test
    @DisplayName("String member: equal to the JDK instance both ways, same hash code")
    void stringMember() {
        var built = containerBuilt(Simple.class);
        assertEquals(jdkBuilt(Simple.class), built);
        assertEquals(built, jdkBuilt(Simple.class));
        assertEquals(jdkBuilt(Simple.class).hashCode(), built.hashCode());
    }

    @Test
    @DisplayName("enum member: equal to the JDK instance both ways, same hash code, same value")
    void enumMember() {
        var built = containerBuilt(Hued.class);
        assertEquals(jdkBuilt(Hued.class), built);
        assertEquals(built, jdkBuilt(Hued.class));
        assertEquals(jdkBuilt(Hued.class).hashCode(), built.hashCode());
        assertEquals(Hue.RED, ((Hued) built).value());
    }

    @Test
    @DisplayName("a @Nonbinding member still takes part in equals")
    @Disabled("BUG-20260914-03: container-built qualifiers leave @Nonbinding members out of equals")
    void nonbindingMemberInEquals() {
        var built = containerBuilt(Noted.class);
        assertEquals(jdkBuilt(Noted.class), built);
        assertFalse(built.equals(OtherNote.class.getAnnotation(Noted.class)),
                "Annotation#equals compares every member; @Nonbinding only matters to CDI resolution");
    }

    @Test
    @DisplayName("a @Nonbinding member still takes part in hashCode")
    @Disabled("BUG-20260914-03: container-built qualifiers leave @Nonbinding members out of hashCode")
    void nonbindingMemberInHashCode() {
        assertEquals(jdkBuilt(Noted.class).hashCode(), containerBuilt(Noted.class).hashCode());
    }

    @Test
    @DisplayName("int[] member: returned with its declared type")
    @Disabled("BUG-20260914-03: container-built qualifiers turn int[] members into Object[]")
    void primitiveArrayMemberValue() {
        assertArrayEquals(new int[] {1, 2}, ((Codes) containerBuilt(Codes.class)).value());
    }

    @Test
    @DisplayName("int[] member: equal to the JDK instance both ways, same hash code")
    @Disabled("BUG-20260914-03: container-built qualifiers turn int[] members into Object[]")
    void primitiveArrayMemberContract() {
        var built = containerBuilt(Codes.class);
        assertEquals(jdkBuilt(Codes.class), built);
        assertEquals(built, jdkBuilt(Codes.class));
        assertEquals(jdkBuilt(Codes.class).hashCode(), built.hashCode());
    }

    @Test
    @DisplayName("nested annotation member: returns the nested annotation")
    @Disabled("BUG-20260914-03: container-built qualifiers turn nested annotation members into null")
    void nestedAnnotationMemberValue() {
        assertEquals(jdkBuilt(Wrapped.class).value(), ((Wrapped) containerBuilt(Wrapped.class)).value());
    }

    @Test
    @DisplayName("nested annotation member: equal to the JDK instance both ways")
    @Disabled("BUG-20260914-03: container-built qualifiers turn nested annotation members into null")
    void nestedAnnotationMemberContract() {
        var built = containerBuilt(Wrapped.class);
        assertEquals(jdkBuilt(Wrapped.class), built);
        assertEquals(built, jdkBuilt(Wrapped.class));
    }
}
