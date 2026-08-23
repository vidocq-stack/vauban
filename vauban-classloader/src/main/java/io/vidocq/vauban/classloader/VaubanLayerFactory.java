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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        var finder = promotingServices(ModuleFinder.of(appPaths.toArray(Path[]::new)));
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
                    .filter(uri -> "file".equals(uri.getScheme()))
                    .ifPresent(uri -> archives.add(Path.of(uri)));
        }

        var loader = VaubanClassLoader.forLayer(List.copyOf(archives), parentLoader, pluginContext);
        var controller = ModuleLayer.defineModules(configuration, List.of(parentLayer),
                moduleName -> loader);
        return new AppLayer(controller.layer(), loader, roots, controller);
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
     */
    static ModuleFinder promotingServices(ModuleFinder base) {
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return base.find(name).map(VaubanLayerFactory::promoteServices);
            }

            @Override
            public Set<ModuleReference> findAll() {
                return base.findAll().stream()
                        .map(VaubanLayerFactory::promoteServices)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        };
    }

    private static ModuleReference promoteServices(ModuleReference reference) {
        var descriptor = reference.descriptor();
        if (descriptor.isAutomatic()) {
            return reference;
        }
        Map<String, List<String>> fromFiles;
        try {
            fromFiles = readServiceFiles(reference);
        } catch (IOException | UncheckedIOException unreadable) {
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
        var changed = false;
        for (var entry : fromFiles.entrySet()) {
            var providers = merged.computeIfAbsent(entry.getKey(), s -> new LinkedHashSet<>());
            for (var impl : entry.getValue()) {
                // A provider must live in this module — services files may also name
                // classes from other archives on a shared class path; skip those.
                var pkg = impl.lastIndexOf('.') > 0 ? impl.substring(0, impl.lastIndexOf('.')) : "";
                if (descriptor.packages().contains(pkg) && providers.add(impl)) {
                    changed = true;
                }
            }
        }
        if (!changed) {
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
}
