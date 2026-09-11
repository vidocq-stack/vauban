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

import io.vidocq.vauban.classloader.spi.ArchiveContext;
import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import io.vidocq.vauban.classloader.spi.ClassTransformerPlugin;
import io.vidocq.vauban.classloader.spi.PluginContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The universal Vauban class loader: the single control point for application class
 * loading. Each archive is opened by the first {@link ByteSourcePlugin} that handles it
 * (sjar decryption…), plain jars and exploded directories by built-in readers; every class
 * then flows through the {@link ClassTransformerPlugin} chain (priority order) before
 * {@code defineClass}. The decrypt → weave composition falls out of this chaining — an
 * unwoven third-party sjar can be fixed nowhere else.
 *
 * <p>Scope: <strong>application archives only</strong> — the engine refuses to define
 * platform or Vauban classes even if an archive bundles them ({@link #EXCLUDED_PREFIXES}),
 * and standard parent-first delegation keeps everything not present in the managed
 * archives with the parent loader.
 *
 * <p>Diagnostics: every transformation is logged; {@code -Dvauban.classloader.dump=<dir>}
 * writes transformed bytes for inspection; a transformer failure fails the class load
 * (nothing silent). Individual transformers can be disabled with
 * {@code -Dvauban.classloader.transformers.disabled=<name,name>}.
 */
public class VaubanClassLoader extends ClassLoader implements AutoCloseable {

    /** Comma-separated transformer names to disable. */
    public static final String DISABLED_TRANSFORMERS_PROPERTY =
            "vauban.classloader.transformers.disabled";
    /** Directory where transformed class bytes are dumped (debugging). */
    public static final String DUMP_PROPERTY = "vauban.classloader.dump";

    private static final System.Logger LOG = System.getLogger(VaubanClassLoader.class.getName());
    /**
     * Never defined by this loader even when an archive bundles them: the platform, and
     * the container's own modules (precise prefixes — application code may legitimately
     * live under {@code io.vidocq.vauban.example…}-style namespaces).
     */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "io.vidocq.vauban.api.", "io.vidocq.vauban.core.", "io.vidocq.vauban.indexer.",
            "io.vidocq.vauban.weaver.", "io.vidocq.vauban.classloader.",
            "io.vidocq.vauban.sjar.", "io.vidocq.vauban.processor.");

    static {
        registerAsParallelCapable();
    }

    private final List<Archive> archives;
    /** internal class entry name (a/b/C.class) → owning archive. */
    private final Map<String, Archive> classIndex;
    private final List<ClassTransformerPlugin> transformers;
    /**
     * Delegation order for classes present in the managed archives. {@code false}
     * (default — the scanner/sjar path): standard parent-first, so clear types shared
     * with the parent world (an sjar's API classes on the class path…) stay unique.
     * {@code true} (the module-layer path): self-first, mandatory when the same module
     * exists in a parent layer — parent-first would resolve every internal reference of
     * a layer class against its un-woven boot-layer twin.
     */
    private final boolean selfFirst;

    /** One managed archive: its reader, and the context handed to transformers. */
    private final class Archive {
        final Path path;
        final ArchiveReader reader;
        final ArchiveContext context;
        volatile Optional<Set<String>> beansList;

        Archive(Path path, ArchiveReader reader) {
            this.path = path;
            this.reader = reader;
            this.context = new ArchiveContext() {
                @Override
                public Path archivePath() {
                    return path;
                }

                @Override
                public ArchiveReader reader() {
                    return reader;
                }

                @Override
                public Optional<Set<String>> beansList() {
                    return Archive.this.beansList();
                }

                @Override
                public Set<String> placedProxyTypes() {
                    return VaubanClassLoader.this.placedProxyTypes();
                }

                @Override
                public byte[] classBytes(String binaryName) {
                    return VaubanClassLoader.this.rawClassBytes(binaryName);
                }
            };
        }

        Optional<Set<String>> beansList() {
            var list = beansList;
            if (list == null) {
                try {
                    list = reader.readResource("META-INF/vauban-beans.list")
                            .map(bytes -> {
                                var names = new LinkedHashSet<String>();
                                new String(bytes, StandardCharsets.UTF_8).lines()
                                        .map(String::strip)
                                        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                                        .forEach(names::add);
                                return (Set<String>) names;
                            });
                } catch (IOException e) {
                    list = Optional.empty();
                }
                beansList = list;
            }
            return list;
        }
    }

    /**
     * Opens {@code archivePaths} (order = lookup order) with the discovered
     * {@link ByteSourcePlugin}s (built-in jar/directory readers as fallback) and the
     * discovered {@link ClassTransformerPlugin}s.
     */
    public static VaubanClassLoader of(List<Path> archivePaths, ClassLoader parent,
            PluginContext pluginContext) throws IOException {
        return new VaubanClassLoader("vauban-app", archivePaths, parent, pluginContext,
                List.of(), false);
    }

    /**
     * Variant with explicitly registered source plugins (e.g. from
     * {@code VaubanContainerBuilder.addByteSourcePlugin}), tried before the
     * ServiceLoader-discovered ones.
     */
    public static VaubanClassLoader of(List<Path> archivePaths, ClassLoader parent,
            PluginContext pluginContext, List<ByteSourcePlugin> explicitSources) throws IOException {
        return new VaubanClassLoader("vauban-app", archivePaths, parent, pluginContext,
                explicitSources, false);
    }

    /** Self-first variant — reserved for module-layer definition ({@code VaubanLayerFactory}). */
    static VaubanClassLoader forLayer(List<Path> archivePaths, ClassLoader parent,
            PluginContext pluginContext) throws IOException {
        return new VaubanClassLoader("vauban-app-layer", archivePaths, parent, pluginContext,
                List.of(), true);
    }

    protected VaubanClassLoader(String name, List<Path> archivePaths, ClassLoader parent,
            PluginContext pluginContext, List<ByteSourcePlugin> explicitSources,
            boolean selfFirst) throws IOException {
        super(name, parent);
        this.selfFirst = selfFirst;
        var sources = new ArrayList<>(explicitSources);
        sources.addAll(sourcePlugins());
        this.archives = new ArrayList<>();
        this.classIndex = new LinkedHashMap<>();
        for (var path : archivePaths) {
            var reader = openArchive(path, sources, pluginContext);
            var archive = new Archive(path, reader);
            archives.add(archive);
            for (var entry : reader.classEntries()) {
                classIndex.putIfAbsent(entry, archive);
            }
        }
        this.transformers = transformerPlugins();
    }

    private static ArchiveReader openArchive(Path path, List<ByteSourcePlugin> sources,
            PluginContext pluginContext) throws IOException {
        for (var plugin : sources) {
            if (plugin.handles(path)) {
                return plugin.open(path, pluginContext);
            }
        }
        return BuiltInReaders.open(path);
    }

    private static List<ByteSourcePlugin> sourcePlugins() {
        var plugins = new ArrayList<ByteSourcePlugin>();
        ServiceLoader.load(ByteSourcePlugin.class, VaubanClassLoader.class.getClassLoader())
                .forEach(plugins::add);
        plugins.sort(Comparator.comparingInt(ByteSourcePlugin::priority));
        return plugins;
    }

    private static List<ClassTransformerPlugin> transformerPlugins() {
        var disabled = Set.of(System.getProperty(DISABLED_TRANSFORMERS_PROPERTY, "")
                .split("\\s*,\\s*"));
        var plugins = new ArrayList<ClassTransformerPlugin>();
        ServiceLoader.load(ClassTransformerPlugin.class, VaubanClassLoader.class.getClassLoader())
                .forEach(p -> {
                    if (disabled.contains(p.name())) {
                        LOG.log(System.Logger.Level.INFO,
                                () -> "Transformer disabled by configuration: " + p.name());
                    } else {
                        plugins.add(p);
                    }
                });
        plugins.sort(Comparator.comparingInt(ClassTransformerPlugin::priority));
        return plugins;
    }

    /** The archives this loader manages, in lookup order. */
    public List<Path> archivePaths() {
        return archives.stream().map(a -> a.path).toList();
    }

    /** {@code true} when {@code binaryName} lives in one of the managed archives. */
    public boolean managesClass(String binaryName) {
        return classIndex.containsKey(toEntry(binaryName));
    }

    // ------------------------------------------------------------ placed proxies (#42 Stage 4)

    /**
     * Build-time placement manifest, one produced-type binary name per line: the types whose
     * client proxy must live in their own package (a non-public overridable member, or no
     * accessible constructor). Written by the annotation processor into the bean archive.
     */
    public static final String PLACEMENT_MANIFEST = "META-INF/vauban/required-opens.list";
    /** Resource prefix under which the annotation processor ships the placed proxy bytes. */
    public static final String PLACED_PREFIX = "META-INF/vauban/placed/";
    private static final String CLIENT_PROXY_SUFFIX = "_ClientProxy";
    private volatile Set<String> placedTypes;

    /**
     * Whether {@code binaryName} is a client proxy this loader can <em>place</em>: it is not a
     * class entry of any archive, it is named {@code <produced>_ClientProxy} for a produced type
     * that this loader defines and that a placement manifest lists, and some archive ships its
     * bytes under {@link #PLACED_PREFIX}. A placed class is defined into the produced type's own
     * package by the loader that owns that package — which is why it needs no {@code opens}.
     */
    public boolean placesClass(String binaryName) {
        var produced = producedTypeOf(binaryName);
        return !produced.isEmpty() && !managesClass(binaryName) && !isExcluded(binaryName)
                && placedProxyTypes().contains(produced)
                && classIndex.containsKey(toEntry(produced))
                && placedBytes(binaryName) != null;
    }

    /** The union of every placement manifest visible through the archives, read once. */
    Set<String> placedProxyTypes() {
        var types = placedTypes;
        if (types == null) {
            var names = new LinkedHashSet<String>();
            for (var archive : archives) {
                try {
                    archive.reader.readResource(PLACEMENT_MANIFEST).ifPresent(bytes ->
                            new String(bytes, StandardCharsets.UTF_8).lines()
                                    .map(String::strip)
                                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                                    .forEach(names::add));
                } catch (IOException e) {
                    // an unreadable manifest places nothing from that archive
                }
            }
            types = Collections.unmodifiableSet(names);
            placedTypes = types;
        }
        return types;
    }

    private static String producedTypeOf(String proxyBinaryName) {
        return proxyBinaryName.endsWith(CLIENT_PROXY_SUFFIX)
                ? proxyBinaryName.substring(0, proxyBinaryName.length() - CLIENT_PROXY_SUFFIX.length())
                : "";
    }

    /** The placed bytes of {@code proxyBinaryName} from whichever archive ships them, or null. */
    private byte[] placedBytes(String proxyBinaryName) {
        var entry = PLACED_PREFIX + toEntry(proxyBinaryName);
        for (var archive : archives) {
            try {
                var bytes = archive.reader.readResource(entry);
                if (bytes.isPresent()) return bytes.get();
            } catch (IOException e) {
                // try the next archive
            }
        }
        return null;
    }

    /**
     * Define a placed client proxy into the produced type's own package. The bytes go through
     * the same transformer chain as any archive class, with the <em>produced type's</em> archive
     * as context, so the cdi-proxifier can retarget the proxy onto the entry constructor it weaves
     * into that type at its own definition.
     */
    private Class<?> placeClass(String name) throws ClassNotFoundException {
        if (isExcluded(name)) {
            throw new ClassNotFoundException(name);
        }
        var produced = producedTypeOf(name);
        if (produced.isEmpty() || !placedProxyTypes().contains(produced)) {
            throw new ClassNotFoundException(name);
        }
        var bytes = placedBytes(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        var owner = classIndex.get(toEntry(produced));
        if (owner == null) {
            throw new ClassNotFoundException("Cannot place " + name + ": " + produced
                    + " is not defined by this loader");
        }
        bytes = applyTransformers(name, bytes, owner);
        LOG.log(System.Logger.Level.DEBUG, () -> "Placed " + name + " into the package of "
                + produced + " (" + owner.path.getFileName() + ")");
        return defineClass(name, bytes, 0, bytes.length);
    }

    /** Binary names of every class the managed archives contain, in archive order. */
    public List<String> managedClassNames() {
        return classIndex.keySet().stream()
                .map(entry -> entry.substring(0, entry.length() - ".class".length())
                        .replace('/', '.'))
                .toList();
    }

    /**
     * <b>Self-first for managed archives</b>: a class present in this loader's archives
     * is defined HERE even when a parent could also resolve it. Required by the
     * trampoline launch, where the application is resolved both in the boot layer (the
     * IDE put it on the module path) and in this loader's layer: parent-first delegation
     * would resolve every internal reference of a layer class against its un-woven
     * boot-layer twin — a different module of the same name, which this layer's modules
     * do not even read. Anything outside the archives (and every excluded prefix) keeps
     * the standard parent-first path.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!selfFirst) {
            return super.loadClass(name, resolve);   // standard parent-first
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            // Self-first for every name in a package this loader owns, not only for archive
            // entries. A class defined at runtime in such a package (a placed proxy, the reflective
            // fallback's <Type>_ClientProxy, a <Bean>$$Intercepted subclass) must extend THIS
            // layer's types, so a same-named class in the parent is always the wrong one: in a
            // layer, a package belongs to one module and so to one loader. A miss throws
            // ClassNotFoundException, which is what sends the runtime fallback to define the class
            // inside this loader's package rather than pick up the parent's.
            if (loaded == null && !isExcluded(name) && ownsPackage(name)) {
                loaded = findClass(name);
            }
            if (loaded == null) {
                return super.loadClass(name, resolve);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isExcluded(String name) {
        for (var prefix : EXCLUDED_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Whether this loader refuses to define the classes of package {@code pkg} (platform and
     * container prefixes). The launcher keeps a module owning such a package in the boot layer:
     * re-layered, it would be an empty shell whose classes come back from its boot twin.
     */
    static boolean excludesPackage(String pkg) {
        return isExcluded(pkg + ".");
    }

    private volatile Set<String> ownedPackages;

    /** Whether the package of {@code binaryName} is one of the packages of this loader's archives. */
    private boolean ownsPackage(String binaryName) {
        var packages = ownedPackages;
        if (packages == null) {
            var set = new java.util.HashSet<String>();
            for (var entry : classIndex.keySet()) {
                var slash = entry.lastIndexOf('/');
                set.add(slash < 0 ? "" : entry.substring(0, slash).replace('/', '.'));
            }
            packages = Set.copyOf(set);
            ownedPackages = packages;
        }
        var dot = binaryName.lastIndexOf('.');
        return packages.contains(dot < 0 ? "" : binaryName.substring(0, dot));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        var archive = classIndex.get(toEntry(name));
        if (archive == null) {
            // Not an archive class: perhaps a client proxy to be placed into a package we own.
            return placeClass(name);
        }
        if (isExcluded(name)) {
            throw new ClassNotFoundException(
                    "Refusing to define excluded class " + name + " from " + archive.path);
        }
        byte[] bytes;
        try {
            bytes = archive.reader.readClass(toEntry(name));
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        bytes = applyTransformers(name, bytes, archive);
        return defineClass(name, bytes, 0, bytes.length);
    }

    /**
     * Module-aware variant invoked by the JVM for classes of the modules a
     * {@code ModuleLayer} defines to this loader ({@code VaubanLayerFactory}) — the
     * inherited default returns {@code null}, which would make every layer class
     * unloadable unless something had already triggered its definition through the plain
     * name-based path. One loader serves the whole layer, so the module name adds nothing
     * to the lookup.
     */
    @Override
    protected Class<?> findClass(String moduleName, String name) {
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Module-aware resource lookup — same single-loader reasoning as above. */
    @Override
    protected URL findResource(String moduleName, String name) {
        return findResource(name);
    }

    /** Runs the transformer chain; also used by the layer loader (M2). */
    protected final byte[] applyTransformers(String name, byte[] bytes, Object archiveHandle) {
        var archive = (Archive) archiveHandle;
        for (var transformer : transformers) {
            if (!transformer.interested(name, archive.context)) continue;
            byte[] transformed;
            try {
                transformed = transformer.transform(name, bytes, archive.context);
            } catch (RuntimeException e) {
                // Nothing silent: a broken transformer must not let a half-defined app run
                throw new LinkageError("Transformer '" + transformer.name()
                        + "' failed on " + name + " (" + archive.path + ")", e);
            }
            if (transformed != null) {
                LOG.log(System.Logger.Level.DEBUG, () -> "Transformed " + name
                        + " with '" + transformer.name() + "' (" + archive.path.getFileName() + ")");
                dump(name, transformer.name(), transformed);
                bytes = transformed;
            }
        }
        return bytes;
    }

    Object archiveHandleOf(String binaryName) {
        return classIndex.get(toEntry(binaryName));
    }

    /** Raw (source-resolved, untransformed) bytes of a managed class, or {@code null}. */
    byte[] rawClassBytes(String binaryName) {
        var archive = classIndex.get(toEntry(binaryName));
        if (archive == null) return null;
        try {
            return archive.reader.readClass(toEntry(binaryName));
        } catch (IOException e) {
            return null;
        }
    }

    private static void dump(String name, String transformer, byte[] bytes) {
        var dir = System.getProperty(DUMP_PROPERTY);
        if (dir == null) return;
        try {
            var file = Path.of(dir, transformer, name.replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot dump " + name + ": " + e);
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        for (var archive : archives) {
            try {
                var resource = archive.reader.readResource(name);
                if (resource.isPresent()) {
                    return new ByteArrayInputStream(resource.get());
                }
            } catch (IOException e) {
                // try the next archive
            }
        }
        return super.getResourceAsStream(name);
    }

    @Override
    protected URL findResource(String name) {
        for (var archive : archives) {
            var url = resourceUrl(archive, name);
            if (url != null) return url;
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        var urls = new ArrayList<URL>();
        for (var archive : archives) {
            var url = resourceUrl(archive, name);
            if (url != null) urls.add(url);
        }
        return Collections.enumeration(urls);
    }

    /**
     * A {@code vauban:} URL whose connection serves the (decrypted) bytes from the
     * archive reader — plain-jar entries could use {@code jar:} URLs, but sjar entries
     * have no standard representation, so one scheme serves both.
     */
    private URL resourceUrl(Archive archive, String name) {
        try {
            var resource = archive.reader.readResource(name);
            if (resource.isEmpty()) return null;
            var bytes = resource.get();
            var handler = new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL u) {
                    return new URLConnection(u) {
                        @Override
                        public void connect() {
                            connected = true;
                        }

                        @Override
                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(bytes);
                        }
                    };
                }
            };
            return URL.of(java.net.URI.create("vauban:" + archive.path.toUri() + "!/" + name), handler);
        } catch (IOException e) {
            return null;
        }
    }

    private static String toEntry(String binaryName) {
        return binaryName.replace('.', '/') + ".class";
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (var archive : archives) {
            try {
                archive.reader.close();
            } catch (Exception e) {
                if (first == null) first = new IOException("Failed closing " + archive.path, e);
            }
        }
        if (first != null) throw first;
    }
}
