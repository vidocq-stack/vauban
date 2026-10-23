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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.core.langmodel.types.VaubanParameterizedType;
import io.vidocq.vauban.core.langmodel.types.VaubanTypeVariable;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@link SyntheticMetadataSerializer} writes for builders given lang model types: class types by name, once,
 * every shape of observed type in a notation the boot reads back to the same index form (VAU-OBS-005), and the
 * qualifiers of an observer.
 */
class SyntheticMetadataWriteTest {

    private static final String STRING = "java.lang.String";

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tagged {
    }

    private static VaubanClassType classType(String name) {
        return new VaubanClassType(DotName.of(name), null);
    }

    private static Properties written(List<VaubanSyntheticBeanBuilder<?>> beans,
            List<VaubanSyntheticObserverBuilder<?>> observers) throws IOException {
        var out = new ByteArrayOutputStream();
        SyntheticMetadataSerializer.write(beans, observers, out);
        var props = new Properties();
        props.load(new ByteArrayInputStream(out.toByteArray()));
        return props;
    }

    @Test
    void langModelClassTypesAreWrittenOnceAndParameterizedOnesAreLeftOut() throws IOException {
        var bean = new VaubanSyntheticBeanBuilder<>(String.class);
        bean.type(String.class);
        bean.type(classType(STRING));
        bean.type(classType("java.lang.CharSequence"));
        bean.type(new VaubanParameterizedType(DotName.of("java.lang.Comparable"), List.of(classType(STRING)), null));

        var props = written(List.of(bean), List.of());

        assertEquals("java.lang.String,java.lang.Object,java.lang.CharSequence", props.getProperty("bean.0.types"));
    }

    @Test
    void aParameterizedObservedTypeIsWrittenByItsTypeNameWithItsQualifiers() throws IOException {
        var observer = new VaubanSyntheticObserverBuilder<>(
                new VaubanParameterizedType(DotName.of("java.util.List"), List.of(classType(STRING)), null));
        observer.qualifier(Tagged.class);

        var props = written(List.of(), List.of(observer));

        assertEquals("java.util.List<java.lang.String>", props.getProperty("observer.0.eventType"));
        assertEquals(Tagged.class.getName(), props.getProperty("observer.0.qualifiers"));
    }

    @Test
    void everyShapeOfObservedTypeIsWrittenAndReadBack() throws IOException {
        var types = new VaubanTypes(null);
        var number = types.of(Number.class);
        var observers = new ArrayList<VaubanSyntheticObserverBuilder<?>>();
        for (var observedType : List.of(
                types.parameterized(List.class, types.wildcardWithUpperBound(number)),
                types.parameterized(Comparable.class, types.wildcardWithLowerBound(types.of(Integer.class))),
                types.parameterized(Map.class, types.of(String.class),
                        types.parameterized(List.class, types.wildcardUnbounded())),
                types.ofArray(types.parameterized(List.class, String.class), 2),
                types.ofArray(new VaubanTypeVariable("N", List.of(number, types.of(Comparable.class))), 1),
                types.parameterized(List.class, types.ofArray(types.ofPrimitive(PrimitiveType.PrimitiveKind.INT), 1)),
                types.ofArray(types.of(String.class), 1))) {
            observers.add(new VaubanSyntheticObserverBuilder<>(observedType));
        }
        observers.add(new VaubanSyntheticObserverBuilder<>(String[].class));

        var props = written(List.of(), observers);
        var read = SyntheticMetadataSerializer.readObservers(props);

        assertEquals(List.of("java.util.List<? extends java.lang.Number>",
                        "java.lang.Comparable<? super java.lang.Integer>",
                        "java.util.Map<java.lang.String, java.util.List<?>>",
                        "java.util.List<java.lang.String>[][]",
                        "(N extends java.lang.Number & java.lang.Comparable)[]",
                        "java.util.List<int[]>",
                        "java.lang.String[]",
                        "[Ljava.lang.String;"),
                read.stream().map(SyntheticObserverDescriptor::eventTypeName).toList());
        assertEquals(observers.stream().map(VaubanSyntheticObserverBuilder::getEventTypeInfo).toList(),
                read.stream().map(SyntheticObserverDescriptor::eventTypeInfo).toList());
    }

    @Test
    void anObservedTypeWrittenByThePreviousVersionReadsTheSame() {
        var string = new TypeInfo.ClassType(DotName.of(STRING));
        var list = DotName.of("java.util.List");

        assertEquals(List.of(new TypeInfo.ClassType(DotName.of("com.example.Outer$Event")),
                        new TypeInfo.ClassType(DotName.of("[Ljava.lang.String;")),
                        new TypeInfo.ClassType(DotName.of("int")),
                        new TypeInfo.ParameterizedType(DotName.of("java.util.Map"), List.of(string,
                                new TypeInfo.ParameterizedType(list, List.of(string)))),
                        new TypeInfo.ParameterizedType(list, List.of(new TypeInfo.ArrayType(string, 1)))),
                Stream.of("com.example.Outer$Event", "[Ljava.lang.String;", "int",
                                "java.util.Map<java.lang.String, java.util.List<java.lang.String>>",
                                "java.util.List<java.lang.String[]>")
                        .map(name -> new SyntheticObserverDescriptor(name, null, null, null, 0, false).eventTypeInfo())
                        .toList());
    }

    @Test
    void aMalformedObservedTypeIsRefused() {
        for (var name : List.of("java.util.List<java.lang.String", "java.util.List<>", "(N)[]", "[Ljava.lang.String",
                "java.lang.String]", "")) {
            var descriptor = new SyntheticObserverDescriptor(name, null, null, null, 0, false);
            assertThrows(IllegalArgumentException.class, descriptor::eventTypeInfo, name);
        }
    }

    @Test
    void voidHasNoNotation() {
        var voidType = new TypeInfo.VoidType();

        var failure = assertThrows(IllegalArgumentException.class, () -> ObservedTypeNotation.write(voidType));

        assertEquals("void is the type of no event", failure.getMessage());
    }
}
