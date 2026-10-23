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
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@link SyntheticMetadataSerializer} writes for builders given lang model types: class types by name, once,
 * a parameterized observed type by its type name, and the qualifiers of an observer.
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
}
