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
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The application layer of the universal loader (study §6, work package M2): the
 * application archives stay <em>off</em> the JVM module path and are resolved into a
 * child {@link ModuleLayer} whose single defining loader is a {@link VaubanClassLoader} —
 * every application class therefore flows through the source/transformer chain at
 * definition, while exports/opens keep being enforced inside the child layer (the strict
 * Java Modules philosophy survives as-is).
 *
 * <p>V1 decisions (study §10): one loader for the whole layer; plain jars and exploded
 * directories (an encrypted sjar with a clear {@code module-info.class} resolves too,
 * since class bytes go through the source plugins — not yet exercised).
 */
public final class VaubanLayerFactory {

    /**
     * The created layer, its defining loader, the resolved application modules, and the
     * layer {@link ModuleLayer.Controller} — launchers use it the way the JDK's own
     * launcher does, e.g. to export an application main class' package to themselves
     * before a reflective {@code main} invocation (application packages are otherwise
     * fully encapsulated).
     */
    public record AppLayer(ModuleLayer layer, VaubanClassLoader loader, Set<String> moduleNames,
            ModuleLayer.Controller controller) {}

    private VaubanLayerFactory() {}

    /**
     * Resolves every module found on {@code appPaths} into a child layer of
     * {@code parentLayer}, defined by a fresh {@link VaubanClassLoader} whose parent is
     * {@code parentLoader}.
     */
    public static AppLayer createAppLayer(List<Path> appPaths, ModuleLayer parentLayer,
            ClassLoader parentLoader, PluginContext pluginContext) throws IOException {
        var finder = promotingServices(ModuleFinder.of(appPaths.toArray(Path[]::new)), parentLayer);
        var roots = finder.findAll().stream()
                .map(ref -> ref.descriptor().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roots.isEmpty()) {
            throw new IOException("No Java module found on the application path " + appPaths);
        }

        // The loader manages the exact archives the resolved modules come from, so the
        // class index, the resource lookup and the transformer contexts line up with the
        // layer content.
        var archives = new LinkedHashSet<Path>();
        Configuration configuration = parentLayer.configuration()
                .resolveAndBind(finder, ModuleFinder.of(), roots);
        for (var resolved : configuration.modules()) {
            resolved.reference().location()
                    .flatMap(VaubanLayerFactory::archiveOf)
                    .ifPresent(archives::add);
        }

        var loader = VaubanClassLoader.forLayer(List.copyOf(archives), parentLoader, pluginContext);
        var controller = ModuleLayer.defineModules(configuration, List.of(parentLayer),
                moduleName -> loader);
        return new AppLayer(controller.layer(), loader, roots, controller);
    }

    /** Module-name prefixes that always stay in the parent layer: the platform and Jakarta. */
    private static final List<String> KEPT_PREFIXES = List.of("java.", "jdk.", "jakarta.");
    /** The container itself — never application code, whatever else lives under io.vidocq.vauban. */
    private static final Set<String> CONTAINER_MODULES = Set.of(
            "io.vidocq.vauban.api", "io.vidocq.vauban.core", "io.vidocq.vauban.indexer",
            "io.vidocq.vauban.weaver", "io.vidocq.vauban.classloader",
            "io.vidocq.vauban.classloader.spi", "io.vidocq.vauban.sjar",
            "io.vidocq.vauban.processor", "io.vidocq.vauban.junit");

    /**
     * The four-argument form with no kept roots.
     *
     * @param configuration the configuration whose modules are candidates, usually the boot layer's
     * @param keptPrefixes  extra module-name prefixes to keep, such as a runtime's own modules
     * @param roots         the application's own modules, never kept by a name rule
     * @return the locations to hand to {@link #createAppLayer}, in configuration order
     * @see #applicationPaths(Configuration, Collection, Set, Set)
     */
    public static List<Path> applicationPaths(Configuration configuration,
            Collection<String> keptPrefixes, Set<String> roots) {
        return applicationPaths(configuration, keptPrefixes, Set.of(), roots);
    }

