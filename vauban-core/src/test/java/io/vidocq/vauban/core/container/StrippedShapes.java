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

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Classes whose methods javac gives a {@code MethodParameters} attribute without being asked for parameter names:
 * the parameters it adds itself, which {@code jlink --strip-debug} then removes the marks of (BUG-20261118-60).
 */
final class StrippedShapes {

    private StrippedShapes() {
    }

    /** A bridge method: {@code apply(Object)}, whose parameter javac marks synthetic. */
    static final class Bridged implements Function<String, Integer> {
        @Override
        public Integer apply(String text) {
            return text.length();
        }
    }

    /** A bridge method over a covariant return and two parameters. */
    static final class BridgedTwice implements Comparable<BridgedTwice>, Supplier<String> {
        @Override
        public int compareTo(BridgedTwice other) {
            return 0;
        }

        @Override
        public String get() {
            return "";
        }
    }

    /** An enum: its constructor takes the name and the ordinal first, and {@code valueOf} a mandated name. */
    enum Sized {
        SMALL(1), LARGE(2);

        final int size;

        Sized(int size) {
            this.size = size;
        }
    }

    /** An inner member class: its constructor takes the outer instance first. */
    static final class Outer {
        final class Inner {
            final String text;

            Inner(String text) {
                this.text = text;
            }
        }
    }

    /** A record with a compact constructor: every parameter mandated. */
    record Point(int x, List<String> labels) {
        Point {
            labels = List.copyOf(labels);
        }
    }

    /** The local and anonymous classes of an instance method: an outer instance first, the captured values last. */
    static final class Locals {
        /** The classes this method declares: a local one, then an anonymous one. */
        List<Class<?>> declared(String captured, int count) {
            class Local {
                final String text;

                Local(String prefix) {
                    this.text = prefix + captured + count;
                }
            }
            var anonymous = new Object() {
                @Override
                public String toString() {
                    return captured + count;
                }
            };
            return List.of(Local.class, anonymous.getClass());
        }
    }

    /** A local class of a static method: no outer instance, a captured value. */
    static Class<?> staticLocal(String captured) {
        class Local {
            final String text;

            Local(StrippedShapes first) {
                this.text = captured + first;
            }
        }
        return Local.class;
    }

    /** A lambda capturing a value: a synthetic method taking it first. */
    static Function<String, String> capturing(String captured) {
        return text -> captured + text;
    }

    /** Every class above, the local and anonymous ones among them. */
    static List<Class<?>> all() {
        var locals = new Locals().declared("", 0);
        return List.of(StrippedShapes.class, Bridged.class, BridgedTwice.class, Sized.class, Outer.Inner.class,
                Point.class, Locals.class, locals.get(0), locals.get(1), staticLocal(""));
    }
}
