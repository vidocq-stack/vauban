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

import jakarta.enterprise.inject.spi.CDI;
import java.util.Hashtable;
import javax.naming.Binding;
import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.OperationNotSupportedException;

/**
 * A read-only context of the {@code java:} namespace, rooted at {@code base} ({@code ""} for the initial
 * context). It keeps its environment and its base name only: every lookup asks the {@link NamingService}
 * bean of the container running at that moment.
 */
final class CdiContext implements Context {

    private static final NameParser PARSER = CompositeName::new;

    private final Hashtable<Object, Object> environment;
    private final String base;

    CdiContext(Hashtable<?, ?> environment, String base) {
        this.environment = environment == null ? new Hashtable<>() : new Hashtable<>(environment);
        this.base = base;
    }

    @Override
    public Object lookup(String name) throws NamingException {
        if (name.isEmpty()) {
            return new CdiContext(environment, base);
        }
        String absolute = base.isEmpty() || name.startsWith("java:") ? name : base + "/" + name;
        NamingService naming = naming();
        return naming.isContext(absolute) ? new CdiContext(environment, absolute) : naming.lookup(absolute);
    }

    @Override
    public Object lookup(Name name) throws NamingException {
        return lookup(name.toString());
    }

    @Override
    public Object lookupLink(String name) throws NamingException {
        return lookup(name);
    }

    @Override
    public Object lookupLink(Name name) throws NamingException {
        return lookup(name);
    }

    /** The naming service of the running container: never kept, a container may stop and another start. */
    private static NamingService naming() throws NamingException {
        CDI<Object> cdi;
        try {
            cdi = CDI.current();
        } catch (IllegalStateException none) {
            var failure = new NoInitialContextException(
                    "No CDI container is running: the java: namespace answers from the running container");
            failure.setRootCause(none);
            throw failure;
        }
        var naming = cdi.select(NamingService.class);
        if (!naming.isResolvable()) {
            throw new NoInitialContextException("The running CDI container has "
                    + (naming.isAmbiguous() ? "several " : "no ") + NamingService.class.getName() + " bean");
        }
        return naming.get();
    }

    @Override
    public void bind(Name name, Object obj) throws NamingException {
        throw readOnly();
    }

    @Override
    public void bind(String name, Object obj) throws NamingException {
        throw readOnly();
    }

    @Override
    public void rebind(Name name, Object obj) throws NamingException {
        throw readOnly();
    }

    @Override
    public void rebind(String name, Object obj) throws NamingException {
        throw readOnly();
    }

    @Override
    public void unbind(Name name) throws NamingException {
        throw readOnly();
    }

    @Override
    public void unbind(String name) throws NamingException {
        throw readOnly();
    }

    @Override
    public void rename(Name oldName, Name newName) throws NamingException {
        throw readOnly();
    }

    @Override
    public void rename(String oldName, String newName) throws NamingException {
        throw readOnly();
    }

    @Override
    public void destroySubcontext(Name name) throws NamingException {
        throw readOnly();
    }

    @Override
    public void destroySubcontext(String name) throws NamingException {
        throw readOnly();
    }

    @Override
    public Context createSubcontext(Name name) throws NamingException {
        throw readOnly();
    }

    @Override
    public Context createSubcontext(String name) throws NamingException {
        throw readOnly();
    }

    private static OperationNotSupportedException readOnly() {
        return new OperationNotSupportedException("The java: namespace is read-only: its names are CDI beans");
    }

    @Override
    public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
        throw notListed();
    }

    @Override
    public NamingEnumeration<NameClassPair> list(String name) throws NamingException {
        throw notListed();
    }

    @Override
    public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
        throw notListed();
    }

    @Override
    public NamingEnumeration<Binding> listBindings(String name) throws NamingException {
        throw notListed();
    }

    private static OperationNotSupportedException notListed() {
        return new OperationNotSupportedException(
                "The java: namespace resolves each name when it is looked up and cannot list them");
    }

    @Override
    public NameParser getNameParser(Name name) {
        return PARSER;
    }

    @Override
    public NameParser getNameParser(String name) {
        return PARSER;
    }

    @Override
    public Name composeName(Name name, Name prefix) throws NamingException {
        return ((Name) prefix.clone()).addAll(name);
    }

    @Override
    public String composeName(String name, String prefix) {
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    @Override
    public Object addToEnvironment(String propName, Object propVal) {
        return environment.put(propName, propVal);
    }

    @Override
    public Object removeFromEnvironment(String propName) {
        return environment.remove(propName);
    }

    @Override
    public Hashtable<?, ?> getEnvironment() {
        return new Hashtable<>(environment);
    }

    @Override
    public void close() {
        // nothing held
    }

    @Override
    public String getNameInNamespace() {
        return base;
    }
}
