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
package io.vidocq.vauban.indexer.model;

import java.util.List;

public sealed interface AnnotationValue {

    record StringVal(String value) implements AnnotationValue {}

    record BooleanVal(boolean value) implements AnnotationValue {}

    record ByteVal(byte value) implements AnnotationValue {}

    record CharVal(char value) implements AnnotationValue {}

    record ShortVal(short value) implements AnnotationValue {}

    record IntVal(int value) implements AnnotationValue {}

    record LongVal(long value) implements AnnotationValue {}

    record FloatVal(float value) implements AnnotationValue {}

    record DoubleVal(double value) implements AnnotationValue {}

    record ClassVal(DotName className) implements AnnotationValue {}

    record EnumVal(DotName enumType, String constantName) implements AnnotationValue {}

    record AnnotationVal(AnnotationInfo annotation) implements AnnotationValue {}

    record ArrayVal(List<AnnotationValue> values) implements AnnotationValue {
        public ArrayVal {
            values = List.copyOf(values);
        }
    }
}
