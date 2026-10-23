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
package io.vidocq.vauban.processor.codegen.provider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** An annotation type with a default of every kind a member can take, rendered into source by the processor. */
@Retention(RetentionPolicy.RUNTIME)
public @interface EveryMemberKind {

    boolean flag() default true;

    byte small() default 1;

    char letter() default 'x';

    short medium() default 2;

    int count() default 3;

    long big() default 4L;

    float ratio() default 1.5f;

    double precise() default 2.5;

    String text() default "t";

    Class<?> type() default String.class;

    Class<? super Integer> numeric() default Number.class;

    ElementType target() default ElementType.TYPE;

    Shade shade() default @Shade;

    String[] texts() default {"a", "b"};

    long[] bigs() default {5L};

    Class<?>[] types() default {Integer.class};

    int required();
}
