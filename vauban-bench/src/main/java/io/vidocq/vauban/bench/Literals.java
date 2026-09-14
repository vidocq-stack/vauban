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
package io.vidocq.vauban.bench;

import java.lang.annotation.Annotation;

/**
 * Hand-written literals following the {@link Annotation} contract. {@code AnnotationLiteral} reads its
 * members by reflection in its own {@code equals} and {@code hashCode}; these do not, so the benchmarks
 * measure what the container does with a qualifier, not what the literal costs.
 */
final class Literals {

    private Literals() {
    }

    static final class ChannelLiteral implements Channel {
        private final String value;
        private final String note;

        ChannelLiteral(String value, String note) {
            this.value = value;
            this.note = note;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public String note() {
            return note;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Channel.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Channel that && value.equals(that.value()) && note.equals(that.note());
        }

        @Override
        public int hashCode() {
            return ((127 * "value".hashCode()) ^ value.hashCode()) + ((127 * "note".hashCode()) ^ note.hashCode());
        }

        @Override
        public String toString() {
            return "@" + Channel.class.getName() + "(value=\"" + value + "\", note=\"" + note + "\")";
        }
    }

    static final class RegionLiteral implements Region {
        private final Zone value;

        RegionLiteral(Zone value) {
            this.value = value;
        }

        @Override
        public Zone value() {
            return value;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return Region.class;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Region that && value == that.value();
        }

        @Override
        public int hashCode() {
            return (127 * "value".hashCode()) ^ value.hashCode();
        }

        @Override
        public String toString() {
            return "@" + Region.class.getName() + "(" + value + ")";
        }
    }
}
