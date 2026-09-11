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
package io.vidocq.vauban.classloader;

import io.vidocq.vauban.classloader.spi.PluginContext;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ResolvedModule;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs a Java SE application inside a Vauban layer — the equivalent of Weld SE's
 * {@code StartMain}:
 *
 * <pre>{@code
 * java -p mods --add-modules ALL-MODULE-PATH \
 *      -m io.vidocq.vauban.classloader/io.vidocq.vauban.classloader.Launch app/app.Main [args]
 * }</pre>
 *
 * <p>{@code --add-modules ALL-MODULE-PATH} is required: with {@code -m}, the launcher's module is
 * the only root, so neither the application nor the container would otherwise be resolved in the
 * boot layer, and there would be nothing to re-layer.
 *
 * <p>The application modules of the boot layer are loaded again in a child layer under a single
 * self-first {@link VaubanClassLoader}, and the application's {@code main} is invoked <em>from
 * inside</em> that layer. Nothing in the application changes: {@code SeContainerInitializer} and the
 * rest of CDI SE work as before. What changes is who defines the application's classes — and once
 * that is Vauban's own loader:
 * <ul>
 *   <li>a client proxy that must live in a third-party produced type's package is <em>placed</em>
 *       there by the loader (issue #42, Stage 4) — zero {@code opens}, no agent, no rewritten jar —
 *       and the third-party constructor does not run for it (issue #24);</li>
 *   <li>an unwoven normal-scoped bean (classes written after javac by an IDE) is woven at
 *       definition by the loader's cdi-proxifier — no load-time agent.</li>
 * </ul>
 *
 * <p>Why the entry point itself must run in the layer, rather than {@code initialize()} doing this
 * transparently: the caller's classes are its identity. A {@code Main} left in the boot layer holds
 * the boot layer's {@code Gadget.class}, while a placed proxy extends the layer's {@code Gadget};
 * {@code container.select(Gadget.class)} would end in {@code ClassCastException}.
 *
 * <p>Kept in the boot layer, and read from there by the re-layered modules:
 * <ul>
 *   <li>the platform ({@code java.*}, {@code jdk.*}), Jakarta ({@code jakarta.*}) and the Vauban
 *       container modules;</li>
 *   <li>automatic modules — a re-layered automatic module would read its own boot-layer twin, and
 *       resolution would fail;</li>
 *   <li>modules with a package the Vauban loader refuses to define ({@code javax.}, {@code sun.},
 *       {@code com.sun.} …), which would otherwise be empty shells in the layer;</li>
 *   <li>modules whose name starts with a prefix listed in {@value #KEEP_PROPERTY};</li>
 *   <li>and, transitively, every module a kept (explicit) module reads: a kept module cannot read a
 *       re-layered one, so what it needs must stay with it.</li>
 * </ul>
 * Everything else with a {@code file:} location is re-layered. The application's main module must
 * end up re-layered: the launcher refuses to run it from the boot layer.
 *
 * <p>{@link #run} does nothing and returns {@code false} when the calling thread already runs in a
 * Vauban layer, so an application may call it as the first statement of its own {@code main} —
 * {@code if (Launch.run("app/app.Main", args)) return;} — the way {@code Vidocq.run} is used.
 */
public final class Launch {

    /** Comma-separated extra module-name prefixes to keep in the boot layer. */
    public static final String KEEP_PROPERTY = "vauban.launch.keep";

    private static final System.Logger LOG = System.getLogger(Launch.class.getName());
    private static final List<String> KEPT_PREFIXES = List.of("java.", "jdk.", "jakarta.");
    /** The container itself — never application code, whatever else lives under io.vidocq.vauban. */
    private static final Set<String> CONTAINER_MODULES = Set.of(
            "io.vidocq.vauban.api", "io.vidocq.vauban.core", "io.vidocq.vauban.indexer",
            "io.vidocq.vauban.weaver", "io.vidocq.vauban.classloader",
            "io.vidocq.vauban.classloader.spi", "io.vidocq.vauban.sjar",
            "io.vidocq.vauban.processor", "io.vidocq.vauban.junit");

    private Launch() {}

    public static void main(String[] args) throws Throwable {
        if (args.length == 0 || args[0].isBlank()) {
            System.err.println("Usage: java -p <mods> --add-modules ALL-MODULE-PATH "
                    + "-m io.vidocq.vauban.classloader/io.vidocq.vauban.classloader.Launch "
                    + "[<module>/]<main-class> [args...]");
            System.exit(2);
        }
        run(args[0], Arrays.copyOfRange(args, 1, args.length));
    }

    /**
     * Re-layer the boot layer's application modules and invoke {@code target}'s
     * {@code public static void main(String[])} from inside the new layer.
     *
     * @param target  {@code <module>/<main-class>} or just {@code <main-class>}
     * @param appArgs arguments handed to the application's {@code main}
     * @return {@code true} when the target ran in a fresh Vauban layer; {@code false}, without doing
     *     anything, when the calling thread already runs in one
     */
    public static boolean run(String target, String[] appArgs) throws Throwable {
        if (alreadyInLayer()) {
            return false;
        }
        var slash = target.indexOf('/');
        var moduleName = slash >= 0 ? target.substring(0, slash) : null;
        var className = slash >= 0 ? target.substring(slash + 1) : target;
        var boot = ModuleLayer.boot();
        if (moduleName != null && boot.findModule(moduleName).isEmpty()) {
            throw new IllegalStateException("Module " + moduleName + " is not resolved in the boot "
                    + "layer. With -m, the launcher is the only root module: add --add-modules "
                    + "ALL-MODULE-PATH (or --add-modules " + moduleName + ") to the java command line.");
        }
        var paths = applicationPaths(boot.configuration(), extraKeptPrefixes());
        if (paths.isEmpty()) {
            throw new IllegalStateException("No application module to re-layer. With -m, the "
                    + "launcher is the only root module: add --add-modules ALL-MODULE-PATH to the "
                    + "java command line. Kept in the boot layer: the platform, Jakarta and Vauban "
                    + "modules, automatic modules, modules with javax./sun./com.sun. packages, the "
                    + KEEP_PROPERTY + " prefixes, and everything those modules read.");
        }
        var previous = Thread.currentThread().getContextClassLoader();
        VaubanLayerFactory.AppLayer appLayer;
        try {
            appLayer = VaubanLayerFactory.createAppLayer(paths, boot, previous, pluginContext());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the Vauban application layer", e);
        }
        LOG.log(System.Logger.Level.INFO, () -> "Vauban layer ready: " + appLayer.moduleNames());
        Thread.currentThread().setContextClassLoader(appLayer.loader());
        try {
            var mainClass = loadMainClass(appLayer, moduleName, className);
            var module = mainClass.getModule();
            if (module.isNamed() && !module.isOpen(mainClass.getPackageName(), Launch.class.getModule())) {
                // The application need not export its main package: the layer's controller can
                // open it to the launcher, and to nobody else.
                appLayer.controller().addOpens(module, mainClass.getPackageName(),
                        Launch.class.getModule());
            }
            invokeMain(mainClass, appArgs);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        return true;
    }

    /** The main class, loaded from — and required to belong to — the application layer. */
    private static Class<?> loadMainClass(VaubanLayerFactory.AppLayer appLayer, String moduleName,
            String className) throws ClassNotFoundException {
        Class<?> mainClass;
        if (moduleName != null) {
            var module = appLayer.layer().findModule(moduleName)
                    .filter(m -> m.getLayer() == appLayer.layer())
                    .orElseThrow(() -> keptInBootLayer(moduleName));
            mainClass = Class.forName(module, className);
            if (mainClass == null) {
                throw new ClassNotFoundException(className + " in module " + moduleName);
            }
        } else {
            mainClass = Class.forName(className, true, appLayer.loader());
        }
        if (mainClass.getModule().getLayer() != appLayer.layer()) {
            throw keptInBootLayer(String.valueOf(mainClass.getModule().getName()));
        }
        return mainClass;
    }

    private static IllegalStateException keptInBootLayer(String moduleName) {
        return new IllegalStateException("Module " + moduleName + " stays in the boot layer, so the "
                + "application would run exactly as without the launcher. A module is kept when it is "
                + "a platform, Jakarta or Vauban module, an automatic module, has a javax./sun./com.sun. "
                + "package, matches a " + KEEP_PROPERTY + " prefix, or is read by a kept module.");
    }

    /** Whether the context class loader chain already contains a Vauban loader. */
    static boolean alreadyInLayer() {
        for (var l = Thread.currentThread().getContextClassLoader(); l != null; l = l.getParent()) {
            if (l instanceof VaubanClassLoader) {
                return true;
            }
        }
        return false;
    }

    /** The extra keep prefixes of {@value #KEEP_PROPERTY}. */
    static List<String> extraKeptPrefixes() {
        var extra = System.getProperty(KEEP_PROPERTY, "").strip();
        var prefixes = new ArrayList<String>();
        if (!extra.isEmpty()) {
            for (var prefix : extra.split("\\s*,\\s*")) {
                if (!prefix.isEmpty()) prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    /**
     * The {@code file:} locations of the modules of {@code config} to re-layer — every module except
     * the kept ones described in the class javadoc.
     */
    static List<Path> applicationPaths(Configuration config, List<String> extraPrefixes) {
        var kept = new LinkedHashSet<ResolvedModule>();
        var queue = new ArrayDeque<ResolvedModule>();
        for (var resolved : config.modules()) {
            if (keptByRule(resolved, extraPrefixes) && kept.add(resolved)) {
                queue.add(resolved);
            }
        }
        // A kept module cannot read a re-layered one: keep, transitively, what it reads. An
        // automatic module reads every module, so following it would keep the whole application;
        // it is kept for itself only.
        while (!queue.isEmpty()) {
            var module = queue.poll();
            if (module.reference().descriptor().isAutomatic()) continue;
            for (var read : module.reads()) {
                if (read.configuration() == config && kept.add(read)) {
                    queue.add(read);
                }
            }
        }
        var paths = new LinkedHashSet<Path>();
        for (var resolved : config.modules()) {
            if (kept.contains(resolved)) continue;
            resolved.reference().location()
                    .filter(uri -> "file".equals(uri.getScheme()))
                    .ifPresent(uri -> paths.add(Path.of(uri)));
        }
        return List.copyOf(paths);
    }

    private static boolean keptByRule(ResolvedModule resolved, List<String> extraPrefixes) {
        var name = resolved.name();
        if (CONTAINER_MODULES.contains(name)) return true;
        for (var prefix : KEPT_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        for (var prefix : extraPrefixes) {
            if (name.startsWith(prefix)) return true;
        }
        var descriptor = resolved.reference().descriptor();
        if (descriptor.isAutomatic()) return true;
        for (var pkg : descriptor.packages()) {
            if (VaubanClassLoader.excludesPackage(pkg)) return true;
        }
        return false;
    }

    private static void invokeMain(Class<?> mainClass, String[] appArgs) throws Throwable {
        var main = mainClass.getMethod("main", String[].class);
        if (!Modifier.isStatic(main.getModifiers())) {
            throw new IllegalStateException(mainClass.getName() + ".main(String[]) is not static");
        }
        try {
            main.invoke(null, (Object) appArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The sjar key provider when the sjar module is present, else an empty context. */
    private static PluginContext pluginContext() {
        try {
            var provider = Class.forName("io.vidocq.vauban.sjar.SjarKeyProvider");
            return (PluginContext) provider.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return PluginContext.empty();
        }
    }
}
