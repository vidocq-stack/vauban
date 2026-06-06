package io.vidocq.vauban.processor.codegen.provider;

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
 */
public final class ComponentProviderGenerator {

    public static final String SIMPLE_NAME = "_VaubanComponents";
    private static final String SPI = "io.vidocq.vauban.api.VaubanComponentProvider";

    private ComponentProviderGenerator() {}

    /** A generated source file: its fully-qualified class name and its textual content. */
    public record Generated(String className, String source) {}

    /**
     * A component the provider can instantiate in-module.
     *
     * @param fqn           fully-qualified class name of the component
     * @param ctorParamTypes erased, nameable types of the selected constructor's parameters, in
     *                       declared order (empty for a no-arg constructor)
     */
    public record Component(String fqn, List<String> ctorParamTypes) {
        public Component {
            ctorParamTypes = List.copyOf(ctorParamTypes);
        }

        boolean noArg() {
            return ctorParamTypes.isEmpty();
        }
    }

    /**
     * Describes an {@code @Inject} instance field (non-private, non-static) that the generated
     * provider can assign in-module via a plain {@code ((DeclaringType) bean).field = (FieldType) value;}.
     * Only fields in the same package as the generated {@code _VaubanComponents} class are eligible:
     * a {@code putfield} to a package-private field only compiles from the same package.
     *
     * @param declaringClassFqn fully-qualified name of the bean class declaring the field
     * @param fieldName         simple name of the field
     * @param fieldTypeErasure  erased, source-nameable type of the field (e.g. {@code "app.Repo"})
     */
    public record FieldInject(String declaringClassFqn, String fieldName, String fieldTypeErasure) {}

    /**
     * Describes a method (producer, observer, disposer, lifecycle callback, initializer) that the
     * generated provider can invoke in-module without reflection.
     *
     * <p>The method identity key ({@code methodId}) is {@code methodName(paramErasure0,…)}, using
     * binary class names (dots for top-level, {@code $} for nested, {@code []} per array dimension),
     * exactly matching the format produced by
     * {@code io.vidocq.vauban.core.container.VaubanLookup#methodId(Method)}.
     *
     * @param declaringClassFqn fully-qualified name of the class declaring the method
     * @param methodName        simple name of the method
     * @param paramErasures     erased parameter type names in declared order (empty for no-arg)
     * @param isStatic          {@code true} for a static method (no target cast)
     * @param isVoid            {@code true} when the return type is {@code void}
     * @param returnErasure     erased return type name, or {@code null} when {@code isVoid} is true
     */
    public record MethodInvoke(
            String declaringClassFqn,
            String methodName,
            java.util.List<String> paramErasures,
            boolean isStatic,
            boolean isVoid,
            String returnErasure) {

        public MethodInvoke {
            paramErasures = List.copyOf(paramErasures);
        }

        /** The runtime-compatible method identity key: {@code name(erasure0,erasure1,…)}. */
        public String methodId() {
            return methodName + "(" + String.join(",", paramErasures) + ")";
        }
    }

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
                    .append(c.fqn()).append("();\n");
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
                        .append(c.fqn()).append("(");
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

        sb.append("}\n");
        return new Generated(className, sb.toString());
    }
}
