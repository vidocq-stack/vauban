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

import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilderFactory;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;

public final class VaubanAnnotationBuilderFactory implements AnnotationBuilderFactory {

    @Override
    public AnnotationBuilder create(Class<? extends Annotation> annotationType) {
        return new VaubanAnnotationBuilder(annotationType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public AnnotationBuilder create(ClassInfo annotationType) {
        try {
            Class<?> clazz = Class.forName(annotationType.name());
            return new VaubanAnnotationBuilder((Class<? extends Annotation>) clazz);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Annotation type not found: " + annotationType.name(), e);
        }
    }
}
