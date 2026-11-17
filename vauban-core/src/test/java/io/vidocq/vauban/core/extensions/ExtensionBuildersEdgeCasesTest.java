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

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.types.VaubanArrayType;
import io.vidocq.vauban.core.langmodel.types.VaubanClassType;
import io.vidocq.vauban.core.langmodel.types.VaubanPrimitiveType;
import io.vidocq.vauban.core.langmodel.types.VaubanWildcardType;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Best-effort paths of the extension-facing builders and of the lang model to index type mapping. */
class ExtensionBuildersEdgeCasesTest {

    private static final DotName MISSING = DotName.of("com.acme.Missing");

    @Test
    void aSyntheticBeanTypeTheBeanLoaderCannotSeeIsLeftOut() {
        var indexed = new ClassInfo(MISSING, DotName.of("java.lang.Object"), List.of(), 0x0001, List.of(), List.of(),
                List.of(), ClassInfo.ClassKind.CLASS);
        var lookup = new IndexLookup(new IndexBuilder().add(indexed).build());
        var builder = new VaubanSyntheticBeanBuilder<>(String.class);
        var before = Set.copyOf(builder.getTypes());

        builder.type(new VaubanClassInfo(indexed, lookup));

        assertEquals(before, Set.copyOf(builder.getTypes()));
    }

    @Test
    void anAnnotationThatCannotNameItsTypeIsNotAdded() {
        var config = new VaubanMethodConfig(new ReflectionMethodInfo(Object.class.getMethods()[0]));
        Annotation broken = new Annotation() {
            @Override
            public Class<? extends Annotation> annotationType() {
                throw new IllegalStateException("no type");
            }
        };

        config.addAnnotation(broken);

        assertTrue(config.getAddedAnnotations().isEmpty());
        assertTrue(config.getAddedAnnotationInstances().isEmpty());
    }

    @Test
    void nestedArraysMapToOneArrayOfTheirTotalDimensions() {
        var intMatrix = new VaubanArrayType(new VaubanArrayType(
                new VaubanPrimitiveType(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT))));
        var strings = new VaubanArrayType(new VaubanClassType(DotName.of("java.lang.String"), null));

        assertEquals(new TypeInfo.ArrayType(new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT), 2),
                LangModelTypeMapper.toIndexType(intMatrix));
        assertEquals(new TypeInfo.ArrayType(new TypeInfo.ClassType(DotName.of("java.lang.String")), 1),
                LangModelTypeMapper.toIndexType(strings));
    }

    /**
     * {@code ? extends Object}, as the language model represents {@code ?} and as {@code Types.wildcardUnbounded()}
     * gives it, is the wildcard without bounds in the index, as the class files and reflection read it: a type an
     * extension takes from the language model maps back to the type the index holds (BUG-20261117-34).
     */
    @Test
    void theUnboundedWildcardMapsBackToAWildcardWithoutBounds() {
        var number = new TypeInfo.ClassType(DotName.of("java.lang.Number"));
        var numberType = new VaubanClassType(number.name(), null);

        assertEquals(new TypeInfo.WildcardType(null, null), LangModelTypeMapper.toIndexType(
                new VaubanWildcardType(new VaubanClassType(DotName.of("java.lang.Object"), null), null)));
        assertEquals(new TypeInfo.WildcardType(number, null),
                LangModelTypeMapper.toIndexType(new VaubanWildcardType(numberType, null)));
        assertEquals(new TypeInfo.WildcardType(null, number),
                LangModelTypeMapper.toIndexType(new VaubanWildcardType(null, numberType)));
    }
}
