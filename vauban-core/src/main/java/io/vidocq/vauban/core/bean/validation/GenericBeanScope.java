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
package io.vidocq.vauban.core.bean.validation;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.List;

/**
 * CDI 4.1, 3.1: "If the managed bean class is a generic type, it must have scope {@code @Dependent}. If a managed bean
 * with a parameterized bean class declares any scope other than {@code @Dependent}, the container automatically
 * detects the problem and treats it as a definition error." The scope counts whether the class declares it or an
 * extension adds it at {@code @Enhancement}: Weld checks the scope of the annotated type the extensions modified
 * (BUG-20261117-35).
 */
public final class GenericBeanScope {

    private static final DotName DEPENDENT = DotName.of("jakarta.enterprise.context.Dependent");

    private GenericBeanScope() {
    }

    /** The definition error of the generic class {@code className} of scope {@code scope}, an annotation type. */
    public static String error(String className, String scope) {
        return "Managed bean " + className + " has type parameters and is not @Dependent: @" + scope;
    }

    /**
     * The definition errors of the managed beans of {@code beans} whose class, as {@code index} describes it, has type
     * parameters and whose scope, the extensions run, is not {@code @Dependent}.
     */
    public static List<String> errors(List<BeanDescriptor> beans, VaubanIndex index) {
        return beans.stream()
                .filter(bean -> bean.kind() == BeanDescriptor.BeanKind.MANAGED && bean.scope() != null
                        && !DEPENDENT.equals(bean.scope().annotationName()))
                .filter(bean -> index.getClassByName(bean.beanClass())
                        .filter(type -> !type.typeParameters().isEmpty()).isPresent())
                .map(bean -> error(bean.beanClass().value(), bean.scope().annotationName().value()))
                .distinct()
                .toList();
    }
}
