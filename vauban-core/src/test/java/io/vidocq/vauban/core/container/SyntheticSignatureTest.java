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

import io.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.Dependent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * VAU-BCE-009: the identity of a synthetic bean does not depend on how its types were given. Under
 * Vidocq the build records a bean whose types are read back at boot as classes, and the extension that
 * made it runs again at boot naming the same type through the language model, kept as an index type.
 * The two registrations are one bean; when their signatures differed, both were registered and every
 * {@code @Inject @ConfigProperties} of an application was ambiguous.
 */
class SyntheticSignatureTest {

    public static class Settings {
    }

    public static class Holder<T> {
    }

    @Test
    @DisplayName("a type given as a class and the same type given as an index type sign the same bean")
    void classAndIndexTypesSignTheSameBean() {
        var recorded = new VaubanSyntheticBeanBuilder<>(Object.class);
        recorded.type(Object.class);
        recorded.type(Settings.class);
        recorded.scope(Dependent.class);

        var replayed = new VaubanSyntheticBeanBuilder<>(Object.class);
        replayed.getIndexTypes().add(new TypeInfo.ClassType(DotName.of(Settings.class.getName())));
        replayed.scope(Dependent.class);

        assertEquals(SyntheticComponentRegistrar.syntheticSignature(recorded),
                SyntheticComponentRegistrar.syntheticSignature(replayed));
    }

    @Test
    @DisplayName("a parameterized index type is signed with its type arguments")
    void parameterizedIndexTypesKeepTheirArguments() {
        var ofString = new VaubanSyntheticBeanBuilder<>(Object.class);
        ofString.getIndexTypes().add(new TypeInfo.ParameterizedType(DotName.of(Holder.class.getName()),
                List.of(new TypeInfo.ClassType(DotName.of("java.lang.String")))));
        var ofInteger = new VaubanSyntheticBeanBuilder<>(Object.class);
        ofInteger.getIndexTypes().add(new TypeInfo.ParameterizedType(DotName.of(Holder.class.getName()),
                List.of(new TypeInfo.ClassType(DotName.of("java.lang.Integer")))));

        assertNotEquals(SyntheticComponentRegistrar.syntheticSignature(ofString),
                SyntheticComponentRegistrar.syntheticSignature(ofInteger));
    }

    @Test
    @DisplayName("every index type is written as the reflection API writes the same type")
    void indexTypesAreWrittenAsTypeNames() {
        var string = new TypeInfo.ClassType(DotName.of("java.lang.String"));
        assertEquals("java.lang.String[][]",
                SyntheticComponentRegistrar.typeName(new TypeInfo.ArrayType(string, 2)));
        assertEquals("int", SyntheticComponentRegistrar.typeName(
                new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT)));
        assertEquals("T", SyntheticComponentRegistrar.typeName(new TypeInfo.TypeVariable("T", List.of(string))));
        assertEquals("? extends java.lang.String",
                SyntheticComponentRegistrar.typeName(new TypeInfo.WildcardType(string, null)));
        assertEquals("? super java.lang.String",
                SyntheticComponentRegistrar.typeName(new TypeInfo.WildcardType(null, string)));
        assertEquals("?", SyntheticComponentRegistrar.typeName(new TypeInfo.WildcardType(null, null)));
        assertEquals("void", SyntheticComponentRegistrar.typeName(new TypeInfo.VoidType()));
        assertEquals("java.util.Map<java.lang.String, ?>", SyntheticComponentRegistrar.typeName(
                new TypeInfo.ParameterizedType(DotName.of("java.util.Map"),
                        List.of(string, new TypeInfo.WildcardType(null, null)))));
    }
}
