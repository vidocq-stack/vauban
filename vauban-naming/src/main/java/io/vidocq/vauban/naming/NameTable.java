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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.naming.InvalidNameException;

/** The names the {@link JndiBindings} beans of one container declare, and who declared each. */
final class NameTable {

    /** Contexts with nothing declared in them: the four namespaces and their environments. */
    private static final Set<String> ALWAYS_CONTEXTS = Set.of("java:comp", "java:module", "java:app", "java:global",
            "java:comp/env", "java:module/env", "java:app/env");

    /** No name declared: the namespaces and their environments are the only contexts. */
    static final NameTable EMPTY = new NameTable(Map.of(), Map.of());

    private final Map<JndiName, JndiTarget> targets;
    private final Map<JndiName, Class<?>> declaredBy;

    private NameTable(Map<JndiName, JndiTarget> targets, Map<JndiName, Class<?>> declaredBy) {
        this.targets = Collections.unmodifiableMap(targets);
        this.declaredBy = Collections.unmodifiableMap(declaredBy);
    }

    /**
     * Asks every declaring bean for its names.
     *
     * @throws IllegalStateException when a name is declared twice, or a declaring bean binds an invalid name
     */
    static NameTable of(Instance<JndiBindings> bindings) {
        Map<JndiName, JndiTarget> targets = new LinkedHashMap<>();
        Map<JndiName, Class<?>> declaredBy = new LinkedHashMap<>();
        for (Instance.Handle<JndiBindings> handle : bindings.handles()) {
            Class<?> declaring = handle.getBean().getBeanClass();
            try {
                handle.get().bind(new Binder() {
                    @Override
                    public Binder bind(String name, JndiTarget target) {
                        JndiName parsed = parse(name, declaring);
                        Class<?> first = declaredBy.putIfAbsent(parsed, declaring);
                        if (first != null) {
                            throw new IllegalStateException(parsed + " is declared twice, by " + first.getName()
                                    + " and by " + declaring.getName());
                        }
                        targets.put(parsed, target);
                        return this;
                    }
                });
            } finally {
                if (handle.getBean().getScope() == Dependent.class) {
                    handle.destroy();
                }
            }
        }
        return new NameTable(targets, declaredBy);
    }

    private static JndiName parse(String name, Class<?> declaring) {
        try {
            return JndiName.parse(name);
        } catch (InvalidNameException invalid) {
            throw new IllegalArgumentException(declaring.getName() + " declares an invalid name: "
                    + invalid.getMessage(), invalid);
        }
    }

    Optional<JndiTarget> target(JndiName name) {
        return Optional.ofNullable(targets.get(name));
    }

    /** The declared names, in the order they were declared. */
    Map<JndiName, JndiTarget> targets() {
        return targets;
    }

    Class<?> declaringClass(JndiName name) {
        return declaredBy.get(name);
    }

    /** A namespace, an environment, or a proper prefix of a declared name. */
    boolean isContext(JndiName name) {
        if (ALWAYS_CONTEXTS.contains(name.toString())) {
            return true;
        }
        for (JndiName declared : targets.keySet()) {
            if (!declared.equals(name) && declared.startsWith(name)) {
                return true;
            }
        }
        return false;
    }
}
