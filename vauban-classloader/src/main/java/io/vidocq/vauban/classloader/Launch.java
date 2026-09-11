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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Runs a Java SE application inside a Vauban layer — the equivalent of Weld SE's
 * {@code StartMain}:
 *
 * <pre>{@code
 * java -p mods -m io.vidocq.vauban.classloader/io.vidocq.vauban.classloader.Launch app/app.Main [args]
 * }</pre>
 *
 * <p>The application modules of the boot layer (everything that is not the platform, Jakarta or
 * Vauban itself) are loaded again in a child layer under a single self-first
 * {@link VaubanClassLoader}, and the application's {@code main} is invoked <em>from inside</em>
 * that layer. Nothing in the application changes: {@code SeContainerInitializer} and the rest of
 * CDI SE work as before. What changes is who defines the application's classes — and once that is
 * Vauban's own loader:
 * <ul>
 *   <li>a client proxy that must live in a third-party produced type's package is
 *       <em>placed</em> there by the loader (issue #42, Stage 4) — zero {@code opens}, no agent,
 *       no rewritten jar — and the third-party constructor never runs (issue #24);</li>
 *   <li>an unwoven normal-scoped bean (classes written after javac by an IDE) is woven at
 *       definition by the loader's cdi-proxifier — no load-time agent.</li>
 * </ul>
 *
 * <p>Why the entry point itself must run in the layer, rather than {@code initialize()} doing this
 * transparently: the caller's classes are its identity. A {@code Main} left in the boot layer holds
 * the boot layer's {@code Gadget.class}, while a placed proxy extends the layer's {@code Gadget};
 * {@code container.select(Gadget.class)} would end in {@code ClassCastException}. So the launcher
 * re-enters the application through {@code main}, once, from the layer. A re-entrant call (the
 * application calling {@link #run} itself while already in a layer) is a no-op that simply invokes
 * the target.
 *
 * <p>Modules kept in the parent (boot) layer: {@code java.*}, {@code jdk.*}, {@code jakarta.*},
 * {@code io.vidocq.vauban.*}, plus any prefix listed in {@value #KEEP_PROPERTY}. Everything else
 * with a {@code file:} location is re-layered.
 */
public final class Launch {

    /** Comma-separated extra module-name prefixes to keep in the boot layer. */
    public static final String KEEP_PROPERTY = "vauban.launch.keep";

    private static final System.Logger LOG = System.getLogger(Launch.class.getName());
    private static final List<String> KEPT_PREFIXES =
            List.of("java.", "jdk.", "jakarta.", "io.vidocq.vauban.");

    private Launch() {}

    public static void main(String[] args) throws Throwable {
        if (args.length == 0 || args[0].isBlank()) {
            System.err.println("Usage: io.vidocq.vauban.classloader.Launch [<module>/]<main-class> [args...]");
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
     */
    public static void run(String target, String[] appArgs) throws Throwable {
        var slash = target.indexOf('/');
        var className = slash >= 0 ? target.substring(slash + 1) : target;
        if (alreadyInLayer()) {
            // The application re-entered the launcher from the layer: nothing to re-layer.
            invokeMain(Class.forName(className, true,
                    Thread.currentThread().getContextClassLoader()), appArgs);
            return;
        }
        var boot = ModuleLayer.boot();
        var paths = applicationPaths(boot);
        if (paths.isEmpty()) {
            throw new IllegalStateException("No application module to re-layer: is the application "
                    + "on the module path (java -p ...)? Modules named " + KEPT_PREFIXES
                    + " and " + KEEP_PROPERTY + " prefixes stay in the boot layer.");
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
            var mainClass = Class.forName(className, true, appLayer.loader());
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

    /** {@code file:} locations of the boot layer's modules that are not kept in the parent. */
    static List<Path> applicationPaths(ModuleLayer boot) {
        var kept = new ArrayList<>(KEPT_PREFIXES);
        var extra = System.getProperty(KEEP_PROPERTY, "").strip();
        if (!extra.isEmpty()) {
            for (var prefix : extra.split("\\s*,\\s*")) {
                if (!prefix.isEmpty()) kept.add(prefix);
            }
        }
        var paths = new LinkedHashSet<Path>();
        for (var resolved : boot.configuration().modules()) {
            var name = resolved.name();
            if (kept.stream().anyMatch(name::startsWith)) {
                continue;
            }
            resolved.reference().location()
                    .filter(uri -> "file".equals(uri.getScheme()))
                    .ifPresent(uri -> paths.add(Path.of(uri)));
        }
        return List.copyOf(paths);
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
