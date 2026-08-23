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
package io.vidocq.vauban.core.weaving;

import io.vidocq.vauban.weaver.BeanWeavingAnalysis;
import io.vidocq.vauban.weaver.ProxyLinkWeaver;
import io.vidocq.vauban.weaver.WeavingAgent;
import io.vidocq.vauban.weaver.WeavingPlan;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Load-time weaving tier of Vidocq/vauban#24: when the build output was <em>not</em> woven
 * — typically an IDE build, whose compiler flushes classes to disk after javac finishes and
 * thereby defeats the auto-started javac plugin — the runtime detects it before application
 * classes are loaded and attaches the {@code vauban-weaver} instrumentation agent to the
 * current JVM, so the very same Class-File transformations are applied at class definition
 * instead. Production builds (Maven, Gradle, plain javac) weave at compile time and never
 * enter this path.
 *
 * <p>Detection is driven exclusively by the {@code META-INF/vauban-beans.list} resources
 * written by the Vauban annotation processor — the tier only ever considers classes an
 * APT-built application declared as beans. Synthetic deployments (tests, TCK archives)
 * carry no such list and keep the spec-mandated unproxyable-bean deployment errors.
 *
 * <p>Everything here is <strong>load-free</strong> on purpose: candidate inspection reads
 * {@code .class} resource bytes through the class loader, because a class that gets loaded
 * before the agent is attached can no longer be woven (adding a constructor is not a valid
 * retransformation). This is also why the hook runs twice: early in the Vidocq bootstrap,
 * before any extension can touch application classes, and again inside
 * {@code VaubanContainerBuilder.build()} as a backstop for plain Vauban SE usage — the
 * already-planned registry makes the second call a no-op.
 *
 * <p>The JVM refuses to attach to itself by default, so the attach is performed by a
 * short-lived child process running {@code io.vidocq.vauban.weaver.AttachBack} from the
 * agent jar (embedded in this artifact, extracted to a temporary file). The JDK prints its
 * standard dynamic-agent warning; disable this tier with
 * {@code -Dvauban.weaving.loadtime=disabled} (the unwoven beans then fail deployment
 * validation with the regular vauban#24 diagnostic).
 */
public final class LoadTimeWeaving {

    /** Set to {@code disabled} to opt out of load-time weaving. */
    public static final String MODE_PROPERTY = "vauban.weaving.loadtime";

    private static final System.Logger LOG = System.getLogger(LoadTimeWeaving.class.getName());

    private static final String BEANS_LIST_RESOURCE = "META-INF/vauban-beans.list";
    private static final String EMBEDDED_AGENT = "/io/vidocq/vauban/core/weaving/vauban-weaver-agent.jar";

    /** Classes already handed to the agent in this JVM — repeated boots must not re-attach. */
    private static final Set<String> plannedSoFar = new LinkedHashSet<>();

    /**
     * @param planned binary names of bean classes the agent weaves at load time in this
     *                JVM (deployment validation must not flag them as unproxyable)
     * @param failure human-readable reason when weaving was needed but could not be
     *                installed, {@code null} otherwise
     */
    public record Result(Set<String> planned, String failure) {
        public static final Result NONE = new Result(Set.of(), null);

        public Result {
            planned = Set.copyOf(planned);
        }
    }

    private LoadTimeWeaving() {}

    /**
     * Detects unwoven normal-scoped beans among the {@code META-INF/vauban-beans.list}
     * declarations visible from {@code loader} and, if any, installs the load-time weaving
     * agent. Must run before those classes are loaded. Idempotent per JVM and per class.
     */
    public static synchronized Result prepare(ClassLoader loader) {
        if ("disabled".equalsIgnoreCase(System.getProperty(MODE_PROPERTY, ""))) {
            return Result.NONE;
        }
        try {
            return doPrepare(loader);
        } catch (LinkageError weaverAbsent) {
            // A hand-crafted class/module path without vauban-weaver must not kill the
            // boot: unwoven beans then simply fail deployment validation as before.
            return new Result(Set.of(), "Load-time weaving unavailable — vauban-weaver is "
                    + "not on the class/module path (" + weaverAbsent + ")");
        }
    }

    private static Result doPrepare(ClassLoader loader) {
        Set<String> declared;
        try {
            declared = readBeanLists(loader);
        } catch (IOException e) {
            return new Result(Set.of(), "Cannot read " + BEANS_LIST_RESOURCE + ": " + e);
        }
        if (declared.isEmpty()) {
            return Result.NONE;
        }

        BeanWeavingAnalysis.ByteResolver resolver = name -> classBytes(loader, name);
        var scopeCache = new HashMap<String, Boolean>();
        var candidates = new LinkedHashSet<String>();
        var bytesByName = new HashMap<String, byte[]>();
        for (var name : declared) {
            if (name.endsWith("_ClientProxy") || name.contains("$$")) continue;
            var bytes = classBytes(loader, name);
            if (bytes == null) continue;
            try {
                if (BeanWeavingAnalysis.isNormalScopedBeanClass(bytes, resolver, scopeCache)) {
                    candidates.add(name);
                    bytesByName.put(name, bytes);
                }
            } catch (IllegalArgumentException unparseable) {
                // e.g. an encrypted sjar entry — resource bytes are not class-file bytes;
                // such classes are handled by their defining loader plugin, not here
            }
        }

        var beans = new LinkedHashMap<String, ProxyLinkWeaver.SuperChain>();
        for (var name : candidates) {
            var bytes = bytesByName.get(name);
            try {
                if (!BeanWeavingAnalysis.needsMarker(bytes)) {
                    continue;
                }
                var chain = BeanWeavingAnalysis.superChain(bytes,
                        n -> bytesByName.containsKey(n) ? bytesByName.get(n) : resolver.bytesOf(n),
                        candidates, beans);
                if (chain == null) {
                    LOG.log(System.Logger.Level.DEBUG, () -> "Load-time weaving cannot chain "
                            + name + " to its superclass — leaving it to deployment validation");
                    continue;
                }
                beans.put(name, chain);
            } catch (IllegalArgumentException unparseable) {
                // unparseable superclass bytes (encrypted sjar entry) — cannot weave safely
            }
        }
        if (beans.isEmpty()) {
            return Result.NONE;
        }

        // Universal-loader mode: when a VaubanClassLoader defines the application classes,
        // the cdi-proxifier transformer weaves them at definition — no agent needed. The
        // planned set is still reported so deployment validation stands down.
        for (var l = loader; l != null; l = l.getParent()) {
            if (l instanceof io.vidocq.vauban.classloader.VaubanClassLoader) {
                LOG.log(System.Logger.Level.INFO, () -> "Unwoven normal-scoped bean(s) "
                        + beans.keySet() + " will be woven by the Vauban class loader");
                return new Result(beans.keySet(), null);
            }
        }

        var newBeans = new LinkedHashMap<String, ProxyLinkWeaver.SuperChain>();
        beans.forEach((name, chain) -> {
            if (!plannedSoFar.contains(name)) newBeans.put(name, chain);
        });
        if (newBeans.isEmpty()) {
            // Everything was planned by an earlier call in this JVM (e.g. the Vidocq
            // bootstrap ran before this container-builder backstop).
            return new Result(beans.keySet(), null);
        }

        var proxies = new LinkedHashSet<String>();
        for (var bean : newBeans.keySet()) {
            proxies.add(bean + "_ClientProxy");
        }
        var plan = new WeavingPlan(newBeans, proxies);
        LOG.log(System.Logger.Level.WARNING, () -> "Build output is not woven for "
                + newBeans.size() + " normal-scoped bean(s) " + newBeans.keySet()
                + " — attaching the Vauban load-time weaving agent (typical of IDE builds;"
                + " Maven/Gradle/javac builds weave at compile time)."
                + " Disable with -D" + MODE_PROPERTY + "=disabled."
                + " Hint: a trampoline main that re-layers the application"
                + " (Vidocq: @VidocqMain + Vidocq.run) avoids the agent entirely");
        try {
            attach(plan);
        } catch (Exception e) {
            return new Result(Set.of(), "Load-time weaving could not be installed ("
                    + e.getMessage() + "). Either build with Maven/Gradle (build-time weaving),"
                    + " run with -javaagent:vauban-weaver.jar=<plan>, or add the (ProxyLink)"
                    + " constructors manually.");
        }
        plannedSoFar.addAll(newBeans.keySet());
        return new Result(beans.keySet(), null);
    }

    private static Set<String> readBeanLists(ClassLoader loader) throws IOException {
        var names = new LinkedHashSet<String>();
        var resources = loader.getResources(BEANS_LIST_RESOURCE);
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            try (var reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.strip();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        names.add(line);
                    }
                }
            }
        }
        return names;
    }

    private static byte[] classBytes(ClassLoader loader, String binaryName) {
        var resource = binaryName.replace('.', '/') + ".class";
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static void attach(WeavingPlan plan) throws Exception {
        var planFile = Files.createTempFile("vauban-weave-plan-", ".txt");
        plan.writeTo(planFile);
        var agentJar = agentJar();

        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pid = String.valueOf(ProcessHandle.current().pid());
        var child = new ProcessBuilder(List.of(javaBin, "-cp", agentJar.toString(),
                "io.vidocq.vauban.weaver.AttachBack", pid, agentJar.toString(), planFile.toString()))
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes());
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new IllegalStateException("attach helper timed out");
        }
        if (child.exitValue() != 0) {
            throw new IllegalStateException("attach helper failed (exit " + child.exitValue()
                    + "): " + output.strip());
        }
        if (!"true".equals(System.getProperty(WeavingAgent.ATTACHED_PROPERTY))) {
            throw new IllegalStateException("agent handshake missing after attach: " + output.strip());
        }
    }

    /**
     * The agent jar: the copy embedded in vauban-core (extracted to a temp file), or — when
     * running from exploded build output where the embedded resource may be absent — the
     * vauban-weaver jar found on the module path or class path.
     */
    private static Path agentJar() throws IOException {
        try (InputStream in = LoadTimeWeaving.class.getResourceAsStream(EMBEDDED_AGENT)) {
            if (in != null) {
                var jar = Files.createTempFile("vauban-weaver-agent-", ".jar");
                Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
                return jar;
            }
        }
        for (var pathProperty : List.of("jdk.module.path", "java.class.path")) {
            var paths = System.getProperty(pathProperty, "");
            for (var entry : paths.split(File.pathSeparator)) {
                if (entry.isBlank()) continue;
                var file = Path.of(entry);
                var fileName = String.valueOf(file.getFileName());
                if (fileName.startsWith("vauban-weaver") && fileName.endsWith(".jar")
                        && Files.isRegularFile(file)) {
                    return file;
                }
            }
        }
        throw new IOException("vauban-weaver agent jar not found (no embedded copy, none on "
                + "jdk.module.path/java.class.path)");
    }
}
