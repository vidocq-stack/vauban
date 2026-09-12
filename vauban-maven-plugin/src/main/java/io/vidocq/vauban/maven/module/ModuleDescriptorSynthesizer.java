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
package io.vidocq.vauban.maven.module;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

/**
 * Gives a jar that has no module descriptor one of its own.
 *
 * <p>This is the work a build used to delegate to ModiTect: derive {@code requires} from what the
 * jar uses, export what it contains, promote {@code META-INF/services} to {@code provides}, and put
 * the result into a copy of the jar. It is done here with the JDK alone — the Class-File API writes
 * the descriptor directly, so nothing has to generate {@code module-info.java} and run a compiler
 * over it, and {@link ModuleFinder} answers the one question that matters: which module owns a
 * package.
 *
 * <p><strong>How {@code requires} is derived.</strong> Every class of the jar is read and every
 * type name in its constant pool is collected — descriptors, internal names, annotation types,
 * generic signatures alike. Each referenced package is then attributed to the module that exports
 * it (a JDK module, or a module of the dependency closure; an automatic module owns all of its
 * packages). Packages nobody owns are reported as notes rather than guessed at. The collection errs
 * towards completeness: a spurious {@code requires} costs nothing, a missing one breaks the
 * application at run time. What no static analysis can see — {@code Class.forName} on a computed
 * name — is invisible to jdeps as well.
 */
public final class ModuleDescriptorSynthesizer {

