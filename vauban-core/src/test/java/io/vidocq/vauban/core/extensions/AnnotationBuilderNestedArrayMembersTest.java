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

import jakarta.enterprise.lang.model.AnnotationMember;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A nested annotation instance given to {@link VaubanAnnotationBuilder} keeps its enum and class array members. */
class AnnotationBuilderNestedArrayMembersTest {

    public enum Level { LOW, HIGH }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Inner {
        Level[] levels();

        Class<?>[] types();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Outer {
        Inner inner();
    }

    @Inner(levels = {Level.HIGH, Level.LOW}, types = {String.class})
    private static final class Carrier {
    }

    @Test
    void enumAndClassArrayMembersOfANestedInstanceAreKept() {
        var inner = Carrier.class.getAnnotation(Inner.class);

        var outer = new VaubanAnnotationBuilder(Outer.class).member("inner", inner).build();
        var nested = outer.member("inner").asNestedAnnotation();

        assertEquals(List.of("HIGH", "LOW"),
                nested.member("levels").asArray().stream().map(AnnotationMember::asEnumConstant).toList());
        assertEquals(List.of("java.lang.String"),
                nested.member("types").asArray().stream().map(m -> m.asType().toString()).toList());
    }
}
