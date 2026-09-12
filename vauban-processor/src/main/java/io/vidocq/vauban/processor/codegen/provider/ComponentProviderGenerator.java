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
package io.vidocq.vauban.processor.codegen.provider;

import io.vidocq.vauban.indexer.codegen.Component;
import io.vidocq.vauban.indexer.codegen.FieldInject;
import io.vidocq.vauban.indexer.codegen.MethodInvoke;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a per-module {@code _VaubanComponents} class implementing
 * {@code io.vidocq.vauban.api.VaubanComponentProvider}. The generated class instantiates the
 * module's components <em>in-module</em> ({@code new X(args…)}), so the container can create them
 * without reflection and without {@code opens … to io.vidocq.vauban.core}.
 *
 * <p>Emitted as Java <strong>source</strong> (not bytecode): the switch-on-name is trivial and
 * stays readable, and javac compiles it in a subsequent APT round. Two paths are generated:
 * <ul>
 *   <li>{@code create(String)} — no-arg components: {@code new X()};</li>
 *   <li>{@code create(String, Object[])} — components with an injected constructor:
 *       {@code new X((Dep) args[0], …)} where the arguments have already been resolved by
 *       {@code vauban-core} (the provider only performs the in-module {@code new}).</li>
 * </ul>
 * Only components whose constructor parameters are nameable reference types are listed; anything
 * else falls back to reflection at runtime.
 *
 * <pre>{@code
 * package app;
 * public final class _VaubanComponents implements io.vidocq.vauban.api.VaubanComponentProvider {
 *     public Object create(String className) {
 *         return switch (className) {
 *             case "app.HelloResource" -> new app.HelloResource();
 *             default -> null;
 *         };
 *     }
 *     public Object create(String className, Object[] args) {
 *         if (args == null || args.length == 0) return create(className);
 *         return switch (className) {
 *             case "app.Service" -> new app.Service((app.Repo) args[0]);
 *             default -> null;
 *         };
 *     }
 * }
 * }</pre>
 *
 * <p>The descriptor records ({@link Component}, {@link FieldInject}, {@link MethodInvoke}) are
 * defined in {@code io.vidocq.vauban.indexer.codegen} and shared with the bytecode generator
 * ({@code ComponentProviderClassGenerator}) via the common {@code vauban-indexer} dependency.
 */
public final class ComponentProviderGenerator {

    public static final String SIMPLE_NAME = "_VaubanComponents";
    private static final String SPI = "io.vidocq.vauban.api.VaubanComponentProvider";

    private ComponentProviderGenerator() {}

    /**
     * A build-time client proxy for a normal-scoped PRODUCER whose produced type is a fully-public
     * class from another module (issue #42). Unlike a managed-bean proxy, the {@code key} the
     * container looks up ({@code <producedType>_ClientProxy}, derived from the produced type by
     * {@code RuntimeClientProxyGenerator.proxyClassName}) differs from {@code proxyFqn}, the actual
     * proxy class the provider instantiates, which lives in the producer's own package.
     *
     * @param key      the {@code createClientProxy} lookup key = {@code <producedType FQN>_ClientProxy}
     * @param proxyFqn the fully-qualified generated proxy class placed in the producer's package
     */
    public record ProducerProxy(String key, String proxyFqn) {}

    /** A generated source file: its fully-qualified class name and its textual content. */
    public record Generated(String className, String source) {}

    /**
     * Back-compatible no-arg entry point: every component is instantiated via {@code new X()}.
     *
     * @param packageName   package the provider lives in (a package of the current module)
     * @param componentFqns fully-qualified names of components instantiable via {@code new X()}
     */
    public static Generated generate(String packageName, List<String> componentFqns) {
        return generateFrom(packageName, componentFqns.stream()
                .map(fqn -> new Component(fqn, List.of())).toList());
    }

    /**
     * @param packageName package the provider lives in (a package of the current module)
     * @param components  components to instantiate, no-arg and/or injected-constructor
     */
    public static Generated generateFrom(String packageName, List<Component> components) {
        return generateFrom(packageName, components, List.of());
    }

    /**
     * @param packageName  package the provider lives in (a package of the current module)
     * @param components   components to instantiate, no-arg and/or injected-constructor
     * @param fieldInjects non-private, non-static {@code @Inject} fields in the provider's own
     *                     package that the provider can assign directly (no reflection, no opens)
     */
    public static Generated generateFrom(String packageName, List<Component> components,
            List<FieldInject> fieldInjects) {
        return generateFrom(packageName, components, fieldInjects, List.of());
    }

