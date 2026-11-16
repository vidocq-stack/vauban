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
package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.DotName;

/**
 * Unique bean identifier.
 */
public record BeanId(String value) {

    public static BeanId of(DotName className) {
        return new BeanId(className.value());
    }

    public static BeanId ofProducerMethod(DotName className, String methodName) {
        return new BeanId(className.value() + "#" + methodName);
    }

    /**
     * The id of one of overloaded producer methods, which share their name: the name followed by the erased parameter
     * types, as {@link Class#getTypeName()} spells them, so that each has an id of its own (BUG-20261116-11). The
     * container finds the method again with {@code VaubanContainer.isProducerMethod}.
     */
    public static BeanId ofOverloadedProducerMethod(DotName className, String methodName,
            java.util.List<String> parameterTypes) {
        return new BeanId(className.value() + "#" + methodName + "(" + String.join(",", parameterTypes) + ")");
    }

    public static BeanId ofProducerField(DotName className, String fieldName) {
        return new BeanId(className.value() + "." + fieldName);
    }
}
