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
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Works out which jar of a dependency closure must declare which {@code uses} directives, by
 * locating the {@code ServiceLoader} lookups in the bytecode with the JDK Class-File API
 * (JEP 484).
 *
 * <p>Needed because a service lookup leaves no trace a dependency analysis can pick up: the type
 * appears as an ordinary class constant, and nothing in the bytecode says it is a service.
 *
 * <h2>Which module needs the directive</h2>
 *
 * {@code ServiceLoader.load} checks {@code Reflection.getCallerClass()} — the module that must
 * declare {@code uses} is the one holding the class that <em>calls</em> {@code ServiceLoader},
 * not the one that names the service. For the direct shape,
 * {@code ServiceLoader.load(Foo.class)}, they are the same class. For the indirect shape every
 * library eventually grows — a helper taking the service type as a {@code Class} parameter and
 * forwarding it — they are not, and may not even be the same jar: langchain4j names
 * {@code AiServiceContextFactory} in {@code langchain4j} but reaches {@code ServiceLoader}
 * through {@code ServiceHelper} in {@code langchain4j-core}, and it is
 * {@code langchain4j-core} that fails without the directive.
 *
 * <p>So the scanner works over the whole closure at once: it finds the forwarding helpers by
 * fixed point over the closure's call graph (any depth of forwarding), then attributes each
 * service both to the jar that names it and to every jar on the forwarding chain down to
 * {@code ServiceLoader}.
 *
 * <h2>Deliberate over-approximation</h2>
 *
 * Every class constant pushed since the previous lookup is taken to be a candidate service type,
 * which can pick up unrelated constants. That is the right way to be wrong here — a superfluous
 * {@code uses} is inert (nothing ever resolves it), a missing one turns the jar's own lookup into
 * a {@code ServiceConfigurationError}. A service type held in a variable rather than written as a
 * class literal cannot be seen at all; no bytecode scanner can.
 */
final class ServiceUsesScanner {

    /** Owner of the lookups we seed the call graph with, in internal form. */
    private static final String SERVICE_LOADER = "java/util/ServiceLoader";

    /** The parameter type that makes a method a candidate for forwarding a service type. */
    private static final ClassDesc CLASS = ClassDesc.of("java.lang.Class");

    /** Same type, internal form, for the receiver test in {@link #consume}. */
    private static final String CLASS_INTERNAL = "java/lang/Class";

    /** Identity of a method across the closure: owner, name and descriptor. */
    private record MethodKey(String owner, String name, String descriptor) {}

    /** Service binary names per jar, ready to be turned into {@code uses} directives. */
    private final Map<Path, Set<String>> servicesByJar;

    private ServiceUsesScanner(Map<Path, Set<String>> servicesByJar) {
        this.servicesByJar = servicesByJar;
    }

    /**
     * Analyses {@code closure} and returns a scanner able to answer for each of its jars.
     *
     * @param closure every jar of the dependency closure — a helper is regularly in another jar
     *                than the code naming the service, so a per-jar analysis sees neither whole
     * @param log     receives one debug line per jar whose classes could not all be read, so an
     *                incomplete scan leaves a trace instead of only a missing {@code uses}
     * @throws IOException if a jar cannot be opened
     */
    static ServiceUsesScanner over(Iterable<Path> closure, Consumer<String> log) throws IOException {
        // Highest failure count seen for a jar over the two reading passes — they read the same
        // entries, so adding them up would double-count the same unreadable classes.
        Map<Path, Integer> unreadable = new LinkedHashMap<>();
        // Pass 1: the call graph, restricted to the methods that could forward a service type.
        Map<MethodKey, Set<MethodKey>> callees = new HashMap<>();
        // Known limitation: a class present in several jars of the closure (a shaded or duplicated
        // copy) resolves to the last jar iterated. Only the attribution of a forwarded service is
        // affected, and the split-package guard already rejects the case that matters — two
        // automatic jars sharing a package.
        Map<String, Path> jarOfClass = new HashMap<>();
        for (Path jar : closure) {
            record(unreadable, jar, forEachClass(jar, model -> {
                String owner = model.thisClass().asInternalName();
                jarOfClass.put(owner, jar);
                for (MethodModel method : model.methods()) {
                    MethodTypeDesc type = method.methodTypeSymbol();
                    // Without a Class parameter a method has no service type to forward; whatever
                    // it looks up is already caught where the class constant is written.
                    if (!type.parameterList().contains(CLASS)) {
                        continue;
                    }
                    Set<MethodKey> invoked = new HashSet<>();
                    eachInstruction(method, element -> {
                        if (element instanceof InvokeInstruction invoke) {
                            invoked.add(keyOf(invoke));
                        }
                    });
                    callees.put(new MethodKey(owner, method.methodName().stringValue(),
                            type.descriptorString()), invoked);
                }
            }));
        }

        // Pass 2: fixed point — a helper calling a helper is the common case
        // (ServiceHelper.loadFactory → loadFactories → loadFactories(Class, ClassLoader)).
        Set<MethodKey> lookups = new HashSet<>();
        boolean growing = true;
        while (growing) {
            growing = false;
            for (Map.Entry<MethodKey, Set<MethodKey>> entry : callees.entrySet()) {
                if (lookups.contains(entry.getKey())) {
                    continue;
                }
                for (MethodKey callee : entry.getValue()) {
                    if (isServiceLoaderLookup(callee) || lookups.contains(callee)) {
                        lookups.add(entry.getKey());
                        growing = true;
                        break;
                    }
                }
            }
        }

        // Pass 3: harvest the class constants and spread each service along its forwarding chain.
        Map<Path, Set<String>> servicesByJar = new HashMap<>();
        Map<MethodKey, Set<String>> chainCache = new HashMap<>();
        for (Path jar : closure) {
            record(unreadable, jar, forEachClass(jar, model -> harvest(model, jar, callees, lookups,
                    jarOfClass, chainCache, servicesByJar)));
        }
        // Reported once per jar, after both passes: a class the scanner cannot read is a service
        // lookup it cannot see, so a jar whose descriptor is generated from a partial scan may be
        // missing a `uses` — worth a trace when one of its lookups later fails at runtime.
        for (Map.Entry<Path, Integer> e : unreadable.entrySet()) {
            log.accept("DEBUG " + e.getValue() + " class file(s) of " + e.getKey().getFileName()
                    + " could not be parsed — uses scan may be incomplete");
        }
        return new ServiceUsesScanner(servicesByJar);
    }