    /**
     * @param packageName   package the provider lives in (a package of the current module)
     * @param components    components to instantiate, no-arg and/or injected-constructor
     * @param fieldInjects  non-private, non-static {@code @Inject} fields in the provider's own
     *                      package that the provider can assign directly (no reflection, no opens)
     * @param methodInvokes methods in the provider's own package that the provider can invoke
     *                      directly (no reflection, no opens): producers, observers, disposers,
     *                      lifecycle callbacks, and initializers
     */
    public static Generated generateFrom(String packageName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes) {
        return generateFrom(packageName, components, fieldInjects, methodInvokes, List.of());
    }

    /**
     * @param packageName     package the provider lives in (a package of the current module)
     * @param components      components to instantiate, no-arg and/or injected-constructor
     * @param fieldInjects    non-private, non-static {@code @Inject} fields in the provider's own
     *                        package that the provider can assign directly (no reflection, no opens)
     * @param methodInvokes   methods in the provider's own package the provider can call directly
     *                        (producers, observers, disposers, lifecycle callbacks, initializers)
     * @param clientProxyFqns fully-qualified {@code <Bean>_ClientProxy} names (top-level normal-scoped
     *                        beans of this package) the provider instantiates in-module — {@code new
     *                        <Bean>_ClientProxy()} + {@code $$setDelegate(delegate)} — so the container
     *                        creates the proxy without reflection and without exporting the package
     */
    public static Generated generateFrom(String packageName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes,
            List<String> clientProxyFqns) {
        return generateFrom(packageName, components, fieldInjects, methodInvokes,
                clientProxyFqns, List.of());
    }

