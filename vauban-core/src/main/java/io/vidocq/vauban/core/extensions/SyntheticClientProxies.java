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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.loading.DeploymentClasses;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The client proxies a build generates for the synthetic beans of a normal scope its frozen extensions registered,
 * when the bean class is a class (vauban#342). No processor sees a synthetic bean, so the container generated such a
 * proxy at every start; the build that freezes the extensions knows the bean class and its scope, and writes the class
 * the start would have generated, byte for byte.
 *
 * <p>The proxy lives in the package of the bean class. For a bean class the build writes, its class file goes next to
 * that class. For a class of another archive, a jar of a library, it goes under {@value #PLACED_PREFIX} and the class
 * is listed in {@value #PLACEMENT_MANIFEST}: a Vauban layer defines it into the package of the bean class, as it does
 * the placed proxy of a produced type. Without such a layer nothing loads it, and the start generates the proxy as
 * before.
 *
 * <p>A bean class that is an interface needs no particular package: its proxy implements public interfaces. It goes
 * to a package of the output that holds a generated {@code _VaubanComponents}, whose lookup lets the start
 * instantiate it with no {@code opens}, and the record names it, as no naming rule leads the start there. An output
 * without such a package gets none: the start builds the {@code java.lang.reflect.Proxy} it built before, unless
 * {@code @ClientProxies} lists the interface.
 *
 * @param files      the files to write, by their path under the output of the build
 * @param placed     the bean classes whose proxy is placed, by binary name
 * @param interfaces the proxy class of each bean class that is an interface, by binary names
 */
public record SyntheticClientProxies(Map<String, byte[]> files, SortedSet<String> placed,
        SortedMap<String, String> interfaces) {

    /** The key of the frozen synthetic metadata that names the proxy class of an interface: this, then its name. */
    public static final String INTERFACE_KEY = "clientProxy.";
    private static final String PROVIDER_FILE = "_VaubanComponents.class";

    /** Where the proxy of a class of another archive ships: {@code VaubanClassLoader.PLACED_PREFIX}. */
    public static final String PLACED_PREFIX = "META-INF/vauban/placed/";
    /** The classes that have a placed proxy: {@code VaubanClassLoader.PLACEMENT_MANIFEST}. */
    public static final String PLACEMENT_MANIFEST = "META-INF/vauban/required-opens.list";

    private static final String CLASS_FILE = ".class";

    public SyntheticClientProxies {
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        placed = Collections.unmodifiableSortedSet(new TreeSet<>(placed));
        interfaces = Collections.unmodifiableSortedMap(new TreeMap<>(interfaces));
    }

    /** No proxy. */
    public static SyntheticClientProxies none() {
        return new SyntheticClientProxies(Map.of(), new TreeSet<>(), new TreeMap<>());
    }

    /**
     * The client proxies of {@code beans}: one for each normal-scoped bean of the deployment that has no proxy
     * yet. A class no proxy can be generated for gets none: the start says why.
     *
     * @param output where the build writes the classes of the application
     */
    public static SyntheticClientProxies of(Collection<VaubanSyntheticBeanBuilder<?>> beans, Path output) {
        Predicate<String> compiled =
                className -> Files.isRegularFile(output.resolve(className.replace('.', '/') + CLASS_FILE));
        var files = new LinkedHashMap<String, byte[]>();
        var placed = new TreeSet<String>();
        var interfaces = new TreeMap<String, String>();
        String host = null;
        for (var bean : beans) {
            Class<?> beanClass = bean.getBeanClass();
            if (!proxied(bean, beanClass)) {
                continue;
            }
            if (beanClass.isInterface()) {
                host = host != null ? host : providerPackage(output);
                interfaceProxy(bean, beanClass, host, files, interfaces);
                continue;
            }
            if (hasProxy(beanClass)) {
                continue;
            }
            RuntimeClientProxyGenerator.GeneratedProxy generated;
            try {
                generated = RuntimeClientProxyGenerator.generate(beanClass);
            } catch (RuntimeException | LinkageError _) {
                continue;
            }
            String path = generated.className().replace('.', '/') + CLASS_FILE;
            if (compiled.test(beanClass.getName())) {
                files.put(path, generated.bytecode());
            } else {
                files.put(PLACED_PREFIX + path, generated.bytecode());
                placed.add(beanClass.getName());
            }
        }
        return new SyntheticClientProxies(files, placed, interfaces);
    }

    /** Marks an output without a package to host the proxy of an interface. */
    private static final String NO_HOST = "-";

    /**
     * The first package of {@code output}, by name, that holds a generated component provider, as a path with its
     * trailing slash; {@link #NO_HOST} when none does.
     */
    private static String providerPackage(Path output) {
        if (!Files.isDirectory(output)) {
            return NO_HOST;
        }
        try (var walked = Files.walk(output)) {
            return walked.filter(file -> file.getFileName() != null
                            && PROVIDER_FILE.equals(file.getFileName().toString()))
                    .map(file -> output.relativize(file.getParent()).toString().replace('\\', '/'))
                    .filter(directory -> !directory.isEmpty() && !directory.startsWith("META-INF"))
                    .sorted().findFirst().map(directory -> directory + "/").orElse(NO_HOST);
        } catch (IOException _) {
            return NO_HOST;
        }
    }

    /**
     * Adds the proxy of the interface {@code beanClass} to {@code files} and {@code interfaces}, in the package
     * {@code host}; none for an interface that is not public.
     */
    private static void interfaceProxy(VaubanSyntheticBeanBuilder<?> bean, Class<?> beanClass, String host,
            Map<String, byte[]> files, Map<String, String> interfaces) {
        if (NO_HOST.equals(host) || interfaces.containsKey(beanClass.getName())) {
            return;
        }
        String name = io.vidocq.vauban.core.proxy.ClientProxyShape.producerProxyName(
                host.substring(0, host.length() - 1).replace('/', '.'), beanClass.getName());
        try {
            var generated = RuntimeClientProxyGenerator.generateInterfaceProxyAt(beanClass, bean.getTypes(), name);
            files.put(name.replace('.', '/') + CLASS_FILE, generated.bytecode());
            interfaces.put(beanClass.getName(), name);
        } catch (RuntimeException | LinkageError _) {
            // No proxy for this one: the start builds its own
        }
    }

    /** Whether {@code bean} is reached through a client proxy of {@code beanClass}. */
    private static boolean proxied(VaubanSyntheticBeanBuilder<?> bean, Class<?> beanClass) {
        if (beanClass == null || beanClass.isArray() || beanClass.isPrimitive()
                || beanClass.getClassLoader() == null || bean.getScopeAnnotation() == null) {
            return false;
        }
        var scope = BceProcessor.scopeOf(bean.getScopeAnnotation());
        return scope != null && scope.isNormal();
    }

    /** Whether the proxy of {@code beanClass} exists already: the processor or the build generated it. */
    private static boolean hasProxy(Class<?> beanClass) {
        try {
            DeploymentClasses.load(RuntimeClientProxyGenerator.proxyClassName(beanClass), beanClass.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError _) {
            return false;
        }
    }

    /**
     * Writes the proxies under {@code output}, and adds the classes of the placed ones to the placement manifest,
     * keeping the lines another build step wrote there.
     */
    public void write(Path output) throws IOException {
        for (var file : files.entrySet()) {
            Path target = output.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        if (placed.isEmpty()) {
            return;
        }
        Path manifest = output.resolve(PLACEMENT_MANIFEST);
        var listed = new TreeSet<String>(placed);
        var lines = new ArrayList<String>();
        if (Files.exists(manifest)) {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                lines.add(line);
                listed.remove(line.strip());
            }
        }
        lines.addAll(listed);
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, lines, StandardCharsets.UTF_8);
    }
}
