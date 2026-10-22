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
package io.vidocq.vauban.naming;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.naming.InvalidNameException;

/** What a {@code java:} name is bound to: a bean of the running container, or another name. */
public sealed interface JndiTarget {

    /**
     * A bean of the running container, resolved at each lookup as {@code select(type, qualifiers)} would.
     *
     * @param type        the bean type
     * @param qualifiers  its qualifiers; none means {@code @Default}
     * @param declaration how the bean was written, for messages: {@code javax.sql.DataSource @Named("orders")}
     */
    record Bean(Class<?> type, Set<Annotation> qualifiers, String declaration) implements JndiTarget {

        /**
         * A bean target.
         *
         * @param type        the bean type
         * @param qualifiers  its qualifiers; none means {@code @Default}
         * @param declaration how the bean was written, for messages
         */
        public Bean {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(declaration, "declaration");
            qualifiers = Collections.unmodifiableSet(new LinkedHashSet<>(qualifiers));
        }
    }

    /**
     * Another name, looked up in its turn: an alias.
     *
     * @param name the name linked to
     */
    record Link(JndiName name) implements JndiTarget {

        /**
         * A link.
         *
         * @param name the name linked to
         */
        public Link {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A bean of the running container.
     *
     * @param type       the bean type
     * @param qualifiers its qualifiers; none means {@code @Default}
     * @return the target
     */
    static JndiTarget bean(Class<?> type, Annotation... qualifiers) {
        String declaration = Stream.concat(Stream.of(type.getName()), Stream.of(qualifiers).map(Annotation::toString))
                .collect(Collectors.joining(" "));
        return new Bean(type, new LinkedHashSet<>(Set.of(qualifiers)), declaration);
    }

    /**
     * A bean written as text, as a configuration file names it:
     * {@code com.acme.Orders @Named("orders")}, {@code com.acme.Orders @com.acme.Region("eu") @com.acme.Tier(level = 2)}.
     * {@code @Named}, {@code @Default} and {@code @Any} take their short name, every other qualifier its fully
     * qualified name; a member is a Java source literal (string, number, boolean, char, enum constant, class).
     *
     * @param declaration the type, then its qualifiers; none means {@code @Default}
     * @param loader      the class loader that sees the type and the qualifiers
     * @return the target
     * @throws IllegalArgumentException when the declaration cannot be read, naming it and the reason
     */
    static JndiTarget bean(String declaration, ClassLoader loader) {
        return BeanDeclarations.parse(declaration, loader);
    }

    /**
     * Another name, looked up in its turn.
     *
     * @param name an absolute name, such as {@code java:comp/env/jdbc/orders}
     * @return the target
     * @throws IllegalArgumentException when the name is not an absolute {@code java:} name
     */
    static JndiTarget link(String name) {
        try {
            return new Link(JndiName.parse(name));
        } catch (InvalidNameException invalid) {
            throw new IllegalArgumentException("A link names an absolute java: name: " + invalid.getMessage(), invalid);
        }
    }
}
