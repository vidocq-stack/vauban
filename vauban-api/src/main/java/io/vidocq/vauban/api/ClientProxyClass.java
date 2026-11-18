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

import static java.lang.classfile.ClassFile.ACC_FINAL;

import java.util.Optional;

/**
 * The class a Vauban client proxy stands for, read from the proxy class.
 *
 * <p>A client proxy is named after the class it stands for, {@code <Bean>_ClientProxy}, in its package and class
 * loader, on every path that makes it: the annotation processor, {@code vauban:generate} and the run-time generator.
 * It extends that class as a rule, but not always: past a final method of the bean class, or for a final bean class,
 * it extends the nearest class above that a proxy can extend, or {@code Object}, and implements the bean class's
 * interfaces (BUG-20261118-20, BUG-20261118-21). Code that reads the annotations of the class behind an object, a
 * JAX-RS provider's say, asks this class rather than the proxy's superclass; code that casts the proxy to the class
 * it names, as an EL accessor table does, checks first that the proxy is an instance of it.
 *
 * <p>A producer's proxy is named after the produced class with the hash of its binary name,
 * {@code <Produced>$$<hash>_ClientProxy}, in the producer's package: it answers its superclass when that class hashes
 * so, the produced class. Past a final method of the produced class, or for a final produced class, it extends a class
 * above, and nothing in it names the produced class: it answers nothing.
 *
 * <p>Every path generates a client proxy {@code final}: a class merely named like one, which a subclass may extend,
 * is none. A final class of the application whose name holds {@code $$} and ends in {@code _ClientProxy} is read as a
 * producer's proxy: it answers its superclass only when the text after the last {@code $$} is the hash of that
 * class's name, and nothing otherwise.
 */
public final class ClientProxyClass {

    /** Suffix of every client proxy Vauban generates: {@code <Bean>_ClientProxy}. */
    public static final String SUFFIX = "_ClientProxy";

    private static final ClassValue<Optional<Class<?>>> STANDS_FOR = new ClassValue<>() {
        @Override
        protected Optional<Class<?>> computeValue(Class<?> type) {
            return standsFor(type);
        }
    };

    private ClientProxyClass() {
    }

    /**
     * The class {@code runtime} stands for when it is a Vauban client proxy: the bean class of a managed bean, which
     * the proxy may not extend, or the produced class of a producer. Read once per class.
     *
     * @param runtime a class, the runtime class of an object for instance
     * @return the class it stands for, or empty when it is no client proxy
     */
    public static Optional<Class<?>> beanClassOf(Class<?> runtime) {
        return STANDS_FOR.get(runtime);
    }

    private static Optional<Class<?>> standsFor(Class<?> type) {
        String name = type.getName();
        Class<?> parent = type.getSuperclass();
        if (parent == null || (type.getModifiers() & ACC_FINAL) == 0 || !name.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        String stem = name.substring(0, name.length() - SUFFIX.length());
        int produced = stem.lastIndexOf("$$");
        return produced >= 0 ? producedBy(parent, stem.substring(produced + 2)) : named(type, parent, stem);
    }

    /** The produced class of a producer's proxy: its superclass, when the hash of that class's name is {@code hash}. */
    private static Optional<Class<?>> producedBy(Class<?> parent, String hash) {
        return Integer.toHexString(parent.getName().hashCode()).equals(hash) ? Optional.of(parent) : Optional.empty();
    }

    /**
     * The class {@code beanName}, in the loader of {@code proxy}, when it is one {@code proxy} can stand for: of its
     * package, and a subclass of what it extends.
     */
    private static Optional<Class<?>> named(Class<?> proxy, Class<?> parent, String beanName) {
        ClassLoader loader = proxy.getClassLoader();
        if (loader == null) {
            return Optional.empty();
        }
        try {
            Class<?> bean = loader.loadClass(beanName);
            return bean.getPackageName().equals(proxy.getPackageName()) && parent.isAssignableFrom(bean)
                    ? Optional.of(bean) : Optional.empty();
        } catch (ClassNotFoundException _) {
            return Optional.empty();
        }
    }
}