    /**
     * The {@code file:} locations of the modules of {@code configuration} that an application layer
     * should load again under a {@link VaubanClassLoader} — the one re-layer policy of the Java SE
     * launcher ({@link Launch}) and of {@code Vidocq.run}.
     *
     * <p>Kept where they are, and read from there by the re-layered modules:
     * <ul>
     *   <li>the platform ({@code java.*}, {@code jdk.*}), Jakarta ({@code jakarta.*}) and the Vauban
     *       container modules, and every module whose name starts with one of {@code keptPrefixes};</li>
     *   <li>automatic modules — a re-layered automatic module would read its own twin in the parent
     *       layer, and resolution would fail;</li>
     *   <li>modules with a package the Vauban loader refuses to define ({@code javax.}, {@code sun.},
     *       {@code com.sun.} …), which would otherwise be empty shells in the layer;</li>
     *   <li>and, transitively, every module a kept explicit module reads: a kept module cannot read a
     *       re-layered one, so what it needs must stay with it.</li>
     * </ul>
     * Everything else with a {@code file:} location is re-layered. A module named in {@code roots} is
     * the application itself: the name rules (the prefixes and the container names) never keep it, so
     * what it reads is not held back through it either. The other rules still apply to a root, and a
     * kept module that reads a root still keeps it.
     *
     * @param configuration the configuration whose modules are candidates, usually the boot layer's
     * @param keptPrefixes  extra module-name prefixes to keep, such as a runtime's own modules
     * @param keptRoots     modules kept whatever their names, with what they read transitively: a runtime's own
     *                      roots, so that its closure stays in the parent layer (vauban#154); a module also named in
     *                      {@code roots} is not kept, because {@code roots} wins
     * @param roots         the application's own modules, never kept by a name rule
     * @return the locations to hand to {@link #createAppLayer}, in configuration order
     */
    public static List<Path> applicationPaths(Configuration configuration,
            Collection<String> keptPrefixes, Set<String> keptRoots, Set<String> roots) {
        var kept = new LinkedHashSet<ResolvedModule>();
        var queue = new ArrayDeque<ResolvedModule>();
        for (var resolved : configuration.modules()) {
            if (keptByRule(resolved, keptPrefixes, keptRoots, roots) && kept.add(resolved)) {
                queue.add(resolved);
            }
        }
        keepWhatKeptModulesRead(configuration, kept, queue);
        var paths = new LinkedHashSet<Path>();
        for (var resolved : configuration.modules()) {
            if (kept.contains(resolved)) continue;
            resolved.reference().location()
                    .flatMap(VaubanLayerFactory::archiveOf)
                    .ifPresent(paths::add);
        }
        return List.copyOf(paths);
    }

    /**
     * A kept module cannot read a re-layered one: keeps, transitively, what the modules of
     * {@code queue} read. An automatic module reads every module, so following it would keep the
     * whole application. It is kept for itself only.
     */
    private static void keepWhatKeptModulesRead(Configuration configuration, Set<ResolvedModule> kept,
            Queue<ResolvedModule> queue) {
        while (!queue.isEmpty()) {
            var module = queue.poll();
            if (!module.reference().descriptor().isAutomatic()) {
                for (var read : module.reads()) {
                    if (read.configuration() == configuration && kept.add(read)) {
                        queue.add(read);
                    }
                }
            }
        }
    }

