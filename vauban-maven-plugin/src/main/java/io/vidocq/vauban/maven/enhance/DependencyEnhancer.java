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
package io.vidocq.vauban.maven.enhance;

import io.vidocq.vauban.core.provider.ComponentProviderClassGenerator;
import io.vidocq.vauban.core.proxy.ProducerProxyEligibility;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Produces an <em>enhanced copy</em> of a third-party dependency jar (issue #42, Stage 2 — the
 * {@code vauban:enhance-dependencies} goal) so that fully-public is no longer required: because the
 * copy is ours to rewrite, the generated {@code <Type>_ClientProxy} lands in the type's <strong>own
 * package</strong> (no split package) and can therefore forward package-private and protected
 * methods. Adds, per enhanced type: a co-located client proxy; per package a {@code _VaubanComponents}
 * provider; and a rewritten {@code module-info.class} declaring
 * {@code provides io.vidocq.vauban.api.VaubanComponentProvider ...} (+ a class-path service file).
 *
 * <p>The original jar is never touched; the enhanced copy shadows it on the module path (its
 * signature is invalidated, which is why the goal is opt-in). The container then resolves the
 * produced type through the provider — zero {@code opens}, no runtime class definition, no agent.
 */
public final class DependencyEnhancer {

    private static final String MODULE_INFO = "module-info.class";
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final String SERVICE_FILE = "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";

    /** What an enhancement produced. */
    public record Result(Path enhancedJar, List<String> enhancedTypes, List<String> providerFqns) {}

    private DependencyEnhancer() {}

