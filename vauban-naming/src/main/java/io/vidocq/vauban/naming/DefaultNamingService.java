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
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

/**
 * The default {@link NamingService}: the names the {@link JndiBindings} beans declare, in four distinct namespaces,
 * then the names the {@link JndiNameProvider} beans answer. The table of declared names is built once per container,
 * at the first lookup or when the container starts; a bean target is resolved at each lookup.
 */
@ApplicationScoped
class DefaultNamingService implements NamingService {

    private final Instance<JndiBindings> bindings;
    private final Instance<JndiNameProvider> providers;
    private volatile NameTable table;

    @Inject
    DefaultNamingService(@Any Instance<JndiBindings> bindings, @Any Instance<JndiNameProvider> providers) {
        this.bindings = bindings;
        this.providers = providers;
    }

    /** The declared names, built at the first call. */
    NameTable table() {
        NameTable built = table;
        if (built == null) {
            synchronized (this) {
                built = table;
                if (built == null) {
                    built = NameTable.of(bindings);
                    table = built;
                }
            }
        }
        return built;
    }

    /**
     * Checks every declared name when the container starts, as an injection point is checked: a name declared
     * twice, a bean target no bean or several beans match, a {@code @Dependent} one, a qualifier that is none, a
     * link to an undeclared name or a loop of links fails the start, every problem named at once.
     */
    void validate(@Observes Startup startup, BeanManager beanManager) {
        NameTable declared;
        try {
            declared = table();
        } catch (RuntimeException wrong) {
            throw new DeploymentException("The java: names cannot be declared: " + wrong.getMessage(), wrong);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<JndiName, JndiTarget> entry : declared.targets().entrySet()) {
            String problem = switch (entry.getValue()) {
                case JndiTarget.Bean bean -> problem(bean, beanManager);
                case JndiTarget.Link link -> problem(entry.getKey(), link, declared);
            };
            if (problem != null) {
                problems.add(entry.getKey() + " (declared by " + declared.declaringClass(entry.getKey()).getName()
                        + ") " + problem);
            }
        }
        if (!problems.isEmpty()) {
            throw new DeploymentException("Wrong java: names: " + String.join("; ", problems));
        }
    }

    private static String problem(JndiTarget.Bean bean, BeanManager beanManager) {
        for (Annotation qualifier : bean.qualifiers()) {
            if (!beanManager.isQualifier(qualifier.annotationType())) {
                return "is bound to " + bean.declaration() + ", but " + qualifier.annotationType().getName()
                        + " is not a qualifier";
            }
        }
        Instance<?> instance = beanManager.createInstance()
                .select(bean.type(), bean.qualifiers().toArray(Annotation[]::new));
        if (instance.isUnsatisfied()) {
            return "is bound to " + bean.declaration() + ", which no bean matches";
        }
        if (instance.isAmbiguous()) {
            return "is bound to " + bean.declaration() + ", which several beans match";
        }
        if (instance.getHandle().getBean().getScope() == Dependent.class) {
            return "is bound to " + bean.declaration() + ", a @Dependent bean: a lookup would create an instance "
                    + "that nobody destroys. Give the bean a scope, such as @ApplicationScoped or @Singleton";
        }
        return null;
    }

    private static String problem(JndiName name, JndiTarget.Link link, NameTable declared) {
        Set<JndiName> chain = new LinkedHashSet<>(List.of(name));
        JndiTarget target = link;
        while (target instanceof JndiTarget.Link next) {
            if (!chain.add(next.name())) {
                chain.add(next.name());
                return "is a loop of links: " + String.join(" -> ", chain.stream().map(JndiName::toString).toList());
            }
            Optional<JndiTarget> found = declared.target(next.name());
            if (found.isEmpty()) {
                return "links to " + next.name() + ", which is not declared";
            }
            target = found.get();
        }
        return null;
    }

    @Override
    public boolean isContext(String name) {
        JndiName parsed;
        try {
            parsed = JndiName.parse(name);
        } catch (InvalidNameException invalid) {
            return false;
        }
        return declared().isContext(parsed) || anyProvider(provider -> provider.isContext(parsed));
    }

    @Override
    public Object lookup(String name) throws NamingException {
        JndiName parsed;
        try {
            parsed = JndiName.parse(name);
        } catch (InvalidNameException invalid) {
            var outside = new NameNotFoundException(invalid.getMessage());
            outside.setRootCause(invalid);
            throw outside;
        }
        return lookup(parsed, new LinkedHashSet<>());
    }

    private Object lookup(JndiName name, Set<JndiName> followed) throws NamingException {
        Optional<JndiTarget> target = declaredOrFailure().target(name);
        if (target.isPresent()) {
            return switch (target.get()) {
                case JndiTarget.Bean bean -> bean(name, bean);
                case JndiTarget.Link link -> {
                    if (!followed.add(name)) {
                        throw new NamingException("The links of " + name + " loop: " + followed);
                    }
                    yield lookup(link.name(), followed);
                }
            };
        }
        return provided(name).orElseThrow(
                () -> new NameNotFoundException(name + " is bound to nothing in the running container"));
    }

    private static Object bean(JndiName name, JndiTarget.Bean bean) throws NamingException {
        Instance<?> instance = CDI.current().select(bean.type(), bean.qualifiers().toArray(Annotation[]::new));
        if (!instance.isResolvable()) {
            throw new NamingException(name + " is bound to " + bean.declaration() + ", which "
                    + (instance.isAmbiguous() ? "several beans match" : "no bean matches") + " in the running container");
        }
        var handle = instance.getHandle();
        if (handle.getBean().getScope() == Dependent.class) {
            throw new NamingException(name + " is bound to " + bean.declaration() + ", a @Dependent bean: a lookup "
                    + "would create an instance that nobody destroys. Give the bean a scope, such as "
                    + "@ApplicationScoped or @Singleton");
        }
        return handle.get();
    }

    private Optional<Object> provided(JndiName name) throws NamingException {
        Object found = null;
        Class<?> answering = null;
        for (Instance.Handle<JndiNameProvider> handle : providers.handles()) {
            Optional<Object> value;
            try {
                value = handle.get().lookup(name);
            } finally {
                destroyIfDependent(handle);
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
        return Optional.ofNullable(found);
    }

    private boolean anyProvider(Predicate<JndiNameProvider> test) {
        for (Instance.Handle<JndiNameProvider> handle : providers.handles()) {
            try {
                if (test.test(handle.get())) {
                    return true;
                }
            } finally {
                destroyIfDependent(handle);
            }
        }
        return false;
    }

    private static void destroyIfDependent(Instance.Handle<?> handle) {
        if (handle.getBean().getScope() == Dependent.class) {
            handle.destroy();
        }
    }

    /** The table for {@link #isContext}: a declaration that cannot be read answers as if nothing were declared. */
    private NameTable declared() {
        try {
            return table();
        } catch (RuntimeException wrong) {
            return NameTable.EMPTY;
        }
    }

    /** The table for a lookup: a declaration that cannot be read fails the lookup with its reason. */
    private NameTable declaredOrFailure() throws NamingException {
        try {
            return table();
        } catch (RuntimeException wrong) {
            var failure = new NamingException("The java: names cannot be declared: " + wrong.getMessage());
            failure.setRootCause(wrong);
            throw failure;
        }
    }
}
