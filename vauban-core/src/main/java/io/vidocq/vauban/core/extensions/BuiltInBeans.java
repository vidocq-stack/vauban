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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The built-in beans of the container, described as the beans a {@code @Registration} method is given: CDI 4.1 calls
 * it for each bean of the deployment, the built-in ones included (BUG-20261117-06), the
 * {@code RequestContextController} among them (CDI 4.1, 6.5.8). Each is {@code @Dependent}, with
 * its interface and those above it as bean types, {@code Object} included, and {@code @Default} and {@code @Any} as
 * qualifiers, the {@code @Intercepted Bean} excepted, whose qualifier is {@code @Intercepted}. The processor, the
 * container at start and the build that freezes the extensions give the same.
 */
final class BuiltInBeans {

    private static final QualifierInstance INTERCEPTED =
            new QualifierInstance(DotName.of("jakarta.enterprise.inject.Intercepted"), Map.of());
    private static final Set<QualifierInstance> DEFAULT = Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY);

    /** The built-in beans, in a stable order. */
    static final List<BeanDescriptor> ALL = List.of(
            builtIn("", DEFAULT, jakarta.enterprise.inject.spi.BeanManager.class,
                    jakarta.enterprise.inject.spi.BeanContainer.class),
            builtIn("", DEFAULT, jakarta.enterprise.inject.Instance.class, jakarta.inject.Provider.class,
                    Iterable.class),
            builtIn("", DEFAULT, jakarta.enterprise.event.Event.class),
            builtIn("", DEFAULT, jakarta.enterprise.inject.spi.InjectionPoint.class),
            builtIn("", DEFAULT, jakarta.enterprise.inject.spi.Bean.class),
            builtIn("", DEFAULT, jakarta.enterprise.inject.spi.Interceptor.class),
            builtIn("", DEFAULT, jakarta.enterprise.context.control.RequestContextController.class),
            builtIn("@Intercepted", Set.of(INTERCEPTED), jakarta.enterprise.inject.spi.Bean.class));

    private BuiltInBeans() {
    }

    private static BeanDescriptor builtIn(String idSuffix, Set<QualifierInstance> qualifiers, Class<?> type,
                                          Class<?>... supertypes) {
        var name = DotName.of(type.getName());
        var types = new LinkedHashSet<TypeInfo>();
        types.add(new TypeInfo.ClassType(name));
        types.addAll(Arrays.stream(supertypes).map(supertype -> new TypeInfo.ClassType(DotName.of(supertype.getName())))
                .toList());
        types.add(new TypeInfo.ClassType(DotName.of(Object.class.getName())));
        return new BeanDescriptor(new BeanId(name.value() + idSuffix), name, BeanDescriptor.BeanKind.SYNTHETIC, types,
                qualifiers, ScopeInfo.DEPENDENT, false, 0, List.of(), null);
    }
}