    /** Keeps the worst count seen for {@code jar}; the passes read the same entries. */
    private static void record(Map<Path, Integer> unreadable, Path jar, int failures) {
        if (failures > 0) {
            unreadable.merge(jar, failures, Math::max);
        }
    }

    /**
     * The services {@code jar} must declare.
     *
     * @return their binary names, sorted so two runs on the same closure generate byte-identical
     *         descriptors
     */
    Set<String> scan(Path jar) {
        return new TreeSet<>(servicesByJar.getOrDefault(jar, Set.of()));
    }

    /** Harvests the lookups of one class and records them against every jar that needs them. */
    private static void harvest(ClassModel model, Path jar, Map<MethodKey, Set<MethodKey>> callees,
                                Set<MethodKey> lookups, Map<String, Path> jarOfClass,
                                Map<MethodKey, Set<String>> chainCache,
                                Map<Path, Set<String>> servicesByJar) {
        for (MethodModel method : model.methods()) {
            // `ServiceLoader.load(Foo.class)` compiles to `ldc Foo.class` then `invokestatic`,
            // with any ClassLoader or ModuleLayer argument loaded around it — and that argument is
            // itself regularly a class constant: `ServiceLoader.load(Foo.class,
            // Util.class.getClassLoader())` pushes two. So the candidates are kept as a stack of
            // every class constant pushed since the previous lookup, and an intervening call
            // removes the ones it consumes (see `consume`). Branches are not modelled: a candidate
            // pushed on one arm can reach a lookup on another. Erring that way is deliberate — a
            // superfluous directive is dropped by UsesLegality when it is not legal, a missing one
            // is a ServiceConfigurationError.
            Deque<ClassDesc> pending = new ArrayDeque<>();
            eachInstruction(method, element -> {
                if (element instanceof ConstantInstruction ci && ci.constantValue() instanceof ClassDesc cd) {
                    pending.addLast(cd);
                } else if (element instanceof InvokeInstruction invoke) {
                    MethodKey key = keyOf(invoke);
                    boolean direct = isServiceLoaderLookup(key);
                    if (!direct && !lookups.contains(key)) {
                        consume(pending, invoke);
                        return;
                    }
                    for (ClassDesc candidate : pending) {
                        if (!candidate.isClassOrInterface()) {
                            continue;
                        }
                        String service = binaryNameOf(candidate);
                        // The naming jar: correct on its own for the direct shape, and harmless
                        // for the indirect one.
                        servicesByJar.computeIfAbsent(jar, j -> new HashSet<>()).add(service);
                        // The forwarding chain: those are the modules ServiceLoader will see as
                        // the caller, so those are the ones that would fail without the directive.
                        if (!direct) {
                            for (String owner : chainOwners(key, callees, lookups, chainCache)) {
                                Path helperJar = jarOfClass.get(owner);
                                if (helperJar != null) {
                                    servicesByJar.computeIfAbsent(helperJar, j -> new HashSet<>()).add(service);
                                }
                            }
                        }
                    }
                    // Reset per lookup: a constant pushed after this call belongs to the next one.
                    pending.clear();
                } else if (element instanceof ReturnInstruction || element instanceof ThrowInstruction) {
                    pending.clear();
                }
            });
        }
    }

