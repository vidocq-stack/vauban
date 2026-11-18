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
package io.vidocq.vauban.processor.apt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.AnnotationValue;
import javax.lang.model.util.SimpleAnnotationValueVisitor14;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261118-06: a member's value read from the source of a constructor dispatches to the visitor as the compiler's
 * own values do, by the kind of what it holds.
 */
@DisplayName("BUG-20261118-06 - a value read from a constructor's source visits as the compiler's does")
class ConstructorTypeUsesValueTest {

    @Test
    @DisplayName("each kind of value reaches the visit method of its kind")
    void eachKindReachesItsVisitMethod() {
        var values = Stream.of(true, (byte) 1, 'c', 1.0d, 1.0f, 1, 1L, (short) 1, "s", List.of(), new Object())
                .map(ConstructorTypeUses.Value::new)
                .map(value -> value.accept(new KindVisitor(), null))
                .toList();
        assertEquals(List.of("boolean", "byte", "char", "double", "float", "int", "long", "short", "string", "array",
                "unknown"), values);
    }

    /** Names the visit method a value reaches. */
    private static final class KindVisitor extends SimpleAnnotationValueVisitor14<String, Void> {

        @Override
        public String visitBoolean(boolean b, Void unused) {
            return "boolean";
        }

        @Override
        public String visitByte(byte b, Void unused) {
            return "byte";
        }

        @Override
        public String visitChar(char c, Void unused) {
            return "char";
        }

        @Override
        public String visitDouble(double d, Void unused) {
            return "double";
        }

        @Override
        public String visitFloat(float f, Void unused) {
            return "float";
        }

        @Override
        public String visitInt(int i, Void unused) {
            return "int";
        }

        @Override
        public String visitLong(long i, Void unused) {
            return "long";
        }

        @Override
        public String visitShort(short s, Void unused) {
            return "short";
        }

        @Override
        public String visitString(String s, Void unused) {
            return "string";
        }

        @Override
        public String visitArray(List<? extends AnnotationValue> vals, Void unused) {
            return "array";
        }

        @Override
        public String visitUnknown(AnnotationValue av, Void unused) {
            return "unknown";
        }
    }
}
