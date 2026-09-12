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
package io.vidocq.vauban.maven.modularize;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which of the scanned {@code uses} directives a generated descriptor may legally declare.
 *
 * <p>A {@code uses} is not a hint: the JVM rejects the whole graph at resolution time with
 * <em>"Module M uses S but does not read a module that exports P to M"</em>. Emitting everything
 * the bytecode scan finds therefore trades a late failure (one broken lookup) for an early one
 * (nothing resolves at all), which is strictly worse. Only directives that satisfy both JPMS
 * conditions are kept:
 *
 * <ol>
 *   <li>the service type resolves to a package of the closure or of the JDK system modules, and</li>
 *   <li>the declaring module can read the module exporting that package, following
 *       {@code requires} and the transitive closure of {@code requires transitive}.</li>
 * </ol>
 *
 * <p>Everything else is dropped and reported. The interesting drop is {@link Kind#UNREADABLE}: when
 * a jar's own code reaches {@code ServiceLoader} for a type defined in a jar that depends on it —
 * langchain4j-core's generic {@code ServiceHelper} loading types from langchain4j — the directive
 * the JVM demands is one the module system forbids, since declaring it would need a
 * {@code requires} back and JPMS has no cycles. No descriptor satisfies both constraints, and the
 * jar has no legal explicit form at all; {@code Modularizer} leaves it automatic.
 */
final class UsesLegality {

    /** Why a scanned service could not be declared. */
    enum Kind {
        /** The service type is in no jar of the closure and in no system module. */
        NOT_IN_CLOSURE,
        /** The type exists but the declaring module cannot read the module exporting it. */
        UNREADABLE,
        /** The scan resolved a non-literal argument to its static type. */
        NOT_A_CLASS_LITERAL
    }

    /** One dropped directive, with the message shown in the report and the build log. */
    record Drop(Path jar, String service, Kind kind, String reason) {}

    /** The legal directives per jar, and everything that was dropped. */
    record Verdict(Map<Path, Set<String>> legal, List<Drop> drops) {

        /** Whether {@code jar} loses a service its own code looks up but cannot legally declare. */
        boolean hasUnreadableDrop(Path jar) {
            return drops.stream().anyMatch(d -> d.jar().equals(jar) && d.kind() == Kind.UNREADABLE);
        }

        /** Every {@link Kind#UNREADABLE} drop of {@code jar}, in scan order. */
        List<Drop> unreadableDrops(Path jar) {
            return drops.stream().filter(d -> d.jar().equals(jar) && d.kind() == Kind.UNREADABLE)
                    .toList();
        }
    }

    /** One module of the graph as it will exist once the selected jars are patched. */
    private record Mod(String name, boolean automatic, Set<String> requires,
                       Set<String> requiresTransitive, Map<String, Set<String>> exports) {}

    /**
     * Types a class literal scan can produce that are never a service: {@code ServiceLoader.load(x)}
     * on a variable resolves to the static type of the argument.
     */
    private static final Set<String> NEVER_A_SERVICE = Set.of("java.lang.Class", "java.lang.Object");

    private final Map<Path, Mod> byJar;
    private final Map<String, Mod> byName;
    private final Map<String, Mod> exporterOfPackage;

    private UsesLegality(Map<Path, Mod> byJar, Map<String, Mod> byName,
                         Map<String, Mod> exporterOfPackage) {
        this.byJar = byJar;
        this.byName = byName;
        this.exporterOfPackage = exporterOfPackage;
    }

    /**
     * Builds the module graph that will exist after patching.
     *
     * @param closure       every jar of the dependency closure
     * @param patched       the jars that are going to become explicit modules
     * @param synthesized   the descriptor synthesized for each patched jar — its name, its derived
     *                      {@code requires} and its {@code exports}, as the module system will read
     *                      them once the jar is patched
     */
    static UsesLegality of(List<Path> closure, Set<Path> patched,
                           Map<Path, ModuleDescriptor> synthesized) {
        Map<Path, Mod> byJar = new LinkedHashMap<>();
        Map<String, Mod> byName = new HashMap<>();
        Map<String, Mod> exporterOfPackage = new HashMap<>();

        for (Path jar : closure) {
            ModuleDescriptor descriptor = descriptorOf(jar);
            if (descriptor == null) {
                continue;
            }
            Set<String> packages = descriptor.packages();
            Mod mod;
            ModuleDescriptor patchedWith = patched.contains(jar) ? synthesized.get(jar) : null;
            if (patchedWith != null) {
                // A patched jar is read exactly as it will be once written: the synthesized
                // descriptor already carries the derived requires and the exports.
                mod = explicit(patchedWith);
            } else if (descriptor.isAutomatic()) {
                mod = new Mod(descriptor.name(), true, Set.of(), Set.of(), Map.of());
            } else {
                mod = explicit(descriptor);
            }
            byJar.put(jar, mod);
            byName.put(mod.name(), mod);
            for (String p : packages) {
                exporterOfPackage.putIfAbsent(p, mod);
            }
        }

        // The JDK's own modules are part of the graph a `uses` is resolved against.
        for (ModuleReference ref : ModuleFinder.ofSystem().findAll()) {
            ModuleDescriptor descriptor = ref.descriptor();
            Mod mod = explicit(descriptor);
            byName.putIfAbsent(mod.name(), mod);
            for (String p : descriptor.packages()) {
                exporterOfPackage.putIfAbsent(p, mod);
            }
        }
        return new UsesLegality(byJar, byName, exporterOfPackage);
    }

    /**
     * Splits the scanned services of each patched jar into what it may declare and what it may not.
     *
     * @param scanned the raw scan result per jar; jars absent from the graph are ignored
     */
    Verdict check(Map<Path, Set<String>> scanned) {
        Map<Path, Set<String>> legal = new LinkedHashMap<>();
        List<Drop> drops = new ArrayList<>();
        for (Map.Entry<Path, Set<String>> entry : scanned.entrySet()) {
            Path jar = entry.getKey();
            Mod caller = byJar.get(jar);
            if (caller == null) {
                continue;
            }
            Set<String> kept = new TreeSet<>();
            for (String service : entry.getValue()) {
                Drop drop = verdictFor(jar, caller, service);
                if (drop == null) {
                    kept.add(service);
                } else {
                    drops.add(drop);
                }
            }
            legal.put(jar, kept);
        }
        return new Verdict(legal, drops);
    }

    /** {@code null} when {@code service} may be declared by {@code caller}, the reason otherwise. */
    private Drop verdictFor(Path jar, Mod caller, String service) {
        if (NEVER_A_SERVICE.contains(service)) {
            return new Drop(jar, service, Kind.NOT_A_CLASS_LITERAL, "not a class literal");
        }
        int dot = service.lastIndexOf('.');
        String pkg = dot < 0 ? "" : service.substring(0, dot);
        Mod exporter = exporterOfPackage.get(pkg);
        if (exporter == null) {
            return new Drop(jar, service, Kind.NOT_IN_CLOSURE, "not in closure");
        }
        if (exporter.name().equals(caller.name())) {
            return null;
        }
        if (!reads(caller, exporter.name())) {
            return new Drop(jar, service, Kind.UNREADABLE, "caller " + caller.name()
                    + " cannot read " + exporter.name()
                    + " (would need requires " + exporter.name() + " — cycle?)");
        }
        if (!exports(exporter, pkg, caller.name())) {
            return new Drop(jar, service, Kind.UNREADABLE, "caller " + caller.name()
                    + " cannot read " + exporter.name() + " (it does not export " + pkg
                    + " to " + caller.name() + ")");
        }
        return null;
    }

    /**
     * Whether {@code caller} reads {@code target}: itself, its {@code requires}, and the transitive
     * closure of {@code requires transitive}. Reading one automatic module implies reading them
     * all, which is what makes demoting a jar back to automatic a pure relaxation.
     */
    private boolean reads(Mod caller, String target) {
        if (caller.name().equals(target) || caller.automatic() || "java.base".equals(target)) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(caller.requires());
        boolean readsAnAutomaticModule = false;
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!seen.add(name)) {
                continue;
            }
            if (name.equals(target)) {
                return true;
            }
            Mod mod = byName.get(name);
            if (mod == null) {
                continue;
            }
            if (mod.automatic()) {
                readsAnAutomaticModule = true;
            } else {
                queue.addAll(mod.requiresTransitive());
            }
        }
        Mod targetMod = byName.get(target);
        return readsAnAutomaticModule && targetMod != null && targetMod.automatic();
    }

    /** Whether {@code exporter} exports {@code pkg} to {@code caller}. */
    private static boolean exports(Mod exporter, String pkg, String caller) {
        if (exporter.automatic()) {
            return true;
        }
        Set<String> targets = exporter.exports().get(pkg);
        return targets != null && (targets.isEmpty() || targets.contains(caller));
    }

    /** The module packaged in {@code jar}, or {@code null} when the platform cannot read one. */
    private static ModuleDescriptor descriptorOf(Path jar) {
        try {
            // Same reader as the classifier, so both agree on what a jar's descriptor is — and on
            // which jars have none. A jar with no derivable module name (unchecked FindException
            // there, IOException here) cannot host a uses directive either, so it drops out.
            return JarModuleClassifier.descriptorOf(jar);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A {@link Mod} view of a descriptor written by hand (a closure jar or a system module). */
    private static Mod explicit(ModuleDescriptor descriptor) {
        Set<String> requires = new HashSet<>();
        Set<String> transitive = new HashSet<>();
        for (ModuleDescriptor.Requires r : descriptor.requires()) {
            requires.add(r.name());
            if (r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                transitive.add(r.name());
            }
        }
        Map<String, Set<String>> exports = new HashMap<>();
        for (ModuleDescriptor.Exports e : descriptor.exports()) {
            // An empty target set means unqualified: readable by anyone who reads the module.
            exports.put(e.source(), e.isQualified() ? new LinkedHashSet<>(e.targets()) : Set.of());
        }
        return new Mod(descriptor.name(), descriptor.isAutomatic(), requires, transitive, exports);
    }

}