    /**
     * Enhance {@code sourceJar} for the {@code typeFqns} that live in it, writing an enhanced copy to
     * {@code outputDir}. Types that cannot be proxied (final, abstract, interface, no accessible
     * constructor, or a non-static final method) are skipped with a warning.
     */
    public static Result enhance(Path sourceJar, String coordinates, Path outputDir,
            List<String> typeFqns, ClassLoader loader, List<String> warnings) throws IOException {
        // Generate proxies, grouped by package.
        var proxyBytesByName = new LinkedHashMap<String, byte[]>();
        var proxyFqnsByPackage = new LinkedHashMap<String, List<String>>();
        var enhanced = new ArrayList<String>();
        for (var fqn : typeFqns) {
            Class<?> type;
            try {
                type = Class.forName(fqn, false, loader);
            } catch (Throwable t) {
                warnings.add("enhance: cannot load " + fqn + " (" + t + ")");
                continue;
            }
            if (!canProxy(type, warnings)) {
                continue;
            }
            // Rewriting someone else's artefact is the heavy answer. When the only obstacle is the
            // package boundary, the build ships the very same proxy as a resource and the Vauban
            // class loader defines it inside the type's own package, jar untouched.
            var verdict = ProducerProxyEligibility.of(type);
            if (verdict.placeable()) {
                warnings.add("enhance: " + type.getName() + " only needs its client proxy inside"
                        + " its own package (" + verdict + "). The Vauban class loader ships and"
                        + " defines that proxy without touching the jar, while rewriting a copy"
                        + " here drops the jar's signature. Keep this goal for what placement"
                        + " cannot do: no Vauban layer at launch, a package the library does not"
                        + " export, or a GraalVM native image.");
            }
            var proxy = RuntimeClientProxyGenerator.generate(type);
            proxyBytesByName.put(proxy.className(), proxy.bytecode());
            proxyFqnsByPackage.computeIfAbsent(packageOf(fqn), k -> new ArrayList<>())
                    .add(proxy.className());
            enhanced.add(fqn);
        }
        if (enhanced.isEmpty()) {
            return new Result(null, List.of(), List.of());
        }

        // One _VaubanComponents per package.
        var providerBytesByName = new LinkedHashMap<String, byte[]>();
        var providerFqns = new ArrayList<String>();
        for (var e : proxyFqnsByPackage.entrySet()) {
            var providerFqn = e.getKey().isEmpty()
                    ? "_VaubanComponents"
                    : e.getKey() + "._VaubanComponents";
            var gen = ComponentProviderClassGenerator.generate(
                    providerFqn, List.of(), List.of(), List.of(), e.getValue());
            providerBytesByName.put(gen.className(), gen.bytecode());
            providerFqns.add(gen.className());
        }

        Files.createDirectories(outputDir);
        var enhancedJar = outputDir.resolve(sourceJar.getFileName());

        try (var in = new JarFile(sourceJar.toFile());
             var out = new JarOutputStream(Files.newOutputStream(enhancedJar))) {
            // The manifest goes first so JarInputStream readers find it, and it is the one place
            // where this copy declares that it is not the artefact the coordinates name.
            writeEntry(out, MANIFEST, provenanceManifest(in, sourceJar, coordinates));
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (name.equals(MANIFEST)) {
                    continue; // rewritten above
                }
                if (isSignatureFile(name)) {
                    // The content this signature attests no longer matches; carrying it over would
                    // be misleading at best and a SecurityException at worst. Provenance is recorded
                    // in the manifest instead.
                    continue;
                }
                byte[] bytes;
                try (InputStream is = in.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }
                if (name.equals(MODULE_INFO)) {
                    bytes = ModuleInfoRewriter.addComponentProvider(bytes, providerFqns);
                } else if (name.equals(SERVICE_FILE)) {
                    continue; // rewritten below (merged)
                }
                writeEntry(out, name, bytes);
            }
            // New generated classes.
            for (var pe : proxyBytesByName.entrySet()) {
                writeEntry(out, pe.getKey().replace('.', '/') + ".class", pe.getValue());
            }
            for (var pe : providerBytesByName.entrySet()) {
                writeEntry(out, pe.getKey().replace('.', '/') + ".class", pe.getValue());
            }
            // Class-path service registration (module path uses the rewritten module-info).
            writeEntry(out, SERVICE_FILE,
                    (String.join("\n", providerFqns) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Result(enhancedJar, enhanced, providerFqns);
    }

    private static boolean canProxy(Class<?> type, List<String> warnings) {
        if (type.isInterface() || type.isEnum() || type.isAnnotation()
                || type.isArray() || type.isPrimitive() || type.isRecord()) {
            warnings.add("enhance: " + type.getName() + " is not a proxyable class");
            return false;
        }
        int mods = type.getModifiers();
        if (Modifier.isFinal(mods)) {
            warnings.add("enhance: " + type.getName() + " is final (unproxyable)");
            return false;
        }
        if (Modifier.isAbstract(mods)) {
            warnings.add("enhance: " + type.getName() + " is abstract (unproxyable)");
            return false;
        }
        boolean ctor = false;
        for (var c : type.getDeclaredConstructors()) {
            if (!Modifier.isPrivate(c.getModifiers())) {
                ctor = true;
                break;
            }
        }
        if (!ctor) {
            warnings.add("enhance: " + type.getName() + " has no non-private constructor (unproxyable)");
            return false;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                int mm = m.getModifiers();
                if (Modifier.isStatic(mm) || Modifier.isPrivate(mm)) {
                    continue;
                }
                if (Modifier.isFinal(mm)) {
                    warnings.add("enhance: " + type.getName() + " has a non-static final method "
                            + m.getName() + " (unproxyable per CDI 4.1 §3.10)");
                    return false;
                }
            }
        }
        return true;
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] bytes) throws IOException {
        var entry = new JarEntry(name);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /**
     * The enhanced copy's manifest: the original main attributes, plus the provenance the copy owes
     * to whoever reads it downstream (SBOM tooling, an attestation check, a puzzled human). The
     * per-entry sections are dropped — they hold the digests of the discarded signature.
     */
    private static byte[] provenanceManifest(JarFile in, Path sourceJar, String coordinates)
            throws IOException {
        var manifest = in.getManifest();
        var copy = new Manifest();
        if (manifest != null) {
            copy.getMainAttributes().putAll(manifest.getMainAttributes());
        }
        var main = copy.getMainAttributes();
        main.putValue("Manifest-Version", "1.0");
        main.putValue("Vauban-Enhanced-From", coordinates == null ? "unknown" : coordinates);
        main.putValue("Vauban-Enhanced-Digest", digestOf(sourceJar));
        main.putValue("Vauban-Enhanced-By", "vauban-maven-plugin/" + io.vidocq.vauban.api.Vauban.VERSION);
        var bytes = new java.io.ByteArrayOutputStream();
        copy.write(bytes);
        return bytes.toByteArray();
    }

    /** {@code sha256:<hex>} over the source artefact, pinning exactly what this copy derives from. */
    private static String digestOf(Path jar) throws IOException {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(Files.readAllBytes(jar)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is mandated by the platform", impossible);
        }
    }

    /** A jar signature file: {@code META-INF/<name>.SF|.DSA|.RSA|.EC}, not in a subdirectory. */
    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        var upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".DSA")
                || upper.endsWith(".RSA") || upper.endsWith(".EC");
    }
}
