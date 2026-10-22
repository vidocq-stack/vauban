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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

/**
 * The default {@link NamingService}: {@code java:comp/env}, with {@code java:module/env} and
 * {@code java:app/env} as aliases since an application is a single component, answered by the
 * {@link JndiNameProvider} beans. Nothing is copied at startup: each lookup asks the providers.
 */
@ApplicationScoped
class DefaultNamingService implements NamingService {

    private static final List<String> ENVIRONMENTS = List.of("java:comp/env", "java:module/env", "java:app/env");
    private static final Set<String> CONTEXTS = Set.of(
            "java:comp", "java:module", "java:app", "java:comp/env", "java:module/env", "java:app/env");

    private final Instance<JndiNameProvider> providers;

    @Inject
    DefaultNamingService(@Any Instance<JndiNameProvider> providers) {
        this.providers = providers;
    }

    @Override
    public boolean isContext(String name) {
        return CONTEXTS.contains(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
    }

    @Override
    public Object lookup(String name) throws NamingException {
        String relative = relative(name);
        Object found = null;
        Class<?> answering = null;
        for (Instance.Handle<JndiNameProvider> handle : providers.handles()) {
            Optional<Object> value;
            try {
                value = handle.get().lookup(relative);
            } finally {
                if (handle.getBean().getScope() == Dependent.class) {
                    handle.destroy();
                }
            }
            if (value.isPresent()) {
                if (found != null) {
                    throw new NamingException(name + " is ambiguous: both " + answering.getName() + " and "
                            + handle.getBean().getBeanClass().getName() + " answer it");
                }
                found = value.get();
                answering = handle.getBean().getBeanClass();
            }
        }
        if (found == null) {
            throw new NameNotFoundException(name + " is bound to nothing in the running container");
        }
        return found;
    }

    private static String relative(String name) throws NameNotFoundException {
        for (String environment : ENVIRONMENTS) {
            if (name.startsWith(environment + "/")) {
                return name.substring(environment.length() + 1);
            }
        }
        throw new NameNotFoundException(name + " is outside java:comp/env, the namespace the container serves");
    }
}