    private static boolean keptByRule(ResolvedModule resolved, Collection<String> keptPrefixes,
            Set<String> keptRoots, Set<String> roots) {
        var descriptor = resolved.reference().descriptor();
        if (descriptor.isAutomatic()) return true;
        for (var pkg : descriptor.packages()) {
            if (VaubanClassLoader.excludesPackage(pkg)) return true;
        }
        var name = resolved.name();
        if (roots.contains(name)) return false;
        if (keptRoots.contains(name)) return true;
        if (CONTAINER_MODULES.contains(name)) return true;
        for (var prefix : KEPT_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        for (var prefix : keptPrefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static final String SERVICES_PREFIX = "META-INF/services/";

    /**
     * Wraps {@code base} so every explicit module descriptor is rebuilt with the
     * archive's {@code META-INF/services/*} declarations promoted to synthetic
     * {@code provides} directives (merged with the declared ones).
     *
     * <p>This removes the need for applications to hand-declare generated providers
     * ({@code _VaubanComponents}, Cassini {@code $$CassiniAdapter}/{@code $$CassiniRoutes},
     * any future generated SPI) in {@code module-info.java}: the standard services files
     * — which the annotation processors already emit for class-path mode — become the
     * single source of truth, and an IDE rebuild of {@code module-info.java} can no
     * longer lose the Maven plugins' sealing. Automatic modules are returned untouched
     * (the module system already derives their {@code provides} from the services
     * files).
     *
     * <p>A services file whose service type the module cannot read is not promoted: the module system rejects a
     * {@code provides} of such a type for the whole layer. {@code reactor-core} lists a micrometer
     * {@code ContextAccessor}, an optional dependency often absent (vauban#162); on the class path, or as an
     * automatic module, that file was ignored the same way.
     *
     * @param parent the layer the modules resolve against, where required modules may be found too
     */
    static ModuleFinder promotingServices(ModuleFinder base, ModuleLayer parent) {
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return base.find(name).map(reference -> promoteServices(reference, base, parent));
            }

            @Override
            public Set<ModuleReference> findAll() {
                return base.findAll().stream()
                        .map(reference -> promoteServices(reference, base, parent))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        };
    }

    private static ModuleReference promoteServices(ModuleReference reference, ModuleFinder base, ModuleLayer parent) {
        var descriptor = reference.descriptor();
        if (descriptor.isAutomatic()) {
            return reference;
        }
        Map<String, List<String>> fromFiles;
        try {
            fromFiles = readServiceFiles(reference);
        } catch (IOException | UncheckedIOException _) {
            return reference;
        }
        if (fromFiles.isEmpty()) {
            return reference;
        }

        var merged = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var provides : descriptor.provides()) {
            merged.computeIfAbsent(provides.service(), s -> new LinkedHashSet<>())
                    .addAll(provides.providers());
        }
        if (!mergeServiceFiles(merged, fromFiles, descriptor, base, parent)) {
            return reference;
        }

        var rebuilt = rebuildWithProvides(descriptor, merged);
        return new ModuleReference(rebuilt, reference.location().orElse(null)) {
            @Override
            public ModuleReader open() throws IOException {
                return reference.open();
            }
        };
    }

    /**
     * Merges into {@code merged} the providers {@code fromFiles} declares, skipping a service type the module cannot
     * read when the descriptor does not already provide it.
     *
     * @return whether a provider was added
     */
    private static boolean mergeServiceFiles(Map<String, LinkedHashSet<String>> merged,
            Map<String, List<String>> fromFiles, ModuleDescriptor descriptor, ModuleFinder base, ModuleLayer parent) {
        var changed = false;
        Set<String> readable = null;
        for (var entry : fromFiles.entrySet()) {
            var service = entry.getKey();
            if (!merged.containsKey(service)) {
                if (readable == null) {
                    readable = readablePackages(descriptor, base, parent);
                }
                if (!readable.contains(packageOf(service))) {
                    continue;
                }
            }
            var providers = merged.computeIfAbsent(service, s -> new LinkedHashSet<>());
            if (addOwnProviders(providers, entry.getValue(), descriptor)) {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Adds to {@code providers} those of {@code candidates} that live in the module. Services files may also name
     * classes from other archives on a shared class path, which are skipped.
     *
     * @return whether a provider was added
     */
    private static boolean addOwnProviders(Set<String> providers, List<String> candidates,
            ModuleDescriptor descriptor) {
        var added = false;
        for (var impl : candidates) {
            var pkg = impl.lastIndexOf('.') > 0 ? impl.substring(0, impl.lastIndexOf('.')) : "";
            if (descriptor.packages().contains(pkg) && providers.add(impl)) {
                added = true;
            }
        }
        return added;
    }

    /**
     * The packages {@code descriptor} reads: its own, and those exported to it by {@code java.base}, by the modules
     * it requires and, transitively, by what those require transitively. Found in {@code base}, whose modules are
     * all roots, or in {@code parent}; a JDK module missing from the parent layers cannot be read from a child
     * layer, so the system modules are not searched, and an unresolved {@code requires static} reads nothing.
     */
    private static Set<String> readablePackages(ModuleDescriptor descriptor, ModuleFinder base, ModuleLayer parent) {
        var readable = new LinkedHashSet<>(descriptor.packages());
        var queue = new ArrayDeque<String>();
        queue.add("java.base");
        descriptor.requires().forEach(r -> queue.add(r.name()));
        var seen = new LinkedHashSet<String>();
        while (!queue.isEmpty()) {
            var name = queue.poll();
            if (seen.add(name)) {
                base.find(name).map(ModuleReference::descriptor)
                        .or(() -> parent.findModule(name).map(Module::getDescriptor))
                        .ifPresent(module -> addReadPackages(module, descriptor.name(), readable, queue));
            }
        }
        return readable;
    }

    /**
     * Adds to {@code readable} the packages {@code module} exports to {@code reader}, and queues the modules it
     * requires transitively.
     */
    private static void addReadPackages(ModuleDescriptor module, String reader, Set<String> readable,
            Queue<String> queue) {
        if (module.isAutomatic()) {
            readable.addAll(module.packages()); // an automatic module exports every package
        }
        for (var exports : module.exports()) {
            if (!exports.isQualified() || exports.targets().contains(reader)) {
                readable.add(exports.source());
            }
        }
        for (var requires : module.requires()) {
            if (requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                queue.add(requires.name());
            }
        }
    }

    private static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    private static java.util.Map<String, List<String>> readServiceFiles(ModuleReference reference)
            throws IOException {
        var result = new LinkedHashMap<String, List<String>>();
        try (ModuleReader reader = reference.open()) {
            List<String> serviceEntries = reader.list()
                    .filter(e -> e.startsWith(SERVICES_PREFIX)
                            && e.length() > SERVICES_PREFIX.length()
                            && !e.endsWith("/"))
                    .toList();
            for (var entry : serviceEntries) {
                var service = entry.substring(SERVICES_PREFIX.length());
                var buffer = reader.read(entry).orElse(null);
                if (buffer == null) continue;
                var impls = new ArrayList<String>();
                try {
                    var bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    new String(bytes, StandardCharsets.UTF_8)
                            .lines()
                            .map(line -> {
                                int hash = line.indexOf('#');
                                return (hash >= 0 ? line.substring(0, hash) : line).strip();
                            })
                            .filter(line -> !line.isEmpty())
                            .forEach(impls::add);
                } finally {
                    reader.release(buffer);
                }
                if (!impls.isEmpty()) {
                    result.put(service, impls);
                }
            }
        }
        return result;
    }

    /** Rebuilds {@code descriptor} identically, with {@code provides} replaced. */
    private static ModuleDescriptor rebuildWithProvides(ModuleDescriptor descriptor,
            java.util.Map<String, LinkedHashSet<String>> provides) {
        var builder = ModuleDescriptor.newModule(descriptor.name(), descriptor.modifiers());
        descriptor.rawVersion().ifPresent(builder::version);
        for (var requires : descriptor.requires()) {
            builder.requires(requires);
        }
        for (var exports : descriptor.exports()) {
            builder.exports(exports);
        }
        if (!descriptor.isOpen()) {
            for (var opens : descriptor.opens()) {
                builder.opens(opens);
            }
        }
        for (var uses : descriptor.uses()) {
            builder.uses(uses);
        }
        builder.packages(descriptor.packages());
        descriptor.mainClass().ifPresent(builder::mainClass);
        provides.forEach((service, providers) ->
                builder.provides(service, List.copyOf(providers)));
        return builder.build();
    }

    /**
     * The archive a resolved module's bytes can be read from, when there is one.
     *
     * <p>Two shapes occur, and both work the same way once opened, because a module is either a jar
     * or a directory of classes:
     *
     * <ul>
     *   <li>{@code file:} — a jar or an exploded directory on a module path;</li>
     *   <li>{@code jrt:} — a module linked into a runtime image by {@code jlink}, readable as
     *       {@code /modules/<name>} of the {@code jrt} file system. Dropping these is what used to
     *       leave a jlink image with nothing to re-layer, and therefore without the placed proxies,
     *       sjar decryption and load-time weaving the layer's loader provides.</li>
     * </ul>
     *
     * <p>Anything else (a module with no location, or a scheme with no file system behind it) is
     * skipped: the layer can only own archives it can actually read.
     */
    static java.util.Optional<Path> archiveOf(java.net.URI location) {
        String scheme = location.getScheme();
        if ("file".equals(scheme)) {
            return java.util.Optional.of(Path.of(location));
        }
        if ("jrt".equals(scheme)) {
            // jrt:/<module> names the module; its content lives under /modules/<module>.
            String module = location.getPath().replaceFirst("^/", "");
            if (module.isEmpty()) {
                return java.util.Optional.empty();
            }
            try {
                var image = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
                Path path = image.getPath("/modules/" + module);
                return java.nio.file.Files.isDirectory(path)
                        ? java.util.Optional.of(path) : java.util.Optional.empty();
            } catch (RuntimeException _) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }
}
