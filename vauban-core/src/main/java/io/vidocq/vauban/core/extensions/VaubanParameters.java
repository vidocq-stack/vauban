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

import jakarta.enterprise.inject.build.compatible.spi.Parameters;

import java.lang.reflect.Array;
import java.util.Map;

/**
 * Simple implementation of {@link Parameters} backed by a Map.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class VaubanParameters implements Parameters {

    private final Map<String, Object> params;

    public VaubanParameters(Map<String, Object> params) {
        this.params = Map.copyOf(params);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        var value = params.get(key);
        if (value == null) {
            return null;
        }
        return convertIfNeeded(value, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
        var value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        return convertIfNeeded(value, type);
    }

    @SuppressWarnings("unchecked")
    private <T> T convertIfNeeded(Object value, Class<T> type) {
        // Handle array type mismatch (e.g., InvokerInfo[] → Invoker[])
        if (type.isArray() && value.getClass().isArray() && !type.isInstance(value)) {
            var componentType = type.getComponentType();
            int length = Array.getLength(value);
            var newArray = Array.newInstance(componentType, length);
            for (int i = 0; i < length; i++) {
                Array.set(newArray, i, convertIfNeeded(Array.get(value, i), componentType));
            }
            return (T) newArray;
        }
        // Convert AnnotationInfo to Annotation proxy when requested type is an annotation
        if (type.isAnnotation() && value instanceof jakarta.enterprise.lang.model.AnnotationInfo annInfo) {
            return (T) createAnnotationProxy(type, annInfo);
        }
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    private static <A extends java.lang.annotation.Annotation> A createAnnotationProxy(Class<?> type, jakarta.enterprise.lang.model.AnnotationInfo annInfo) {
        return (A) io.vidocq.vauban.core.annotation.AnnotationInstances.create(
                (Class<? extends java.lang.annotation.Annotation>) type,
                io.vidocq.vauban.core.langmodel.LangModelAnnotations.toIndex(annInfo),
                type.getClassLoader());
    }

}
