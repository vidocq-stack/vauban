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

import java.util.Objects;
import javax.naming.InvalidNameException;

/**
 * An absolute {@code java:} name: a namespace and a path in it, such as {@code java:comp/env/jdbc/orders},
 * whose path is {@code env/jdbc/orders}. The path neither starts nor ends with {@code /}; it is empty for the
 * namespace itself.
 *
 * @param namespace the namespace
 * @param path      the path in it, {@code ""} for the namespace itself
 */
public record JndiName(Namespace namespace, String path) {

    /**
     * A name, checked.
     *
     * @param namespace the namespace
     * @param path      the path in it, {@code ""} for the namespace itself
     */
    public JndiName {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("A path has no empty segment: " + path);
        }
    }

    /**
     * Parses an absolute name; a trailing {@code /} is dropped.
     *
     * @param name such as {@code java:comp/env/jdbc/orders}
     * @return the name
     * @throws InvalidNameException when the name is outside the four namespaces, or has an empty segment
     */
    public static JndiName parse(String name) throws InvalidNameException {
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (Namespace namespace : Namespace.values()) {
            String prefix = namespace.prefix();
            if (trimmed.equals(prefix)) {
                return new JndiName(namespace, "");
            }
            if (trimmed.startsWith(prefix + "/")) {
                String path = trimmed.substring(prefix.length() + 1);
                if (path.isEmpty() || path.startsWith("/") || path.contains("//")) {
                    throw new InvalidNameException(name + " has an empty segment");
                }
                return new JndiName(namespace, path);
            }
        }
        throw new InvalidNameException(name + " is outside java:comp, java:module, java:app and java:global");
    }

    /**
     * Whether this is a namespace itself, such as {@code java:comp}.
     *
     * @return {@code true} when the path is empty
     */
    public boolean isRoot() {
        return path.isEmpty();
    }

    /**
     * The name a relative name has under this one: {@code java:comp/env} and {@code jdbc/orders} give
     * {@code java:comp/env/jdbc/orders}.
     *
     * @param relative a relative name, without a leading {@code /}
     * @return the child name
     */
    public JndiName child(String relative) {
        return new JndiName(namespace, isRoot() ? relative : path + "/" + relative);
    }

    /**
     * Whether this name is {@code prefix} or under it, segment by segment: {@code java:comp/env} does not start
     * with {@code java:comp/en}.
     *
     * @param prefix the name to compare with
     * @return {@code true} when this name is {@code prefix} or one of its descendants
     */
    public boolean startsWith(JndiName prefix) {
        return namespace == prefix.namespace
                && (prefix.isRoot() || path.equals(prefix.path) || path.startsWith(prefix.path + "/"));
    }

    @Override
    public String toString() {
        return isRoot() ? namespace.prefix() : namespace.prefix() + "/" + path;
    }
}