    /**
     * Removes the candidates {@code invoke} consumes: one per {@code Class}-typed parameter, plus
     * the receiver when the call is an instance method on {@code java.lang.Class}.
     *
     * <p>This is what keeps an unrelated constant from drifting into a later lookup —
     * {@code LoggerFactory.getLogger(Service.class)} earlier in the method eats its own argument
     * rather than being recorded as a service. It is also why a call that consumes nothing, such
     * as {@code Thread.currentThread()} in
     * {@code ServiceLoader.load(Foo.class, Thread.currentThread().getContextClassLoader())}, is
     * allowed to leave the candidates alone: clearing on every intervening call would lose
     * {@code Foo}, which is the whole point of the scan.
     */
    private static void consume(Deque<ClassDesc> pending, InvokeInstruction invoke) {
        int consumed = 0;
        for (ClassDesc parameter : invoke.typeSymbol().parameterList()) {
            if (CLASS.equals(parameter)) {
                consumed++;
            }
        }
        // `Util.class.getClassLoader()` / `.getModule()`: the receiver is the constant just pushed.
        if (invoke.opcode() != Opcode.INVOKESTATIC && CLASS_INTERNAL.equals(invoke.owner().asInternalName())) {
            consumed++;
        }
        while (consumed-- > 0 && !pending.isEmpty()) {
            pending.pollLast();
        }
    }

    /**
     * The classes on the forwarding chain from {@code entry} down to {@code ServiceLoader}: every
     * one of them may be the caller {@code ServiceLoader} sees.
     */
    private static Set<String> chainOwners(MethodKey entry, Map<MethodKey, Set<MethodKey>> callees,
                                           Set<MethodKey> lookups, Map<MethodKey, Set<String>> cache) {
        Set<String> cached = cache.get(entry);
        if (cached != null) {
            return cached;
        }
        Set<String> owners = new HashSet<>();
        Set<MethodKey> seen = new HashSet<>();
        Deque<MethodKey> queue = new ArrayDeque<>();
        queue.add(entry);
        seen.add(entry);
        while (!queue.isEmpty()) {
            MethodKey current = queue.poll();
            owners.add(current.owner());
            for (MethodKey callee : callees.getOrDefault(current, Set.of())) {
                if (lookups.contains(callee) && seen.add(callee)) {
                    queue.add(callee);
                }
            }
        }
        cache.put(entry, owners);
        return owners;
    }

    /** Whether {@code key} is one of the static {@code ServiceLoader} lookup factories. */
    private static boolean isServiceLoaderLookup(MethodKey key) {
        return SERVICE_LOADER.equals(key.owner())
                && ("load".equals(key.name()) || "loadInstalled".equals(key.name()));
    }

    private static MethodKey keyOf(InvokeInstruction invoke) {
        return new MethodKey(invoke.owner().asInternalName(), invoke.name().stringValue(),
                invoke.type().stringValue());
    }

    /** Feeds every instruction of {@code method}'s body to {@code visitor}, if it has one. */
    private static void eachInstruction(MethodModel method, Consumer<CodeElement> visitor) {
        Optional<CodeModel> code = method.code();
        if (code.isPresent()) {
            for (CodeElement element : code.get()) {
                visitor.accept(element);
            }
        }
    }

    /**
     * Parses every class of {@code jar} and hands it to {@code visitor}. A class that cannot be
     * parsed is skipped rather than fatal: a missing {@code uses} costs one runtime code path,
     * an aborted build costs the whole modularization.
     *
     * @return how many classes were skipped — silence would make an incomplete scan look complete
     */
    private static int forEachClass(Path jar, Consumer<ClassModel> visitor) throws IOException {
        int failures = 0;
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry entry : jf.stream().toList()) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")) {
                    continue;
                }
                ClassModel model;
                try (InputStream in = jf.getInputStream(entry)) {
                    model = ClassFile.of().parse(in.readAllBytes());
                } catch (RuntimeException | IOException e) {
                    failures++;
                    continue;
                }
                try {
                    visitor.accept(model);
                } catch (RuntimeException e) {
                    // Same reasoning: one unreadable member must not cost the jar its descriptor.
                    failures++;
                }
            }
        }
        return failures;
    }

    /** {@code Lcom/acme/Foo;} → {@code com.acme.Foo} (nested types keep their {@code $}). */
    private static String binaryNameOf(ClassDesc desc) {
        String d = desc.descriptorString();
        return d.substring(1, d.length() - 1).replace('/', '.');
    }
}
