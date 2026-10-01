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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModularizerTest {

    @TempDir
    Path tmp;

    private Path plainJar(String fileName, String pkg, String cls, Map<String, String> services) throws IOException {
        Path classes = tmp.resolve(fileName + "-classes");
        TestJars.compileClass(classes, pkg + "." + cls,
                "package " + pkg + "; public class " + cls + " {}");
        return TestJars.jar(tmp.resolve("m2").resolve(fileName), classes, Map.of(), services);
    }

    private static Modularizer.Options defaults() {
        return new Modularizer.Options(Modularizer.Mode.DERIVED, Set.of(), Set.of(), Map.of(), true, false);
    }

    @Test
    void synthesizeOneReturnsAnOpenDescriptorAndWritesNothing() throws IOException {
        Path jar = plainJar("acme-one-1.0.0.jar", "com.acme.one", "One", Map.of());
        Path buildDir = tmp.resolve("target");

        Optional<byte[]> moduleInfo = Modularizer.synthesizeOne(jar, List.of(jar), true, line -> { });

        assertTrue(moduleInfo.isPresent());
        ModuleDescriptor md = ModuleDescriptor.read(java.nio.ByteBuffer.wrap(moduleInfo.get()));
        assertTrue(md.isOpen());
        assertEquals("acme.one", md.name());
        assertFalse(Files.exists(ModularizedJars.root(buildDir)), "the goal's own directory is not touched");
    }

    @Test
    void synthesizeOneLeavesAnExplicitModuleAlone() throws IOException {
        Path jar = tmp.resolve("m2/acme-explicit-1.0.jar");
        Files.createDirectories(jar.getParent());
        byte[] descriptor = java.lang.classfile.ClassFile.of().buildModule(
                java.lang.classfile.attribute.ModuleAttribute.of(java.lang.constant.ModuleDesc.of("acme.explicit"),
                        mb -> mb.requires(java.lang.classfile.attribute.ModuleRequireInfo.of(
                                java.lang.constant.ModuleDesc.of("java.base"),
                                java.lang.classfile.ClassFile.ACC_MANDATED, null))));
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("module-info.class"));
            out.write(descriptor);
            out.closeEntry();
        }

        assertTrue(Modularizer.synthesizeOne(jar, List.of(jar), true, line -> { }).isEmpty());
    }

    @Test
    void patchesDerivedJarWithOpenModuleAndPromotedServices() throws IOException {
        Path jar = plainJar("acme-provider-1.0.0.jar", "com.acme.provider", "Impl",
                Map.of("META-INF/services/com.acme.provider.Impl", "com.acme.provider.Impl\n"));
        Path buildDir = tmp.resolve("target");
        List<String> log = new ArrayList<>();

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-provider"),
                buildDir, defaults(), log::add);

        Path out = ModularizedJars.root(buildDir).resolve("acme-provider-1.0.0.jar");
        assertEquals(List.of(out), result.patched());
        assertTrue(Files.isRegularFile(out));
        ModuleDescriptor md = ModuleFinder.of(out).findAll().iterator().next().descriptor();
        assertFalse(md.isAutomatic());
        assertEquals("acme.provider", md.name());
        assertTrue(md.isOpen());
        assertEquals(Set.of("com.acme.provider"),
                md.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet()));
        assertEquals(1, md.provides().size());
        assertEquals("com.acme.provider.Impl", md.provides().iterator().next().service());
        // original untouched
        assertTrue(ModuleFinder.of(jar).findAll().iterator().next().descriptor().isAutomatic());
        assertTrue(result.report().contains("acme.provider"));
    }

    @Test
    void derivedModeSkipsAutomaticNamedJars() throws IOException {
        Path classes = tmp.resolve("named-classes");
        TestJars.compileClass(classes, "org.acme.named.N", "package org.acme.named; public class N {}");
        Path jar = TestJars.jar(tmp.resolve("m2/acme-named-1.0.jar"), classes,
                Map.of("Automatic-Module-Name", "org.acme.named"), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-named"),
                buildDir, defaults(), s -> {});

        assertTrue(result.patched().isEmpty());
        assertEquals(List.of(jar), result.skipped());
    }

    @Test
    void allAutomaticModePatchesAutomaticNamedJarsKeepingTheirName() throws IOException {
        Path classes = tmp.resolve("named2-classes");
        TestJars.compileClass(classes, "org.acme.named2.N", "package org.acme.named2; public class N {}");
        Path jar = TestJars.jar(tmp.resolve("m2/acme-named2-1.0.jar"), classes,
                Map.of("Automatic-Module-Name", "org.acme.named2"), Map.of());
        Path buildDir = tmp.resolve("target");
        var opts = new Modularizer.Options(Modularizer.Mode.ALL_AUTOMATIC, Set.of(), Set.of(), Map.of(), true, false);

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-named2"), buildDir, opts, s -> {});

        assertEquals(1, result.patched().size());
        ModuleDescriptor md = ModuleFinder.of(result.patched().get(0)).findAll().iterator().next().descriptor();
        assertEquals("org.acme.named2", md.name());
        assertFalse(md.isAutomatic());
    }

    @Test
    void moduleNameOverrideWins() throws IOException {
        Path jar = plainJar("acme-thing-2.0.jar", "com.acme.thing", "T", Map.of());
        Path buildDir = tmp.resolve("target");
        var opts = new Modularizer.Options(Modularizer.Mode.DERIVED, Set.of(), Set.of(),
                Map.of("acme-thing", "com.acme.thing"), true, false);

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-thing"), buildDir, opts, s -> {});

        ModuleDescriptor md = ModuleFinder.of(result.patched().get(0)).findAll().iterator().next().descriptor();
        assertEquals("com.acme.thing", md.name());
    }

    /** A jar that already ships a hand-written {@code module-info.class}. */
    private Path explicitJar(String fileName, String moduleName, String pkg, String cls) throws IOException {
        Path classes = tmp.resolve(fileName + "-classes");
        Path srcDir = tmp.resolve(fileName + "-src");
        Files.createDirectories(srcDir.resolve(pkg.replace('.', '/')));
        Files.writeString(srcDir.resolve("module-info.java"),
                "module " + moduleName + " { exports " + pkg + "; }");
        Files.writeString(srcDir.resolve(pkg.replace('.', '/') + "/" + cls + ".java"),
                "package " + pkg + "; public class " + cls + " {}");
        Files.createDirectories(classes);
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), "--release", "25",
                srcDir.resolve("module-info.java").toString(),
                srcDir.resolve(pkg.replace('.', '/') + "/" + cls + ".java").toString());
        assertEquals(0, rc);
        return TestJars.jar(tmp.resolve("m2").resolve(fileName), classes, Map.of(), Map.of());
    }

    @Test
    void includesRestrictTheSelectionAndNeverPromoteAnExplicitJar() throws IOException {
        Path wanted = plainJar("acme-wanted-1.0.jar", "com.acme.wanted", "W", Map.of());
        Path other = plainJar("acme-other-1.0.jar", "com.acme.other", "O", Map.of());
        Path explicit = explicitJar("acme-explicit-1.0.jar", "com.acme.explicit", "com.acme.explicit", "E");
        Path buildDir = tmp.resolve("target");
        var opts = new Modularizer.Options(Modularizer.Mode.DERIVED,
                Set.of("acme-wanted", "acme-explicit"), Set.of(), Map.of(), true, false);

        Modularizer.Result result = Modularizer.run(List.of(wanted, other, explicit),
                Map.of(wanted, "acme-wanted", other, "acme-other", explicit, "acme-explicit"),
                buildDir, opts, s -> {});

        // listed AND automatic: patched. Listed but explicit: still skipped, its author wrote it.
        assertEquals(List.of(ModularizedJars.root(buildDir).resolve("acme-wanted-1.0.jar")), result.patched());
        assertEquals(List.of(other, explicit), result.skipped());
    }

    @Test
    void excludesDropTheNamedJarAndLeaveTheRest() throws IOException {
        Path a = plainJar("acme-keep-1.0.jar", "com.acme.keep", "K", Map.of());
        Path b = plainJar("acme-drop-1.0.jar", "com.acme.drop", "D", Map.of());
        Path buildDir = tmp.resolve("target");
        var opts = new Modularizer.Options(Modularizer.Mode.DERIVED, Set.of(), Set.of("acme-drop"),
                Map.of(), true, false);

        Modularizer.Result result = Modularizer.run(List.of(a, b), Map.of(a, "acme-keep", b, "acme-drop"),
                buildDir, opts, s -> {});

        assertEquals(List.of(ModularizedJars.root(buildDir).resolve("acme-keep-1.0.jar")), result.patched());
        assertEquals(List.of(b), result.skipped());
    }

    @Test
    void deletesStaleOutputsFromAPreviousRun() throws IOException {
        Path jar = plainJar("acme-fresh-1.0.jar", "com.acme.fresh", "F", Map.of());
        Path buildDir = tmp.resolve("target");
        Path stale = Files.createDirectories(ModularizedJars.root(buildDir)).resolve("old-1.0.jar");
        Files.writeString(stale, "leftover");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-fresh"),
                buildDir, defaults(), s -> {});

        assertFalse(Files.exists(stale), "a jar left by a previous run must not survive");
        assertEquals(List.of(ModularizedJars.root(buildDir).resolve("acme-fresh-1.0.jar")), result.patched());
    }

    /**
     * An automatic module may consume any service; an explicit one may not. Patching a jar that
     * calls {@code ServiceLoader.load(X.class)} without emitting {@code uses X} turns a working
     * lookup into a {@code ServiceConfigurationError} at the first call — so the generated
     * descriptor must carry the {@code uses} directives of the jar's own call sites.
     */
    @Test
    void emitsUsesForServiceLoaderCallSites() throws IOException {
        Path classes = tmp.resolve("acme-consumer-classes");
        TestJars.compileClasses(classes, Map.of(
                "com.acme.consumer.SomeSpi", "package com.acme.consumer; public interface SomeSpi {}",
                "com.acme.consumer.Consumer", """
                        package com.acme.consumer;
                        import java.util.ServiceLoader;
                        public class Consumer {
                            public SomeSpi first() {
                                return ServiceLoader.load(SomeSpi.class).findFirst().orElse(null);
                            }
                        }
                        """));
        Path jar = TestJars.jar(tmp.resolve("m2/acme-consumer-1.0.jar"), classes, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-consumer"),
                buildDir, defaults(), s -> {});

        ModuleDescriptor md = ModuleFinder.of(result.patched().get(0)).findAll().iterator().next().descriptor();
        assertTrue(md.uses().contains("com.acme.consumer.SomeSpi"),
                "generated descriptor must declare uses for its own ServiceLoader.load call sites, was " + md.uses());
    }

    /**
     * The shape that actually broke langchain4j-core: the service type never reaches
     * {@code ServiceLoader.load} as a class literal, it is handed to a helper that forwards it —
     * here through two hops, as {@code ServiceHelper.loadFactory} → {@code loadFactories} does.
     */
    @Test
    void emitsUsesForServiceTypesForwardedThroughAHelper() throws IOException {
        Path classes = tmp.resolve("acme-helper-classes");
        TestJars.compileClasses(classes, Map.of(
                "com.acme.helper.Spi", "package com.acme.helper; public interface Spi {}",
                "com.acme.helper.Helper", """
                        package com.acme.helper;
                        import java.util.ServiceLoader;
                        public class Helper {
                            static <T> ServiceLoader<T> loadAll(Class<T> type, ClassLoader cl) {
                                return ServiceLoader.load(type, cl);
                            }
                            public static <T> T first(Class<T> type) {
                                return loadAll(type, Helper.class.getClassLoader()).findFirst().orElse(null);
                            }
                        }
                        """,
                "com.acme.helper.Caller", """
                        package com.acme.helper;
                        public class Caller {
                            public Spi get() { return Helper.first(Spi.class); }
                        }
                        """));
        Path jar = TestJars.jar(tmp.resolve("m2/acme-helper-1.0.jar"), classes, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-helper"),
                buildDir, defaults(), s -> {});

        ModuleDescriptor md = ModuleFinder.of(result.patched().get(0)).findAll().iterator().next().descriptor();
        assertTrue(md.uses().contains("com.acme.helper.Spi"),
                "a service type forwarded through a helper must still be declared, was " + md.uses());
    }

    /**
     * The two-argument overload loads its ClassLoader from a second class constant, pushed after
     * the service type. Keeping only the last constant would record the utility class and drop the
     * service — exactly the lookup this whole fix exists for.
     */
    @Test
    void emitsUsesWhenAClassLoaderConstantFollowsTheServiceType() throws IOException {
        Path classes = tmp.resolve("acme-two-classes");
        TestJars.compileClasses(classes, Map.of(
                "com.acme.two.Spi", "package com.acme.two; public interface Spi {}",
                "com.acme.two.Util", "package com.acme.two; public class Util {}",
                "com.acme.two.Consumer", """
                        package com.acme.two;
                        import java.util.ServiceLoader;
                        public class Consumer {
                            public Spi first() {
                                return ServiceLoader.load(Spi.class, Util.class.getClassLoader())
                                        .findFirst().orElse(null);
                            }
                        }
                        """));
        Path jar = TestJars.jar(tmp.resolve("m2/acme-two-1.0.jar"), classes, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-two"),
                buildDir, defaults(), s -> {});

        Set<String> uses = usesOf(result.patched().get(0));
        assertTrue(uses.contains("com.acme.two.Spi"),
                "the service type must survive a trailing ClassLoader constant, was " + uses);
    }

    /** A nested service type must survive the round trip through the generated source. */
    @Test
    void emitsUsesForANestedServiceType() throws IOException {
        Path classes = tmp.resolve("acme-nested-classes");
        TestJars.compileClasses(classes, Map.of(
                "com.acme.nested.Outer", "package com.acme.nested; public class Outer { public interface Inner {} }",
                "com.acme.nested.Consumer", """
                        package com.acme.nested;
                        import java.util.ServiceLoader;
                        public class Consumer {
                            public Outer.Inner first() {
                                return ServiceLoader.load(Outer.Inner.class).findFirst().orElse(null);
                            }
                        }
                        """));
        Path jar = TestJars.jar(tmp.resolve("m2/acme-nested-1.0.jar"), classes, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-nested"),
                buildDir, defaults(), s -> {});

        Set<String> uses = usesOf(result.patched().get(0));
        assertTrue(uses.contains("com.acme.nested.Outer$Inner"),
                "a nested service type must be declared under its binary name, was " + uses);
    }

    /**
     * Cross-jar propagation when it is legal: the helper and the service type ship in the same
     * jar, and the caller reads it. Both descriptors may then declare the directive — the helper's
     * because the package is its own, the caller's because it requires the helper.
     */
    @Test
    void emitsUsesInBothJarsWhenTheLookupHelperLivesInAnotherJarOfTheClosure() throws IOException {
        Path helperClasses = tmp.resolve("acme-lib-classes");
        TestJars.compileClasses(helperClasses, Map.of(
                "com.acme.lib.LibSpi", "package com.acme.lib; public interface LibSpi {}",
                "com.acme.lib.Loader", """
                        package com.acme.lib;
                        import java.util.ServiceLoader;
                        public class Loader {
                            public static <T> T load(Class<T> type) {
                                return ServiceLoader.load(type).findFirst().orElse(null);
                            }
                        }
                        """));
        Path helperJar = TestJars.jar(tmp.resolve("m2/acme-lib-1.0.jar"), helperClasses, Map.of(), Map.of());

        Path appClasses = tmp.resolve("acme-app-classes");
        TestJars.compileClasses(appClasses, Map.of("com.acme.app.App", """
                package com.acme.app;
                import com.acme.lib.LibSpi;
                import com.acme.lib.Loader;
                public class App {
                    public LibSpi get() { return Loader.load(LibSpi.class); }
                }
                """), helperJar);
        Path appJar = TestJars.jar(tmp.resolve("m2/acme-app-1.0.jar"), appClasses, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(appJar, helperJar),
                Map.of(appJar, "acme-app", helperJar, "acme-lib"), buildDir, defaults(), s -> {});

        Path app = ModularizedJars.root(buildDir).resolve("acme-app-1.0.jar");
        Path lib = ModularizedJars.root(buildDir).resolve("acme-lib-1.0.jar");
        assertTrue(result.patched().containsAll(List.of(app, lib)), result.patched().toString());
        assertTrue(usesOf(app).contains("com.acme.lib.LibSpi"),
                "a helper in another jar of the closure must still be recognised, was " + usesOf(app));
        // The one that actually matters at run time: ServiceLoader checks its immediate caller's
        // module, and that is the helper's — com.acme.lib — not the module naming the service type.
        assertTrue(usesOf(lib).contains("com.acme.lib.LibSpi"),
                "the jar reaching ServiceLoader must declare the service, was " + usesOf(lib));
    }

    /** The {@code uses} directives of the module packaged in {@code jar}. */
    private static Set<String> usesOf(Path jar) {
        return ModuleFinder.of(jar).findAll().iterator().next().descriptor().uses();
    }

    /**
     * A `uses` on a type the resolved graph does not contain is a hard resolution error, so a
     * service whose package is in no jar of the closure is dropped — but the jar is still patched.
     */
    @Test
    void dropsAndReportsAUsesOfATypeOutsideTheClosure() throws IOException {
        Path outside = tmp.resolve("outside-classes");
        TestJars.compileClasses(outside, Map.of("org.outside.External",
                "package org.outside; public interface External {}"));
        Path classes = tmp.resolve("acme-lonely-classes");
        TestJars.compileClasses(classes, Map.of("com.acme.lonely.Consumer", """
                package com.acme.lonely;
                import java.util.ServiceLoader;
                import org.outside.External;
                public class Consumer {
                    public External first() {
                        return ServiceLoader.load(External.class).findFirst().orElse(null);
                    }
                }
                """), outside);
        // org.outside is compiled against but never packaged: it is outside the closure.
        Path jar = TestJars.jar(tmp.resolve("m2/acme-lonely-1.0.jar"), classes, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");
        List<String> log = new ArrayList<>();

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-lonely"),
                buildDir, defaults(), log::add);

        assertEquals(1, result.patched().size(), "an unresolvable service must not cost the patch");
        assertFalse(usesOf(result.patched().get(0)).contains("org.outside.External"));
        assertTrue(result.report().contains(
                "dropped uses: acme-lonely-1.0.jar — org.outside.External (not in closure)"),
                result.report());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARN dropped uses:")), log.toString());
    }

    /**
     * The langchain4j wall. A generic helper reaches {@code ServiceLoader} for a type defined in
     * the jar that calls it, so the JVM demands a {@code uses} on the helper's module — which
     * would need a {@code requires} back, and JPMS has no cycles. The helper jar has no legal
     * explicit form, so it is left automatic instead of being patched into a graph that
     * cannot resolve.
     */
    @Test
    void keepsAJarAutomaticWhenItsOwnLookupCannotBeDeclaredLegally() throws IOException {
        Path helperClasses = tmp.resolve("acme-helper2-classes");
        TestJars.compileClasses(helperClasses, Map.of("com.acme.helper2.Helper", """
                package com.acme.helper2;
                import java.util.ServiceLoader;
                public class Helper {
                    public static <T> T load(Class<T> type) {
                        return ServiceLoader.load(type).findFirst().orElse(null);
                    }
                }
                """));
        Path helperJar = TestJars.jar(tmp.resolve("m2/acme-helper2-1.0.jar"), helperClasses,
                Map.of(), Map.of());
        Path userClasses = tmp.resolve("acme-user-classes");
        TestJars.compileClasses(userClasses, Map.of(
                "com.acme.user.Spi", "package com.acme.user; public interface Spi {}",
                "com.acme.user.Caller", """
                        package com.acme.user;
                        import com.acme.helper2.Helper;
                        public class Caller {
                            public Spi get() { return Helper.load(Spi.class); }
                        }
                        """), helperJar);
        Path userJar = TestJars.jar(tmp.resolve("m2/acme-user-1.0.jar"), userClasses, Map.of(), Map.of());
        Map<Path, String> ids = Map.of(helperJar, "acme-helper2", userJar, "acme-user");
        Path buildDir = tmp.resolve("target");
        List<String> log = new ArrayList<>();

        Modularizer.Result result = Modularizer.run(List.of(helperJar, userJar), ids,
                buildDir, defaults(), log::add);

        Path patchedUser = ModularizedJars.root(buildDir).resolve("acme-user-1.0.jar");
        assertEquals(List.of(patchedUser), result.patched());
        assertTrue(result.skipped().contains(helperJar), "the helper jar must stay automatic");
        // The naming jar can declare it — the package is its own — and still does.
        assertTrue(usesOf(patchedUser).contains("com.acme.user.Spi"), usesOf(patchedUser).toString());
        assertTrue(result.report().contains("kept automatic: acme-helper2-1.0.jar"), result.report());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARN kept automatic:")), log.toString());

        // forceExplicit patches it anyway, still without the directive it cannot declare.
        Path forcedBuildDir = tmp.resolve("target-forced");
        var forced = new Modularizer.Options(Modularizer.Mode.DERIVED, Set.of(), Set.of(),
                Map.of(), true, true);
        Modularizer.Result forcedResult = Modularizer.run(List.of(helperJar, userJar), ids,
                forcedBuildDir, forced, s -> {});

        Path patchedHelper = ModularizedJars.root(forcedBuildDir).resolve("acme-helper2-1.0.jar");
        assertTrue(forcedResult.patched().contains(patchedHelper));
        assertFalse(usesOf(patchedHelper).contains("com.acme.user.Spi"),
                "an illegal directive stays dropped even under forceExplicit");
    }

    /** {@code ServiceLoader.load(variable)} must never be turned into {@code uses java.lang.Class}. */
    @Test
    void neverEmitsUsesForJavaLangClass() throws IOException {
        Path classes = tmp.resolve("acme-dyn-classes");
        TestJars.compileClasses(classes, Map.of("com.acme.dyn.Dyn", """
                package com.acme.dyn;
                import java.util.ServiceLoader;
                public class Dyn {
                    public Object first(Class<?> type) {
                        // Pushed and stored, never consumed by a call: it survives to the lookup.
                        Class<?> marker = Class.class;
                        Object found = ServiceLoader.load(type).findFirst().orElse(null);
                        return found == null ? marker : found;
                    }
                }
                """));
        Path jar = TestJars.jar(tmp.resolve("m2/acme-dyn-1.0.jar"), classes, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");

        Modularizer.Result result = Modularizer.run(List.of(jar), Map.of(jar, "acme-dyn"),
                buildDir, defaults(), s -> {});

        Set<String> uses = usesOf(result.patched().get(0));
        assertFalse(uses.contains("java.lang.Class"), uses.toString());
        assertFalse(uses.contains("java.lang.Object"), uses.toString());
        assertTrue(result.report().contains("(not a class literal)"), result.report());
    }

    /**
     * A class constant consumed by an earlier call must not drift into a later lookup. Before the
     * consumption rule, {@code LoggerFactory.getLogger(Service.class)} at the top of a method was
     * recorded as a service of that method's {@code ServiceLoader.load} — an invented directive
     * that, once legality is enforced, can cost the jar its explicit form for nothing.
     */
    @Test
    void ignoresAClassConstantConsumedByAnEarlierCall() throws IOException {
        Path logClasses = tmp.resolve("acme-log-classes");
        TestJars.compileClasses(logClasses, Map.of("com.acme.log.LoggerFactory", """
                package com.acme.log;
                public class LoggerFactory {
                    public static String getLogger(Class<?> owner) { return owner.getName(); }
                }
                """));
        Path logJar = TestJars.jar(tmp.resolve("m2/acme-log-1.0.jar"), logClasses, Map.of(), Map.of());

        Path svcClasses = tmp.resolve("acme-svc-classes");
        TestJars.compileClasses(svcClasses, Map.of(
                "com.acme.svc.Spi", "package com.acme.svc; public interface Spi {}",
                "com.acme.svc.Service", """
                        package com.acme.svc;
                        import com.acme.log.LoggerFactory;
                        import java.util.ServiceLoader;
                        public class Service {
                            private static final String LOG = LoggerFactory.getLogger(Service.class);
                            public Spi first() {
                                LoggerFactory.getLogger(Service.class);
                                return ServiceLoader.load(Spi.class).findFirst().orElse(null);
                            }
                            public String log() { return LOG; }
                        }
                        """), logJar);
        Path svcJar = TestJars.jar(tmp.resolve("m2/acme-svc-1.0.jar"), svcClasses, Map.of(), Map.of());
        Path buildDir = tmp.resolve("target");
        List<String> log = new ArrayList<>();

        Modularizer.Result result = Modularizer.run(List.of(svcJar, logJar),
                Map.of(svcJar, "acme-svc", logJar, "acme-log"), buildDir, defaults(), log::add);

        Path svc = ModularizedJars.root(buildDir).resolve("acme-svc-1.0.jar");
        assertEquals(Set.of("com.acme.svc.Spi"), usesOf(svc),
                "only the service type may be declared, not the logger's argument");
        // Nothing invented means nothing to drop, and so no reason to demote either jar.
        assertEquals(2, result.patched().size(), result.report());
        assertFalse(result.report().contains("kept automatic"), result.report());
        // The synthesis reports its own notes through the same log, so only our warnings count.
        assertTrue(log.stream().noneMatch(l -> l.startsWith("WARN dropped uses:")
                || l.startsWith("WARN kept automatic:")), log.toString());
    }

    @Test
    void failsOnSplitPackageAcrossTwoAutomaticJars() throws IOException {
        Path a = plainJar("acme-a-1.0.jar", "com.acme.shared", "A", Map.of());
        Path b = plainJar("acme-b-1.0.jar", "com.acme.shared", "B", Map.of());
        Path buildDir = tmp.resolve("target");

        var ex = assertThrows(IllegalStateException.class, () ->
                Modularizer.run(List.of(a, b), Map.of(a, "acme-a", b, "acme-b"), buildDir, defaults(), s -> {}));

        assertTrue(ex.getMessage().contains("com.acme.shared"));
        assertTrue(ex.getMessage().contains("acme-a-1.0.jar"));
        assertTrue(ex.getMessage().contains("acme-b-1.0.jar"));
        // <excludes> cannot silence this guard: it covers the whole automatic closure.
        assertTrue(ex.getMessage().contains("Remove one side from the dependency graph (Maven <exclusions>)"),
                ex.getMessage());
    }
}
