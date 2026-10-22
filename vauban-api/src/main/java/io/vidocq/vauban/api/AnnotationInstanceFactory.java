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
package io.vidocq.vauban.api;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Builds an annotation instance from members written as Java source literals, such as a qualifier read from a
 * configuration file: {@code @Region("eu")} is {@code create(Region.class, Map.of("value", "\"eu\""), loader)}.
 *
 * <p>The instance equals the one the JDK builds from the same declaration. Vauban's container provides this
 * service; code that needs it finds it with {@link #find()}.
 */
public interface AnnotationInstanceFactory {

    /**
     * An instance of {@code type}, each member taking the value of its literal, or its default when left out.
     *
     * @param type    the annotation type
     * @param members member name to Java source literal: {@code "\"eu\""}, {@code 2}, {@code 3000000000L},
     *                {@code true}, {@code 'c'}, {@code com.acme.Zone.EU} (or {@code EU}), {@code com.acme.Foo.class}
     * @param loader  the class loader that sees the classes the literals name
     * @return the instance
     * @throws IllegalArgumentException when a member is unknown, is an array or an annotation, is left out
     *                                  without a default, or its literal is not of the member's type
     */
    <A extends Annotation> A create(Class<A> type, Map<String, String> members, ClassLoader loader);

    /**
     * The provider on the module path or the class path: looked up through this interface's own class loader,
     * then the thread's context class loader.
     *
     * @return the provider, or empty when no container module provides one
     */
    static Optional<AnnotationInstanceFactory> find() {
        var own = ServiceLoader.load(AnnotationInstanceFactory.class, AnnotationInstanceFactory.class.getClassLoader())
                .findFirst();
        if (own.isPresent()) {
            return own;
        }
        var context = Thread.currentThread().getContextClassLoader();
        return context == null ? Optional.empty()
                : ServiceLoader.load(AnnotationInstanceFactory.class, context).findFirst();
    }
}
