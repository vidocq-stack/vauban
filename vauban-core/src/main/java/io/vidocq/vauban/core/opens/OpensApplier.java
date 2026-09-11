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
package io.vidocq.vauban.core.opens;

import io.vidocq.vauban.weaver.AgentAccess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opens, at container boot, the packages of produced types that fall to runtime proxy generation
 * (issue #42, Stage 3b — "the container handles the open itself"). The annotation processor lists
 * those types in {@code META-INF/vauban/required-opens.list}; here we load each, and — only when it
 * is in a <strong>named</strong> module and not already open — open its package to
 * {@code io.vidocq.vauban.core} via {@link Instrumentation#redefineModule}. No hand-written
 * {@code --add-opens}, no jar rewrite.
 *
 * <p>Strictly a no-op when the list is empty (the common case, including the TCK): it reads a
 * resource that is not there and returns before touching any agent. On the class path every produced
 * type is in the unnamed module (already open), so nothing is opened there either.
 *
 * <p>Requires an instrumentation agent. It reuses the one captured by the load-time weaving agent
 * (present when the app runs with {@code -javaagent:vauban-weaver.jar} or after a weaving attach);
 * otherwise it makes a best-effort self-attach. If no agent can be obtained it logs an actionable
 * warning and leaves the runtime fallback to fail with its own {@code opens} message.
 */
public final class OpensApplier {

    private static final System.Logger LOG = System.getLogger(OpensApplier.class.getName());
    private static final String RESOURCE = "META-INF/vauban/required-opens.list";

    /**
     * System property gating the boot-time open. Opt-in, and deliberately so: opening another
     * module's package behind the user's back is the very thing this project criticises runtime CDI
     * implementations for needing — the only difference would be who grants it. Left off, an
     * application that would depend on it fails loudly at boot with the remedies spelled out,
     * instead of silently acquiring a dependency on dynamic agent attachment that only shows up on
     * a locked-down JVM in production.
     *
     * <p>This lever is also on borrowed time: integrity by default is closing dynamic agent
     * attachment. Prefer a fully-public produced type (compile-time proxy, nothing to open) or the
     * {@code vauban:enhance-dependencies} goal (build-time, survives integrity by default).
     */
    public static final String AUTO_OPEN_PROPERTY = "vauban.opens.auto";

    private OpensApplier() {}

    /** Whether the boot-time open lever is switched on — see {@link #AUTO_OPEN_PROPERTY}. */
    public static boolean autoOpenEnabled() {
        return Boolean.getBoolean(AUTO_OPEN_PROPERTY);
    }


    /** Apply the required opens visible from {@code loader}. Safe to call on every boot. */
    public static void apply(ClassLoader loader) {
        Set<String> producedTypeFqns;
        try {
            producedTypeFqns = read(loader);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Cannot read " + RESOURCE + ": " + e);
            return;
        }
        if (producedTypeFqns.isEmpty()) {
            return; // the common case — nothing to do, no agent touched
        }

        var container = OpensApplier.class.getModule(); // io.vidocq.vauban.core
        var pending = new LinkedHashMap<Module, Set<String>>();
        for (var fqn : producedTypeFqns) {
            Class<?> c;
            try {
                c = Class.forName(fqn, false, loader);
            } catch (Throwable notHere) {
                continue; // not loadable from this layer — leave it to the runtime
            }
            var module = c.getModule();
            if (module == null || !module.isNamed()) {
                continue; // class path (unnamed module): already open to everyone
            }
            var pkg = c.getPackageName();
            if (c.getClassLoader() instanceof io.vidocq.vauban.classloader.VaubanClassLoader vcl
                    && vcl.placesClass(fqn + "_ClientProxy")
                    && module.isExported(pkg, container)) {
                // #42 Stage 4: the loader that owns the package defines the shipped proxy into
                // it — nothing to open, and nothing the runtime needs to generate. The container
                // still instantiates that proxy from io.vidocq.vauban.core, which needs the package
                // exported to it: when it is not, the package stays pending so the warning (or the
                // opt-in open) applies instead of a silent failure at the injection point.
                LOG.log(System.Logger.Level.DEBUG, () -> fqn + ": proxy placed by the Vauban "
                        + "class loader, no opens needed");
                continue;
            }
            if (module.isOpen(pkg, container)) {
                continue; // already opened (by module-info or a previous apply)
            }
            pending.computeIfAbsent(module, m -> new LinkedHashSet<>()).add(pkg);
        }
        if (pending.isEmpty()) {
            return;
        }

        if (!autoOpenEnabled()) {
            LOG.log(System.Logger.Level.WARNING, () -> "Vauban needs " + describe(pending)
                    + " opened to " + container.getName() + " for a runtime producer proxy, and will "
                    + "not do it on its own. Pick one: start the application through "
                    + "io.vidocq.vauban.classloader.Launch (in a Vauban layer the loader defines the "
                    + "shipped proxy inside the package itself — zero opens, no agent); "
                    + "make the produced type fully public (the proxy "
                    + "is then built at compile time and nothing needs opening); run the "
                    + "vauban:enhance-dependencies goal (the proxy moves inside the dependency, still "
                    + "nothing to open); add the matching `opens ... to " + container.getName() + ";`; "
                    + "or, as a last resort, set -D" + AUTO_OPEN_PROPERTY + "=true to let the "
                    + "container open it at boot through an agent — which integrity by default is "
                    + "closing off.");
            return;
        }

        var inst = instrumentation();
        if (inst == null) {
            LOG.log(System.Logger.Level.WARNING, () -> "Vauban must open " + describe(pending)
                    + " to " + container.getName() + " for a runtime producer proxy, but no "
                    + "instrumentation agent is available. Run with -javaagent:vauban-weaver.jar, add "
                    + "the matching `opens ... to io.vidocq.vauban.core;`, or make the produced type "
                    + "fully public so the proxy is built at compile time.");
            return;
        }
        for (var entry : pending.entrySet()) {
            var module = entry.getKey();
            if (!inst.isModifiableModule(module)) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "Module " + module.getName() + " is not modifiable; cannot open "
                                + entry.getValue() + " to " + container.getName());
                continue;
            }
            var opens = new HashMap<String, Set<Module>>();
            for (var pkg : entry.getValue()) {
                opens.put(pkg, Set.of(container));
            }
            inst.redefineModule(module, Set.of(), Map.of(), opens, Set.of(), Map.of());
            LOG.log(System.Logger.Level.INFO, () -> "Opened " + entry.getValue() + " of module "
                    + module.getName() + " to " + container.getName()
                    + " (Stage 3b — zero --add-opens)");
        }
    }

    private static Instrumentation instrumentation() {
        var inst = AgentAccess.instrumentation();
        if (inst != null) {
            return inst;
        }
        // Best-effort self-attach through the weaver's AttachBack helper (no weaving plan).
        try {
            io.vidocq.vauban.core.weaving.LoadTimeWeaving.ensureAgentAttached();
        } catch (Throwable ignore) {
            // attach unavailable (e.g. jdk.attach missing) — fall through, warn above
        }
        return AgentAccess.instrumentation();
    }

    private static Set<String> read(ClassLoader loader) throws IOException {
        var names = new LinkedHashSet<String>();
        var resources = loader.getResources(RESOURCE);
        while (resources.hasMoreElements()) {
            try (var r = new BufferedReader(new InputStreamReader(
                    resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.strip();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        names.add(line);
                    }
                }
            }
        }
        return names;
    }

    private static String describe(Map<Module, Set<String>> pending) {
        var sb = new StringBuilder();
        pending.forEach((m, pkgs) -> sb.append(pkgs).append(" of ").append(m.getName()).append(' '));
        return sb.toString().strip();
    }
}