    /** A class name written as a type descriptor, as in a field, method or annotation descriptor. */
    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([A-Za-z_$][\\w$]*(?:/[\\w$]+)*);");
    /** A bare internal name, as a constant-pool class entry holds it. */
    private static final Pattern INTERNAL_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:/[\\w$]+)+");
    /** Signature files: a descriptor added to a jar invalidates them, so they are not carried over. */
    private static final Pattern SIGNATURE_FILE =
            Pattern.compile("META-INF/[^/]+\\.(SF|DSA|RSA|EC)", Pattern.CASE_INSENSITIVE);

    private static final String SERVICES = "META-INF/services/";
    private static final String MODULE_INFO = "module-info.class";

    private ModuleDescriptorSynthesizer() {}

    /**
     * @param jar the jar to describe — it must not already carry a descriptor
     * @param moduleName the module name to give it, usually the automatic name javac already resolved
     * @param open {@code true} for an {@code open module}: everything stays reflectively reachable,
     *        which is what a library moving off the class path needs
     * @param closure every jar of the dependency closure, this one included — the search space for
     *        "which module owns this package"
     * @param uses service types this jar looks up through {@code ServiceLoader}; they cannot be
     *        derived from bytes alone, so the caller supplies them
     * @param version the module version to stamp, or {@code null} — a jar's version lives in its
     *        file name or its manifest, so only the caller knows it
     */
    public record Request(Path jar, String moduleName, boolean open, List<Path> closure,
                          Set<String> uses, String version) {

        public Request {
            Objects.requireNonNull(jar, "jar");
            Objects.requireNonNull(moduleName, "moduleName");
            closure = List.copyOf(closure);
            uses = Set.copyOf(uses);
        }

        /** Without a version — the common case when nothing knows one. */
        public Request(Path jar, String moduleName, boolean open, List<Path> closure, Set<String> uses) {
            this(jar, moduleName, open, closure, uses, null);
        }
    }

    /**
     * @param moduleInfo the bytes of the synthesized {@code module-info.class}
     * @param descriptor those bytes read back by the JDK — what the module system will see
     * @param notes anything a build log should carry: packages with no owner, dropped providers
     */
    public record Result(byte[] moduleInfo, ModuleDescriptor descriptor, List<String> notes) {

        public Result {
            notes = List.copyOf(notes);
        }
    }

    /** Synthesize a descriptor for {@code request.jar()}, without touching the jar itself. */
    public static Result synthesize(Request request) throws IOException {
        var notes = new ArrayList<String>();
        Set<String> packages;
        Set<String> referenced;
        Map<String, List<String>> services;
        try (var jar = new JarFile(request.jar().toFile())) {
            packages = packagesOf(jar);
            referenced = referencedPackages(jar);
            services = servicesOf(jar, packages, notes);
        }
        if (packages.isEmpty()) {
            throw new IOException("cannot modularize " + request.jar().getFileName()
                    + ": it holds no class in any package");
        }

        var owners = packageOwners(request, notes);
        var requires = new TreeSet<String>();
        for (String pkg : referenced) {
            if (packages.contains(pkg)) {
                continue; // its own code
            }
            String owner = owners.get(pkg);
            if (owner == null) {
                notes.add("DEBUG no module of the closure owns package " + pkg
                        + ", referenced by " + request.jar().getFileName() + ": no requires added");
            } else if (!owner.equals(request.moduleName()) && !"java.base".equals(owner)) {
                requires.add(owner);
            }
        }

        byte[] moduleInfo = emit(request, packages, requires, services);
        var descriptor = ModuleDescriptor.read(new java.io.ByteArrayInputStream(moduleInfo));
        return new Result(moduleInfo, descriptor, notes);
    }

    /**
     * Write a copy of {@code jar} into {@code outDir}, under the same file name, carrying
     * {@code moduleInfo} at its root. Signature files are left behind: adding an entry invalidates
     * them, and a jar whose signature no longer verifies fails to load at all.
     *
     * @return the path of the copy
     */
    public static Path writeJarWithDescriptor(Path jar, byte[] moduleInfo, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Path out = outDir.resolve(jar.getFileName().toString());
        try (var source = new JarFile(jar.toFile());
             var target = new JarOutputStream(Files.newOutputStream(out))) {
            var names = source.stream().map(JarEntry::getName).toList();
            for (String name : names) {
                if (MODULE_INFO.equals(name) || SIGNATURE_FILE.matcher(name).matches()) {
                    continue;
                }
                var entry = source.getJarEntry(name);
                target.putNextEntry(new JarEntry(name));
                if (!entry.isDirectory()) {
                    try (InputStream in = source.getInputStream(entry)) {
                        in.transferTo(target);
                    }
                }
                target.closeEntry();
            }
            target.putNextEntry(new JarEntry(MODULE_INFO));
            target.write(moduleInfo);
            target.closeEntry();
        }
        return out;
    }

    // ---- descriptor assembly -------------------------------------------------------------------

    private static byte[] emit(Request request, Set<String> packages, Set<String> requires,
                               Map<String, List<String>> services) {
        var packageDescs = packages.stream().map(PackageDesc::of).toList();
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of(request.moduleName()), mb -> {
            mb.moduleFlags(request.open() ? ClassFile.ACC_OPEN : 0);
            if (request.version() != null && !request.version().isBlank()) {
                mb.moduleVersion(request.version());
            }
            mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
            for (String required : requires) {
                mb.requires(ModuleDesc.of(required), 0, null);
            }
            // A library coming off the class path had every package visible; keep it that way,
            // rather than guessing which ones its users import.
            for (PackageDesc pkg : packageDescs) {
                mb.exports(pkg, 0);
            }
            if (!request.open()) {
                // An open module opens everything implicitly, and the JVM rejects a descriptor that
                // opens a package on top of that ("the opens table for an open module must be
                // 0 length"). Only a closed module spells its opens out.
                for (PackageDesc pkg : packageDescs) {
                    mb.opens(pkg, 0);
                }
            }
            for (String service : request.uses()) {
                mb.uses(ClassDesc.of(service));
            }
            for (var entry : services.entrySet()) {
                mb.provides(ClassDesc.of(entry.getKey()),
                        entry.getValue().stream().map(ClassDesc::of).toArray(ClassDesc[]::new));
            }
        });
        // ModulePackages, as javac writes it. Nothing here strictly needs it — every package is
        // exported, so a reader can infer the same list — but it is the authoritative one, and it
        // stays right if the export policy above ever narrows.
        return ClassFile.of().buildModule(moduleAttribute,
                clb -> clb.with(ModulePackagesAttribute.ofNames(packageDescs)));
    }

    // ---- jar reading ---------------------------------------------------------------------------

    private static Set<String> packagesOf(JarFile jar) {
        var packages = new TreeSet<String>();
        jar.stream().map(JarEntry::getName)
                .filter(name -> name.endsWith(".class") && !name.equals(MODULE_INFO))
                .filter(name -> !name.startsWith("META-INF/"))
                .forEach(name -> {
                    int slash = name.lastIndexOf('/');
                    if (slash > 0) {
                        packages.add(name.substring(0, slash).replace('/', '.'));
                    }
                });
        return packages;
    }

    /** Every package named anywhere in the jar's bytecode. */
    private static Set<String> referencedPackages(JarFile jar) throws IOException {
        var packages = new TreeSet<String>();
        for (String name : jar.stream().map(JarEntry::getName).toList()) {
            if (!name.endsWith(".class") || name.equals(MODULE_INFO)) {
                continue;
            }
            byte[] bytes;
            try (InputStream in = jar.getInputStream(jar.getJarEntry(name))) {
                bytes = in.readAllBytes();
            }
            var model = ClassFile.of().parse(bytes);
            for (var entry : model.constantPool()) {
                switch (entry) {
                    case ClassEntry classEntry -> collect(packages, classEntry.name().stringValue());
                    case Utf8Entry utf8 -> collect(packages, utf8.stringValue());
                    default -> { /* numbers, module and package entries: no type names */ }
                }
            }
        }
        return packages;
    }

    /**
     * Harvest package names from one constant-pool string. A string is taken both as a possible
     * internal name and as a possible descriptor, because the pool does not say which it is; a
     * package nothing owns is dropped later, so over-collecting is free.
     */
    private static void collect(Set<String> packages, String value) {
        var descriptors = DESCRIPTOR_TYPE.matcher(value);
        boolean matched = false;
        while (descriptors.find()) {
            matched = true;
            packageOf(descriptors.group(1)).ifPresent(packages::add);
        }
        if (!matched) {
            var internal = INTERNAL_NAME.matcher(value);
            if (internal.matches()) {
                packageOf(value).ifPresent(packages::add);
            }
        }
    }

    private static java.util.Optional<String> packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash <= 0 ? java.util.Optional.empty()
                : java.util.Optional.of(internalName.substring(0, slash).replace('/', '.'));
    }

    /** {@code META-INF/services/<service>} files, keeping only providers this jar contains. */
    private static Map<String, List<String>> servicesOf(JarFile jar, Set<String> packages, List<String> notes)
            throws IOException {
        var services = new TreeMap<String, List<String>>();
        for (String name : jar.stream().map(JarEntry::getName).toList()) {
            if (!name.startsWith(SERVICES) || name.endsWith("/")) {
                continue;
            }
            String service = name.substring(SERVICES.length());
            if (service.indexOf('/') >= 0) {
                continue; // not a service file, just something filed under that directory
            }
            String body;
            try (InputStream in = jar.getInputStream(jar.getJarEntry(name))) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var providers = new ArrayList<String>();
            for (String line : body.split("\n")) {
                int comment = line.indexOf('#');
                String provider = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (provider.isEmpty()) {
                    continue;
                }
                if (!packages.contains(packageOfBinaryName(provider))) {
                    // `provides` may only name a class of this very module; keeping the entry would
                    // make the descriptor unreadable.
                    notes.add("WARN " + name + " lists " + provider
                            + ", which this jar does not contain: left out of the module descriptor");
                    continue;
                }
                providers.add(provider);
            }
            if (!providers.isEmpty()) {
                services.put(service, List.copyOf(providers));
            }
        }
        return services;
    }

    private static String packageOfBinaryName(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    // ---- package ownership ---------------------------------------------------------------------

    /**
     * Which module owns each package: the JDK's own modules first — nothing may shadow those — then
     * the dependency closure. An explicit module owns the packages it exports; an automatic module
     * exports everything it holds.
     */
    private static Map<String, String> packageOwners(Request request, List<String> notes) {
        var owners = new LinkedHashMap<String, String>();
        for (var reference : ModuleFinder.ofSystem().findAll()) {
            record(owners, reference.descriptor());
        }
        for (Path jar : request.closure()) {
            if (jar.equals(request.jar())) {
                continue;
            }
            try {
                for (var reference : ModuleFinder.of(jar).findAll()) {
                    record(owners, reference.descriptor());
                }
            } catch (FindException e) {
                notes.add("WARN " + jar.getFileName() + " yields no module name ("
                        + e.getMessage() + "): the packages it holds cannot be required");
            }
        }
        return owners;
    }

    private static void record(Map<String, String> owners, ModuleDescriptor descriptor) {
        Set<String> owned = descriptor.isAutomatic()
                ? descriptor.packages()
                : exportedPackages(descriptor);
        for (String pkg : owned) {
            owners.putIfAbsent(pkg, descriptor.name());
        }
    }

    private static Set<String> exportedPackages(ModuleDescriptor descriptor) {
        var exported = new LinkedHashSet<String>();
        for (var export : descriptor.exports()) {
            if (!export.isQualified()) {
                exported.add(export.source());
            }
        }
        return exported;
    }
}