    /**
     * Full overload also emitting {@link ProducerProxy} cases (issue #42): build-time proxies for
     * normal-scoped producers of fully-public external classes, keyed by the produced type but
     * instantiated from the producer's own package.
     */
    public static Generated generateFrom(String packageName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes,
            List<String> clientProxyFqns, List<ProducerProxy> producerProxies) {
        var className = packageName.isEmpty() ? SIMPLE_NAME : packageName + "." + SIMPLE_NAME;
        var noArg = components.stream().filter(Component::noArg).toList();
        var withArgs = components.stream().filter(c -> !c.noArg()).toList();

        var sb = new StringBuilder();
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("// Generated by Vauban APT — instantiates this module's components in-module so\n");
        sb.append("// the container needs no `opens ... to io.vidocq.vauban.core`. Do not edit.\n");
        // @Vetoed: never a CDI bean — so even Weld in bean-discovery-mode=all skips it. Vauban loads
        // it via ServiceLoader, not by scanning, so vetoing it is neutral here.
        sb.append("@jakarta.enterprise.inject.Vetoed\n");
        sb.append("public final class ").append(SIMPLE_NAME)
                .append(" implements ").append(SPI).append(" {\n");

        sb.append("    @Override\n");
        sb.append("    public Object create(String className) {\n");
        sb.append("        return switch (className) {\n");
        for (var c : noArg) {
            sb.append("            case \"").append(c.fqn()).append("\" -> new ")
                    .append(c.sourceFqn()).append("();\n");
        }
        sb.append("            default -> null;\n");
        sb.append("        };\n");
        sb.append("    }\n");

        if (!withArgs.isEmpty()) {
            sb.append("    @Override\n");
            sb.append("    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            sb.append("    public Object create(String className, Object[] args) {\n");
            sb.append("        if (args == null || args.length == 0) return create(className);\n");
            sb.append("        return switch (className) {\n");
            for (var c : withArgs) {
                sb.append("            case \"").append(c.fqn()).append("\" -> new ")
                        .append(c.sourceFqn()).append("(");
                var params = c.ctorParamTypes();
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("(").append(params.get(i)).append(") args[").append(i).append("]");
                }
                sb.append(");\n");
            }
            sb.append("            default -> null;\n");
            sb.append("        };\n");
            sb.append("    }\n");
        }

        if (!fieldInjects.isEmpty()) {
            // Group field injections by declaring class for the outer switch.
            Map<String, List<FieldInject>> byClass = fieldInjects.stream()
                    .collect(Collectors.groupingBy(FieldInject::declaringClassFqn,
                            java.util.LinkedHashMap::new, Collectors.toList()));

            sb.append("    @Override\n");
            sb.append("    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            sb.append("    public boolean injectField(Object bean, String className, String fieldName, Object value) {\n");
            sb.append("        switch (className) {\n");
            for (var entry : byClass.entrySet()) {
                sb.append("            case \"").append(entry.getKey()).append("\" -> {\n");
                sb.append("                var b = (").append(entry.getKey()).append(") bean;\n");
                sb.append("                switch (fieldName) {\n");
                for (var fi : entry.getValue()) {
                    sb.append("                    case \"").append(fi.fieldName()).append("\" -> { ")
                            .append("b.").append(fi.fieldName())
                            .append(" = (").append(fi.fieldTypeErasure()).append(") value; return true; }\n");
                }
                sb.append("                    default -> { return false; }\n");
                sb.append("                }\n");
                sb.append("            }\n");
            }
            sb.append("            default -> { return false; }\n");
            sb.append("        }\n");
            sb.append("    }\n");
        }

        if (!methodInvokes.isEmpty()) {
            // Group method invocations by declaring class, preserving insertion order.
            Map<String, List<MethodInvoke>> byClass = methodInvokes.stream()
                    .collect(Collectors.groupingBy(MethodInvoke::declaringClassFqn,
                            java.util.LinkedHashMap::new, Collectors.toList()));

            sb.append("    @Override\n");
            sb.append("    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            sb.append("    public Object invoke(Object target, String className, String methodId, Object[] args) {\n");
            sb.append("        switch (className) {\n");
            for (var entry : byClass.entrySet()) {
                var declClass = entry.getKey();
                sb.append("            case \"").append(declClass).append("\" -> {\n");
                sb.append("                switch (methodId) {\n");
                for (var mi : entry.getValue()) {
                    sb.append("                    case \"").append(mi.methodId()).append("\" -> {\n");
                    // Build the actual call expression
                    var call = new StringBuilder();
                    if (mi.isStatic()) {
                        call.append(declClass).append(".").append(mi.methodName()).append("(");
                    } else {
                        call.append("((").append(declClass).append(") target).").append(mi.methodName()).append("(");
                    }
                    var erasures = mi.paramErasures();
                    for (int i = 0; i < erasures.size(); i++) {
                        if (i > 0) call.append(", ");
                        call.append("(").append(erasures.get(i)).append(") args[").append(i).append("]");
                    }
                    call.append(")");

                    if (mi.isVoid()) {
                        sb.append("                        ").append(call).append(";\n");
                        sb.append("                        return null;\n");
                    } else {
                        sb.append("                        return ").append(call).append(";\n");
                    }
                    sb.append("                    }\n");
                }
                sb.append("                    default -> { return ").append(SPI).append(".NOT_INVOKED; }\n");
                sb.append("                }\n");
                sb.append("            }\n");
            }
            sb.append("            default -> { return ").append(SPI).append(".NOT_INVOKED; }\n");
            sb.append("        }\n");
            sb.append("    }\n");
        }

        if (!clientProxyFqns.isEmpty() || !producerProxies.isEmpty()) {
            // In-module client-proxy instantiation for this package's normal-scoped beans:
            // `new <Bean>_ClientProxy()` + `$$setDelegate(delegate)` (both in-package, the proxy being
            // a sibling generated source), so the container creates the proxy without reflection and
            // the bean package needs no `opens`/`exports`. Producer proxies (issue #42) are keyed by
            // the produced type but instantiated from this (the producer's) package.
            sb.append("    @Override\n");
            sb.append("    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            sb.append("    public Object createClientProxy(String proxyClassName, java.util.function.Supplier<?> delegate) {\n");
            sb.append("        switch (proxyClassName) {\n");
            for (var proxyFqn : clientProxyFqns) {
                sb.append("            case \"").append(proxyFqn).append("\" -> {\n");
                sb.append("                var p = new ").append(proxyFqn).append("();\n");
                sb.append("                p.$$setDelegate(delegate);\n");
                sb.append("                return p;\n");
                sb.append("            }\n");
            }
            for (var pp : producerProxies) {
                sb.append("            case \"").append(pp.key()).append("\" -> {\n");
                sb.append("                var p = new ").append(pp.proxyFqn()).append("();\n");
                sb.append("                p.$$setDelegate(delegate);\n");
                sb.append("                return p;\n");
                sb.append("            }\n");
            }
            sb.append("            default -> { return null; }\n");
            sb.append("        }\n");
            sb.append("    }\n");
        }

        sb.append("}\n");
        return new Generated(className, sb.toString());
    }
}
