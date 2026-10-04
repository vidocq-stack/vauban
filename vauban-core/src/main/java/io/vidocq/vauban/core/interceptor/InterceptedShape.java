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
package io.vidocq.vauban.core.interceptor;

import java.util.List;

/**
 * Neutral shape for an intercepted bean subclass.
 *
 * <p>{@code beanBinaryName} is the binary name of the bean (e.g. {@code "com.example.MyService"}).
 * The generated subclass will be named {@code beanBinaryName + "$$Intercepted"}.
 *
 * <p>This record is also the <strong>single naming authority</strong> for the generated members
 * ({@code $$Intercepted} suffix, {@code $$super$} bridges, {@code $$ti$} glue, {@code $$init} and
 * the {@code $$…} fields): both the bytecode renderer ({@link InterceptedEmitter}) and the source
 * renderer ({@code InterceptedSourceRenderer} in vauban-processor) must take these names from
 * here. VAU-INT-001 had to be fixed twice because each renderer carried its own copy of the
 * {@code $$ti$} naming logic.
 */
public record InterceptedShape(
        String beanBinaryName,
        List<CtorShape> constructors,
        List<MethodShape> methods) {

    /** Suffix of the generated subclass: {@code <bean>$$Intercepted}. */
    public static final String SUBCLASS_SUFFIX = "$$Intercepted";
    /** Prefix of the per-method bridge calling {@code super.<name>(…)} directly. */
    public static final String SUPER_BRIDGE_PREFIX = "$$super$";
    /** Prefix of the per-method static glue lifted into a {@code TargetInvoker}. */
    public static final String TI_PREFIX = "$$ti$";
    /** Name of the post-construction wiring method. */
    public static final String INIT_METHOD = "$$init";

    public static final String FIELD_MANAGER = "$$manager";
    public static final String FIELD_BINDINGS = "$$bindings";
    public static final String FIELD_CONSTRUCTOR_BINDINGS = "$$constructorBindings";
    public static final String FIELD_CONTEXT = "$$context";

    public InterceptedShape {
        java.util.Objects.requireNonNull(beanBinaryName, "beanBinaryName");
        constructors = List.copyOf(constructors);
        methods = List.copyOf(methods);
    }

    /** Binary name of the generated subclass: {@code beanBinaryName + "$$Intercepted"}. */
    public String subclassName() {
        return beanBinaryName + SUBCLASS_SUFFIX;
    }

    /**
     * The interfaces the generated subclass lists among its direct superinterfaces, in method order:
     * those through which a bridge reaches a shadowed default method explicitly
     * ({@link MethodShape#defaultOwner()}, BUG-20261004-08). Empty for nearly every bean.
     */
    public List<TypeRef> explicitDefaultOwners() {
        var owners = new java.util.LinkedHashSet<TypeRef>();
        for (MethodShape m : methods) {
            if (m.defaultOwner() != null) owners.add(m.defaultOwner());
        }
        return List.copyOf(owners);
    }

    /**
     * {@link #explicitDefaultOwners()} as a rendered subclass lists them: each as the bean
     * parameterises it ({@link MethodShape#defaultOwnerSource()}).
     */
    public List<String> explicitDefaultOwnerSources() {
        var owners = new java.util.LinkedHashMap<TypeRef, String>();
        for (MethodShape m : methods) {
            if (m.defaultOwner() != null) owners.putIfAbsent(m.defaultOwner(), m.defaultOwnerSource());
        }
        return List.copyOf(owners.values());
    }

    /** Name of the {@code $$super$<name>} bridge for a bean method. */
    public static String superBridgeName(String methodName) {
        return SUPER_BRIDGE_PREFIX + methodName;
    }

    /**
     * Unique {@code $$ti$} glue method name per method, aligned with {@link #methods()} by index.
     * Non-overloaded names stay {@code $$ti$<name>} (byte-for-byte stable); overloaded names get a
     * {@code $<occurrence>} suffix so the erased {@code (Object,Object[])Object} glues do not
     * collide (VAU-INT-001). The {@code $$super$<name>} bridges keep distinct descriptors and need
     * no suffix.
     */
    public List<String> targetInvokerNames() {
        var total = new java.util.HashMap<String, Integer>();
        for (MethodShape m : methods) total.merge(m.name(), 1, Integer::sum);
        var seen = new java.util.HashMap<String, Integer>();
        var names = new java.util.ArrayList<String>(methods.size());
        for (MethodShape m : methods) {
            int occ = seen.merge(m.name(), 1, Integer::sum) - 1;
            String base = TI_PREFIX + m.name();
            names.add(total.get(m.name()) > 1 ? base + "$" + occ : base);
        }
        return List.copyOf(names);
    }
}
