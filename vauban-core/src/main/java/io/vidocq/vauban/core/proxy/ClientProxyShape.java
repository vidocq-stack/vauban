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
package io.vidocq.vauban.core.proxy;

import io.vidocq.vauban.core.interceptor.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Neutral shape of a {@code <Bean>_ClientProxy} normal-scoped client proxy, and the
 * <strong>single naming authority</strong> for everything the proxy generators emit.
 *
 * <p>Three front-ends build this shape — the runtime generator (from {@link Class}, full
 * hierarchy walk + simplest-constructor defaults), the APT bytecode generator (from the
 * indexer {@code ClassInfo}, declared methods + no-arg constructor) and the APT source
 * renderer (from {@code TypeElement}). Their intentional semantic differences live in the
 * shape DATA ({@link #superCtorParams()}, the method set, {@code needsMethodHandle}); the
 * bytecode rendering itself is done once by {@link ClientProxyEmitter}.
 *
 * @param beanBinaryName binary name of the proxied bean (e.g. {@code "com.example.MyService"})
 * @param superCtorParams parameter types of the super constructor the no-arg proxy
 *        constructor calls with default values — empty means plain {@code super()}
 * @param methods the overridden methods, in emission order
 */
public record ClientProxyShape(
        String beanBinaryName,
        List<TypeRef> superCtorParams,
        List<ProxyMethodShape> methods) {

    /** Suffix of the generated proxy class: {@code <Bean>_ClientProxy}. */
    public static final String PROXY_SUFFIX = "_ClientProxy";
    /** The lazy contextual-instance supplier field. */
    public static final String FIELD_DELEGATE = "$$delegate";
    /** The delegate-supplier setter wired by the container after instantiation. */
    public static final String SET_DELEGATE_METHOD = "$$setDelegate";
    /** Prefix of the static-final {@code MethodHandle} fields ({@code $$mh_<name>_<n>}). */
    public static final String MH_FIELD_PREFIX = "$$mh_";
    /** Marker parameter type of the opt-in client-proxy entry constructor (Vidocq/vauban#24). */
    public static final String PROXY_LINK_CLASS = io.vidocq.vauban.api.ProxyLink.CLASS_NAME;

    /**
     * {@code true} when {@code ctor} is the opt-in {@code (ProxyLink)} client-proxy entry
     * constructor. Every front-end prefers it over the historical simplest-constructor
     * default chaining, and the container never selects it for constructor injection.
     */
    public static boolean isProxyLinkConstructor(java.lang.reflect.Constructor<?> ctor) {
        return ctor.getParameterCount() == 1
                && PROXY_LINK_CLASS.equals(ctor.getParameterTypes()[0].getName());
    }

    /** Index-model variant of {@link #isProxyLinkConstructor(java.lang.reflect.Constructor)}. */
    public static boolean isProxyLinkConstructor(io.vidocq.vauban.indexer.model.MethodInfo method) {
        return method.isConstructor()
                && method.parameters().size() == 1
                && method.parameters().getFirst().type()
                        instanceof io.vidocq.vauban.indexer.model.TypeInfo.ClassType ct
                && PROXY_LINK_CLASS.equals(ct.name().value());
    }

    public ClientProxyShape {
        java.util.Objects.requireNonNull(beanBinaryName, "beanBinaryName");
        superCtorParams = List.copyOf(superCtorParams);
        methods = List.copyOf(methods);
    }

    /**
     * One overridden method.
     *
     * @param name method name
     * @param returnType erased return type
     * @param params erased parameter types
     * @param thrownTypes erased checked-exception types — used by the source renderer to
     *        keep the {@code throws} contract; the bytecode emitter ignores it
     * @param needsMethodHandle {@code true} when the override must dispatch through a
     *        {@code MethodHandle.invokeExact} (protected/package-private member declared in
     *        another runtime package — JVMS §4.10.1.9 rejects a naive {@code invokevirtual})
     * @param interfaceOwner {@code null}, or the accessible interface to forward an interface
     *        default method through: a declaration the bean does not inherit (a private method of
     *        a superclass) shadows it for a call typed by the bean class, which the JVM resolves to
     *        that declaration and refuses (BUG-20261004-08)
     */
    public record ProxyMethodShape(
            String name,
            TypeRef returnType,
            List<TypeRef> params,
            List<TypeRef> thrownTypes,
            boolean needsMethodHandle,
            TypeRef interfaceOwner) {

        public ProxyMethodShape {
            java.util.Objects.requireNonNull(name, "name");
            java.util.Objects.requireNonNull(returnType, "returnType");
            params = List.copyOf(params);
            thrownTypes = List.copyOf(thrownTypes);
        }

        /** A method forwarded through the bean class. */
        public ProxyMethodShape(String name, TypeRef returnType, List<TypeRef> params,
                                List<TypeRef> thrownTypes, boolean needsMethodHandle) {
            this(name, returnType, params, thrownTypes, needsMethodHandle, null);
        }
    }

    /** Binary name of the generated proxy: {@code beanBinaryName + "_ClientProxy"}. */
    public String proxyClassName() {
        return beanBinaryName + PROXY_SUFFIX;
    }

    /**
     * {@code MethodHandle} field name per method, aligned with {@link #methods()} by index —
     * {@code null} for plain {@code invokevirtual} methods. The historical sequence is
     * {@code $$mh_<name>_<n>} where {@code n} only advances across MH-dispatched methods.
     */
    public List<String> methodHandleFieldNames() {
        var names = new ArrayList<String>(methods.size());
        int idx = 0;
        for (ProxyMethodShape m : methods) {
            names.add(m.needsMethodHandle() ? MH_FIELD_PREFIX + m.name() + "_" + (idx++) : null);
        }
        return java.util.Collections.unmodifiableList(names);
    }

    /** {@code true} when at least one method dispatches through a {@code MethodHandle}. */
    public boolean hasMethodHandles() {
        for (ProxyMethodShape m : methods) {
            if (m.needsMethodHandle()) return true;
        }
        return false;
    }
}
