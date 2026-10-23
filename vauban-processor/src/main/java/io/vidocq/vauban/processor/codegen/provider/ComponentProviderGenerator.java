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

    /**
     * The provider a module's test compilation emits (vauban#101): a second {@code _VaubanComponents} would shadow
     * the main sources' one when the tests run. The container finds it through the main provider's lookup.
     */
    public static final String TEST_SIMPLE_NAME = "_VaubanComponentsTests";
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
     * @param fieldInjects assignable fields in the provider's own package: source-level
     *                     {@code @Inject} fields and public mutable runtime-BCE candidates
     */
    public static Generated generateFrom(String packageName, List<Component> components,
            List<FieldInject> fieldInjects) {
        return generateFrom(packageName, components, fieldInjects, List.of());
    }

    /**
     * @param packageName   package the provider lives in (a package of the current module)
     * @param components    components to instantiate, no-arg and/or injected-constructor
     * @param fieldInjects  assignable fields in the provider's own package: source-level
     *                      {@code @Inject} fields and public mutable runtime-BCE candidates
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
     * @param fieldInjects    assignable fields in the provider's own package: source-level
     *                        {@code @Inject} fields and public mutable runtime-BCE candidates
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
        return generateFrom(packageName, components, fieldInjects, methodInvokes,
                clientProxyFqns, producerProxies, new AnnotationArtefacts.Rendered("", ""));
    }

    /**
     * Full overload also carrying what this package's own annotation types declare (vauban#70): the
     * metadata, a reader that calls their members directly and a literal for each one. They are
     * rendered here, in the annotation type's own package, so a package-private qualifier is covered
     * like any other — the container then matches, injects and hands out that qualifier with no
     * reflection at all.
     */
    public static Generated generateFrom(String packageName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes,
            List<String> clientProxyFqns, List<ProducerProxy> producerProxies,
            AnnotationArtefacts.Rendered annotations) {
        return generateFrom(packageName, components, fieldInjects, methodInvokes, clientProxyFqns,
                producerProxies, annotations, java.util.Set.of());
    }

    /**
     * Full overload, {@code bytecodeClasses} holding the binary names of the generated subclasses and
     * proxies the processor emitted as bytecode, because Java source cannot declare one of their
     * overrides (BUG-20261004-09). It emits them in the last round, so javac never reads them back and
     * this source cannot name them: it instantiates them through its own {@code MethodHandles.lookup()}
     * instead — the provider's full-privilege lookup in its own package, so still no {@code opens}.
     */
    public static Generated generateFrom(String packageName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes,
            List<String> clientProxyFqns, List<ProducerProxy> producerProxies,
            AnnotationArtefacts.Rendered annotations, java.util.Set<String> bytecodeClasses) {
        return generateFrom(packageName, SIMPLE_NAME, new Contents(components, fieldInjects, methodInvokes,
                clientProxyFqns, producerProxies, annotations, bytecodeClasses));
    }

    /**
     * What a provider covers, beside its package and name.
     *
     * @param components      components to instantiate, no-arg and/or injected-constructor
     * @param fieldInjects    assignable fields in the provider's own package
     * @param methodInvokes   methods in the provider's own package the provider can call directly
     * @param clientProxyFqns the {@code <Bean>_ClientProxy} classes the provider instantiates in-module
     * @param producerProxies the build-time proxies of normal-scoped producers (issue #42)
     * @param annotations     what this package's own annotation types declare (vauban#70)
     * @param bytecodeClasses the binary names of the classes the processor emitted as bytecode
     */
    public record Contents(List<Component> components, List<FieldInject> fieldInjects,
            List<MethodInvoke> methodInvokes, List<String> clientProxyFqns, List<ProducerProxy> producerProxies,
            AnnotationArtefacts.Rendered annotations, java.util.Set<String> bytecodeClasses) {}

    /** {@link #generateFrom(String, List, List, List, List, List, AnnotationArtefacts.Rendered, java.util.Set)}
     * under {@code simpleName}: {@link #SIMPLE_NAME}, or {@link #TEST_SIMPLE_NAME} for a test compilation. */
    public static Generated generateFrom(String packageName, String simpleName, Contents contents) {
        var className = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        var components = contents.components();

        var sb = new StringBuilder();
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("// Generated by Vauban APT — instantiates this module's components in-module so\n");
        sb.append("// the container needs no `opens ... to io.vidocq.vauban.core`. Do not edit.\n");
        // @Vetoed: never a CDI bean — so even Weld in bean-discovery-mode=all skips it. Vauban loads
        // it via ServiceLoader, not by scanning, so vetoing it is neutral here.
        sb.append("@jakarta.enterprise.inject.Vetoed\n");
        sb.append("public final class ").append(simpleName)
                .append(" implements ").append(SPI).append(" {\n");

        appendCreate(sb, components.stream().filter(Component::noArg).toList(), contents.bytecodeClasses());
        var withArgs = components.stream().filter(c -> !c.noArg()).toList();
        if (!withArgs.isEmpty()) {
            appendCreateWithArgs(sb, withArgs, contents.bytecodeClasses());
        }
        if (!contents.fieldInjects().isEmpty()) {
            appendInjectField(sb, components, contents.fieldInjects());
        }
        if (!contents.methodInvokes().isEmpty()) {
            appendInvoke(sb, contents.methodInvokes());
        }
        if (!contents.clientProxyFqns().isEmpty() || !contents.producerProxies().isEmpty()) {
            appendCreateClientProxy(sb, contents);
        }

        // grantModuleLookup(): the provider's own lookup, handed to the container through a grant only the
        // container can create; it passes it on for this package's managed classes (ModuleLookups).
        sb.append(OVERRIDE);
        sb.append("    public void grantModuleLookup(io.vidocq.vauban.api.ModuleLookupGrant grant) {\n");
        sb.append("        grant.accept(java.lang.invoke.MethodHandles.lookup());\n");
        sb.append(CLOSE_4);

        appendCoverage(sb, contents);

        if (!contents.bytecodeClasses().isEmpty()) {
            appendBytecodeInstantiation(sb);
        }

        sb.append(contents.annotations().methods());
        sb.append(contents.annotations().literals());

        // Sneaky-throw, as in the intercepted subclasses: T appears only in `throws T`, so by JLS §18.4
        // it resolves to RuntimeException at every call site — the SPI methods need no throws clause,
        // yet a constructor's or a bean method's checked exception propagates unwrapped (vauban#145).
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {\n");
        sb.append("        throw (T) t;\n");
        sb.append(CLOSE_4);
        sb.append("}\n");
        return new Generated(className, sb.toString());
    }

    private static final String OVERRIDE = "    @Override\n";
    private static final String SUPPRESS_RAW = "    @SuppressWarnings({\"unchecked\", \"rawtypes\"})\n";
    private static final String TRY_OPEN = "        try {\n";
    private static final String CATCH_SNEAKY = "        } catch (Throwable $$t) {\n"
            + "            throw sneaky($$t);\n"
            + "        }\n";
    private static final String CASE_OPEN = "            case \"";
    private static final String CASE_BLOCK = "\" -> {\n";
    private static final String DEFAULT_NULL = "            default -> null;\n";
    private static final String SWITCH_EXPRESSION_END = "        };\n";
    private static final String NO_ARG_CALL = "();\n";
    private static final String CLOSE_4 = "    }\n";
    private static final String CLOSE_8 = "        }\n";
    private static final String CLOSE_12 = "            }\n";
    private static final String CLOSE_16 = "                }\n";
    private static final String COVERAGE_INDENT = "                ";
    private static final String COVERAGE_SEPARATOR = ",\n";

    /** {@code create(String)}: the no-arg components. */
    private static void appendCreate(StringBuilder sb, List<Component> noArg, java.util.Set<String> bytecodeClasses) {
        sb.append(OVERRIDE);
        sb.append("    public Object create(String className) {\n");
        // A constructor may declare checked exceptions (vauban#145): see sneaky() below.
        sb.append(TRY_OPEN);
        sb.append("        return switch (className) {\n");
        for (var c : noArg) {
            if (bytecodeClasses.contains(c.fqn())) {
                sb.append(CASE_OPEN).append(c.fqn()).append("\" -> ").append(CONSTRUCT)
                        .append("(\"").append(c.fqn()).append("\", new Class<?>[0], new Object[0]);\n");
            } else {
                sb.append(CASE_OPEN).append(c.fqn()).append("\" -> new ")
                        .append(c.sourceFqn()).append(NO_ARG_CALL);
            }
        }
        sb.append(DEFAULT_NULL);
        sb.append(SWITCH_EXPRESSION_END);
        sb.append(CATCH_SNEAKY);
        sb.append(CLOSE_4);
    }

    /** {@code create(String, Object[])}: the components with an injected constructor. */
    private static void appendCreateWithArgs(StringBuilder sb, List<Component> withArgs,
            java.util.Set<String> bytecodeClasses) {
        sb.append(OVERRIDE);
        sb.append(SUPPRESS_RAW);
        sb.append("    public Object create(String className, Object[] args) {\n");
        sb.append("        if (args == null || args.length == 0) return create(className);\n");
        sb.append(TRY_OPEN);
        sb.append("        return switch (className) {\n");
        for (var c : withArgs) {
            if (bytecodeClasses.contains(c.fqn())) {
                sb.append(CASE_OPEN).append(c.fqn()).append("\" -> ").append(CONSTRUCT)
                        .append("(\"").append(c.fqn()).append("\", new Class<?>[] {")
                        .append(c.ctorParamTypes().stream().map(t -> t + ".class")
                                .collect(Collectors.joining(", ")))
                        .append("}, args);\n");
            } else {
                sb.append(CASE_OPEN).append(c.fqn()).append("\" -> new ")
                        .append(c.sourceFqn()).append("(").append(castArgs(c.ctorParamTypes())).append(");\n");
            }
        }
        sb.append(DEFAULT_NULL);
        sb.append(SWITCH_EXPRESSION_END);
        sb.append(CATCH_SNEAKY);
        sb.append(CLOSE_4);
    }

    /** {@code (T0) args[0], (T1) args[1], …}: each argument cast to its parameter type. */
    private static String castArgs(List<String> parameterTypes) {
        var sb = new StringBuilder();
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(").append(parameterTypes.get(i)).append(") args[").append(i).append("]");
        }
        return sb.toString();
    }

    /** {@code injectField(...)}: the assignable fields, grouped by declaring class for the outer switch. */
    private static void appendInjectField(StringBuilder sb, List<Component> components,
            List<FieldInject> fieldInjects) {
        Map<String, List<FieldInject>> byClass = fieldInjects.stream()
                .collect(Collectors.groupingBy(FieldInject::declaringClassFqn,
                        java.util.LinkedHashMap::new, Collectors.toList()));

        sb.append(OVERRIDE);
        sb.append(SUPPRESS_RAW);
        sb.append("    public boolean injectField(Object bean, String className, String fieldName, Object value) {\n");
        sb.append("        switch (className) {\n");
        for (var entry : byClass.entrySet()) {
            sb.append(CASE_OPEN).append(entry.getKey()).append(CASE_BLOCK);
            var ownerSourceName = components.stream().filter(c -> c.fqn().equals(entry.getKey()))
                    .map(Component::sourceFqn).findFirst().orElse(entry.getKey());
            sb.append("                var b = (").append(ownerSourceName).append(") bean;\n");
            sb.append("                switch (fieldName) {\n");
            for (var fi : entry.getValue()) {
                sb.append("                    case \"").append(fi.fieldName()).append("\" -> { ")
                        .append("b.").append(fi.fieldName())
                        .append(" = (").append(fi.fieldTypeErasure()).append(") value; return true; }\n");
            }
            sb.append("                    default -> { return false; }\n");
            sb.append(CLOSE_16);
            sb.append(CLOSE_12);
        }
        sb.append("            default -> { return false; }\n");
        sb.append(CLOSE_8);
        sb.append(CLOSE_4);
    }

    /** {@code invoke(...)}: the methods the provider calls directly, grouped by declaring class in order. */
    private static void appendInvoke(StringBuilder sb, List<MethodInvoke> methodInvokes) {
        Map<String, List<MethodInvoke>> byClass = methodInvokes.stream()
                .collect(Collectors.groupingBy(MethodInvoke::declaringClassFqn,
                        java.util.LinkedHashMap::new, Collectors.toList()));

        sb.append(OVERRIDE);
        sb.append(SUPPRESS_RAW);
        sb.append("    public Object invoke(Object target, String className, String methodId, Object[] args) {\n");
        // Observers, producers, disposers and initializers may declare checked exceptions
        // (vauban#145); the SPI says they propagate as is: see sneaky() below.
        sb.append(TRY_OPEN);
        sb.append("        switch (className) {\n");
        for (var entry : byClass.entrySet()) {
            var declClass = entry.getKey();
            sb.append(CASE_OPEN).append(declClass).append(CASE_BLOCK);
            sb.append("                switch (methodId) {\n");
            for (var mi : entry.getValue()) {
                appendInvokeCase(sb, declClass, mi);
            }
            sb.append("                    default -> { return ").append(SPI).append(".NOT_INVOKED; }\n");
            sb.append(CLOSE_16);
            sb.append(CLOSE_12);
        }
        sb.append("            default -> { return ").append(SPI).append(".NOT_INVOKED; }\n");
        sb.append(CLOSE_8);
        sb.append(CATCH_SNEAKY);
        sb.append(CLOSE_4);
    }

    /** One {@code case "<methodId>" -> { … }} of {@code invoke}: the direct call and its result. */
    private static void appendInvokeCase(StringBuilder sb, String declClass, MethodInvoke mi) {
        sb.append("                    case \"").append(mi.methodId()).append(CASE_BLOCK);
        var call = new StringBuilder();
        if (mi.isStatic()) {
            call.append(declClass).append(".").append(mi.methodName()).append("(");
        } else {
            call.append("((").append(declClass).append(") target).").append(mi.methodName()).append("(");
        }
        call.append(castArgs(mi.paramErasures())).append(")");

        if (mi.isVoid()) {
            sb.append("                        ").append(call).append(";\n");
            sb.append("                        return null;\n");
        } else {
            sb.append("                        return ").append(call).append(";\n");
        }
        sb.append("                    }\n");
    }

    /**
     * {@code createClientProxy(...)}: in-module client-proxy instantiation for this package's
     * normal-scoped beans — {@code new <Bean>_ClientProxy()} + {@code $$setDelegate(delegate)}, both
     * in-package, the proxy being a sibling generated source — so the container creates the proxy
     * without reflection and the bean package needs no {@code opens}/{@code exports}. Producer
     * proxies (issue #42) are keyed by the produced type but instantiated from this (the producer's)
     * package.
     */
    private static void appendCreateClientProxy(StringBuilder sb, Contents contents) {
        sb.append(OVERRIDE);
        sb.append(SUPPRESS_RAW);
        sb.append("    public Object createClientProxy(String proxyClassName, java.util.function.Supplier<?> delegate) {\n");
        // A proxy constructor declares Throwable: its super constructor may throw a checked exception.
        sb.append(TRY_OPEN);
        sb.append("        switch (proxyClassName) {\n");
        for (var proxyFqn : contents.clientProxyFqns()) {
            appendProxyCase(sb, proxyFqn, proxyFqn, contents.bytecodeClasses());
        }
        for (var pp : contents.producerProxies()) {
            appendProxyCase(sb, pp.key(), pp.proxyFqn(), contents.bytecodeClasses());
        }
        sb.append("            default -> { return null; }\n");
        sb.append(CLOSE_8);
        sb.append(CATCH_SNEAKY);
        sb.append(CLOSE_4);
    }

    /** One {@code case "<key>" -> { … }} of {@code createClientProxy}. */
    private static void appendProxyCase(StringBuilder sb, String key, String proxyFqn,
            java.util.Set<String> bytecodeClasses) {
        if (bytecodeClasses.contains(proxyFqn)) {
            appendBytecodeProxyCase(sb, key, proxyFqn);
            return;
        }
        sb.append(CASE_OPEN).append(key).append(CASE_BLOCK);
        sb.append("                var p = new ").append(proxyFqn).append(NO_ARG_CALL);
        sb.append("                p.$$setDelegate(delegate);\n");
        sb.append("                return p;\n");
        sb.append(CLOSE_12);
    }

    /**
     * {@code coverage()}: what this provider runs in-module, from the same lists as the switches, so
     * the declaration cannot diverge from the dispatch. Diagnostics only (Vidocq dev console).
     */
    private static void appendCoverage(StringBuilder sb, Contents contents) {
        var proxyKeys = new java.util.ArrayList<>(contents.clientProxyFqns());
        contents.producerProxies().forEach(pp -> proxyKeys.add(pp.key()));
        sb.append(OVERRIDE);
        sb.append("    public io.vidocq.vauban.api.GeneratedCoverage coverage() {\n");
        sb.append("        return io.vidocq.vauban.api.GeneratedCoverage.of(")
                .append("io.vidocq.vauban.api.GeneratedCoverage.Generator.APT,\n");
        sb.append(COVERAGE_INDENT).append(stringArray(contents.components().stream().map(Component::fqn).toList()))
                .append(COVERAGE_SEPARATOR);
        sb.append(COVERAGE_INDENT).append(stringArray(contents.fieldInjects().stream()
                .map(fi -> fi.declaringClassFqn() + "#" + fi.fieldName()).toList())).append(COVERAGE_SEPARATOR);
        sb.append(COVERAGE_INDENT).append(stringArray(contents.methodInvokes().stream()
                .map(mi -> mi.declaringClassFqn() + "#" + mi.methodId()).toList())).append(COVERAGE_SEPARATOR);
        sb.append(COVERAGE_INDENT).append(stringArray(proxyKeys)).append(");\n");
        sb.append(CLOSE_4);
    }

    /** The provider's helper instantiating a class emitted as bytecode, by its binary name. */
    private static final String CONSTRUCT = "$$construct";

    /** {@code case "<key>" -> return $$proxy("<binary name>", delegate);}: a proxy emitted as bytecode. */
    private static void appendBytecodeProxyCase(StringBuilder sb, String key, String proxyBinaryName) {
        sb.append(CASE_OPEN).append(key).append(CASE_BLOCK);
        sb.append("                return $$proxy(\"").append(proxyBinaryName).append("\", delegate);\n");
        sb.append(CLOSE_12);
    }

    /**
     * The helpers instantiating, through the provider's own lookup, the classes emitted as bytecode:
     * a full-privilege lookup in the provider's package, which needs no {@code opens}. The handle is
     * looked up on every call — such a class is rare (BUG-20261004-09).
     */
    private static void appendBytecodeInstantiation(StringBuilder sb) {
        sb.append("""
                    // Classes this package's processor emitted as bytecode (BUG-20261004-09): this source
                    // cannot name them, so they are instantiated through the provider's own lookup.
                    private static Object $$construct(String className, Class<?>[] parameterTypes, Object[] args) {
                        try {
                            var lookup = java.lang.invoke.MethodHandles.lookup();
                            var type = Class.forName(className, false, lookup.lookupClass().getClassLoader());
                            return lookup.findConstructor(type,
                                    java.lang.invoke.MethodType.methodType(void.class, parameterTypes))
                                    .invokeWithArguments(args);
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new IllegalStateException("[Vauban] Could not instantiate " + className, t);
                        }
                    }

                    private static Object $$proxy(String className, java.util.function.Supplier<?> delegate) {
                        var proxy = $$construct(className, new Class<?>[0], new Object[0]);
                        try {
                            java.lang.invoke.MethodHandles.lookup().findVirtual(proxy.getClass(), "$$setDelegate",
                                    java.lang.invoke.MethodType.methodType(void.class, java.util.function.Supplier.class))
                                    .invokeWithArguments(proxy, delegate);
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new IllegalStateException("[Vauban] Could not wire " + className, t);
                        }
                        return proxy;
                    }
                """);
    }

    /** {@code new String[] {"a", "b"}}: the keys never hold a quote or a backslash (class and member names). */
    private static String stringArray(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", ", "new String[] {", "}"));
    }
}
