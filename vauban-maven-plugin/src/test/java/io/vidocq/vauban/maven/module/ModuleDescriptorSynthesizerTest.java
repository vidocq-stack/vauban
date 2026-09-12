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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthesising a module descriptor for a jar that has none — what ModiTect used to do for the
 * {@code modularize} goal, done here with the Class-File API and the JDK's own module reader.
 *
 * <p>The requirement is narrow and checkable: derive {@code requires} from the types the jar really
 * uses, export what it contains, promote {@code META-INF/services} to {@code provides}, and produce
 * a descriptor the module system accepts — not merely one that parses.
 */
@DisplayName("Module descriptor synthesis — giving a plain jar a module-info of its own")
class ModuleDescriptorSynthesizerTest {

    @TempDir
    Path tmp;

    private static final String HELPER = """
            package org.tool.support;
            public class Helper {
                public static String tag() { return "helper"; }
            }
            """;

    private static final String UNUSED = """
            package org.unused;
            public class Unused {
                public static String tag() { return "unused"; }
            }
            """;

    private static final String WIDGET = """
            package com.acme.widget;
            import org.tool.support.Helper;
            public class Widget {
                public String describe() { return "widget:" + Helper.tag(); }
                public java.util.logging.Logger logger() { return java.util.logging.Logger.getGlobal(); }
            }
            """;

    private static final String INTERNALS = """
            package com.acme.widget.internal;
            public class Internals {
                public static String secret() { return "secret"; }
            }
            """;

    /** The supporting jar carries its own descriptor, so its module name is not guesswork. */
    private static final String SUPPORT_MODULE_INFO = """
            module org.tool.support {
                exports org.tool.support;
            }
            """;

    private ModuleDescriptorSynthesizer.Result synthesize(boolean open, Set<String> uses,
                                                          Map<String, String> extras) throws Exception {
        var support = TestJars.modularJar(tmp, "support", List.of(), SUPPORT_MODULE_INFO, HELPER);
        var unused = TestJars.modularJar(tmp, "unused", List.of(),
                "module org.unused { exports org.unused; }", UNUSED);
        var widget = TestJars.jar(tmp, "widget", List.of(support), extras, null, WIDGET, INTERNALS);

        return ModuleDescriptorSynthesizer.synthesize(new ModuleDescriptorSynthesizer.Request(
                widget, "com.acme.widget", open, List.of(support, unused, widget), uses));
    }

    @Test
    @DisplayName("requires follow the types the jar actually uses, nothing more")
    void requiresFollowActualUse() throws Exception {
        var result = synthesize(true, Set.of(), Map.of());
        var descriptor = result.descriptor();

        var required = descriptor.requires().stream().map(ModuleDescriptor.Requires::name).toList();
        assertTrue(required.contains("org.tool.support"),
                "Widget calls Helper, so the module must read the module that owns it: " + required);
        assertTrue(required.contains("java.logging"),
                "a JDK type used in a signature counts just as much as a dependency's: " + required);
        assertTrue(required.contains("java.base"), "java.base is always required: " + required);
        assertFalse(required.contains("org.unused"),
                "a jar that is merely on the path must not become a dependency: " + required);
        assertFalse(required.contains("com.acme.widget"),
                "a module never requires itself: " + required);

        assertTrue(descriptor.requires().stream()
                        .filter(r -> r.name().equals("java.base"))
                        .allMatch(r -> r.modifiers().contains(ModuleDescriptor.Requires.Modifier.MANDATED)),
                "java.base is mandated, as javac writes it");
    }

    @Test
    @DisplayName("every package is exported and listed, so the descriptor is self-consistent")
    void exportsAndPackagesAreComplete() throws Exception {
        var result = synthesize(true, Set.of(), Map.of());

        assertEquals(Set.of("com.acme.widget", "com.acme.widget.internal"), result.descriptor().packages(),
                "ModulePackages must list what the jar contains — the JDK's reader rejects a "
                        + "descriptor that exports a package it does not declare");
        assertEquals(Set.of("com.acme.widget", "com.acme.widget.internal"),
                result.descriptor().exports().stream()
                        .map(ModuleDescriptor.Exports::source).collect(java.util.stream.Collectors.toSet()),
                "a jar that used to be on the class path had everything visible: keep it that way");

        assertTrue(result.descriptor().isOpen(), "the request asked for an open module");
        assertTrue(result.descriptor().opens().isEmpty(),
                "an open module opens everything already — a non-empty opens table is rejected by the JVM");

        // The bytes, not just the object we built: the JDK's own reader must accept them.
        var reread = ModuleDescriptor.read(new ByteArrayInputStream(result.moduleInfo()));
        assertEquals(result.descriptor().name(), reread.name());
        assertEquals(result.descriptor().packages(), reread.packages());
    }

    @Test
    @DisplayName("a closed module opens every package explicitly instead")
    void closedModuleOpensExplicitly() throws Exception {
        var result = synthesize(false, Set.of(), Map.of());

        assertFalse(result.descriptor().isOpen(), "the request asked for a closed module");
        assertEquals(Set.of("com.acme.widget", "com.acme.widget.internal"),
                result.descriptor().opens().stream()
                        .map(ModuleDescriptor.Opens::source).collect(java.util.stream.Collectors.toSet()),
                "reflection must keep working for a library that used to sit on the class path");
    }

    @Test
    @DisplayName("META-INF/services becomes provides, and declared uses become uses")
    void servicesArePromoted() throws Exception {
        var services = Map.of(
                "META-INF/services/org.tool.support.Helper",
                "# a comment, and a blank line\n\ncom.acme.widget.Widget\n");

        var result = synthesize(true, Set.of("org.tool.support.Helper"), services);

        var provides = result.descriptor().provides();
        assertEquals(1, provides.size(), "exactly one service file was present: " + provides);
        assertEquals("org.tool.support.Helper", provides.iterator().next().service());
        assertEquals(List.of("com.acme.widget.Widget"), provides.iterator().next().providers(),
                "comments and blank lines are not provider names");

        assertEquals(Set.of("org.tool.support.Helper"), result.descriptor().uses(),
                "uses cannot be derived from a jar's bytes alone, so the caller supplies them");
    }

    @Test
    @DisplayName("the synthesized module resolves for real, and its classes link across it")
    void theSynthesizedModuleResolves() throws Exception {
        var support = TestJars.modularJar(tmp, "support", List.of(), SUPPORT_MODULE_INFO, HELPER);
        var widget = TestJars.plainJar(tmp, "widget", List.of(support), WIDGET, INTERNALS);

        var result = ModuleDescriptorSynthesizer.synthesize(new ModuleDescriptorSynthesizer.Request(
                widget, "com.acme.widget", true, List.of(support, widget), Set.of()));
        var patched = ModuleDescriptorSynthesizer.writeJarWithDescriptor(
                widget, result.moduleInfo(), tmp.resolve("out"));

        var finder = java.lang.module.ModuleFinder.of(patched, support);
        var configuration = ModuleLayer.boot().configuration()
                .resolve(finder, java.lang.module.ModuleFinder.of(), List.of("com.acme.widget"));
        var layer = ModuleLayer.boot()
                .defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());

        var loader = layer.findLoader("com.acme.widget");
        Class<?> type = loader.loadClass("com.acme.widget.Widget");
        assertEquals("com.acme.widget", type.getModule().getName(),
                "the patched jar must be resolved as an explicit module, not as an automatic one");
        assertEquals("widget:helper", type.getMethod("describe").invoke(type.getDeclaredConstructor().newInstance()),
                "the derived `requires` must be enough for the code to actually run");
    }

    @Test
    @DisplayName("a type reached only through an annotation or a generic still yields a requires")
    void indirectTypeReferencesCount() throws Exception {
        var meta = TestJars.modularJar(tmp, "meta", List.of(), """
                module org.tool.meta { exports org.tool.meta; }
                """, """
                package org.tool.meta;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                public @interface Marker { }
                """);
        var boxed = TestJars.modularJar(tmp, "boxed", List.of(), """
                module org.tool.boxed { exports org.tool.boxed; }
                """, """
                package org.tool.boxed;
                public class Boxed { }
                """);
        // Marker appears only as an annotation, Boxed only inside a generic signature: neither is
        // ever the type of a field, a parameter or a call target.
        var widget = TestJars.plainJar(tmp, "widget", List.of(meta, boxed), """
                package com.acme.widget;
                @org.tool.meta.Marker
                public class Annotated {
                    private final java.util.List<org.tool.boxed.Boxed> items = java.util.List.of();
                    public int size() { return items.size(); }
                }
                """);

        var result = ModuleDescriptorSynthesizer.synthesize(new ModuleDescriptorSynthesizer.Request(
                widget, "com.acme.widget", true, List.of(meta, boxed, widget), Set.of()));

        var required = result.descriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name).toList();
        assertTrue(required.contains("org.tool.meta"),
                "an annotation kept in the class file is a real dependency: " + required);
        assertTrue(required.contains("org.tool.boxed"),
                "so is a type that only ever appears in a generic signature: " + required);
    }

    @Test
    @DisplayName("the module version is stamped when the caller knows one")
    void moduleVersionIsStamped() throws Exception {
        var support = TestJars.modularJar(tmp, "support", List.of(), SUPPORT_MODULE_INFO, HELPER);
        var widget = TestJars.plainJar(tmp, "widget", List.of(support), WIDGET, INTERNALS);

        var stamped = ModuleDescriptorSynthesizer.synthesize(new ModuleDescriptorSynthesizer.Request(
                widget, "com.acme.widget", true, List.of(support, widget), Set.of(), "1.2.3"));
        assertEquals("1.2.3", stamped.descriptor().rawVersion().orElseThrow(),
                "a jar's version is knowable — from its file name — and worth keeping in the descriptor");

        var plain = ModuleDescriptorSynthesizer.synthesize(new ModuleDescriptorSynthesizer.Request(
                widget, "com.acme.widget", true, List.of(support, widget), Set.of(), null));
        assertTrue(plain.descriptor().rawVersion().isEmpty(),
                "and absent rather than invented when the caller has none");
    }

    @Test
    @DisplayName("a signed jar loses its signature, because a descriptor invalidates it anyway")
    void signatureFilesAreLeftBehind() throws Exception {
        var support = TestJars.modularJar(tmp, "support", List.of(), SUPPORT_MODULE_INFO, HELPER);
        var widget = TestJars.jar(tmp, "widget", List.of(support), Map.of(
                        "META-INF/VENDOR.SF", "Signature-Version: 1.0\n",
                        "META-INF/VENDOR.RSA", "not really a signature block"),
                null, WIDGET, INTERNALS);

        var result = ModuleDescriptorSynthesizer.synthesize(new ModuleDescriptorSynthesizer.Request(
                widget, "com.acme.widget", true, List.of(support, widget), Set.of()));
        var patched = ModuleDescriptorSynthesizer.writeJarWithDescriptor(
                widget, result.moduleInfo(), tmp.resolve("out"));

        try (var copy = new java.util.jar.JarFile(patched.toFile())) {
            var entries = copy.stream().map(java.util.jar.JarEntry::getName).toList();
            assertFalse(entries.contains("META-INF/VENDOR.SF"),
                    "a signature that can no longer verify must not be carried over: " + entries);
            assertFalse(entries.contains("META-INF/VENDOR.RSA"), "nor its block: " + entries);
            assertTrue(entries.contains("com/acme/widget/Widget.class"), "the code is still there");
        }
    }

    @Test
    @DisplayName("the patched jar keeps every original entry, and gains only the descriptor")
    void patchedJarKeepsItsContent() throws Exception {
        var support = TestJars.modularJar(tmp, "support", List.of(), SUPPORT_MODULE_INFO, HELPER);
        var widget = TestJars.jar(tmp, "widget", List.of(support),
                Map.of("META-INF/services/org.tool.support.Helper", "com.acme.widget.Widget\n"),
                null, WIDGET, INTERNALS);

        var result = ModuleDescriptorSynthesizer.synthesize(new ModuleDescriptorSynthesizer.Request(
                widget, "com.acme.widget", true, List.of(support, widget), Set.of()));
        var patched = ModuleDescriptorSynthesizer.writeJarWithDescriptor(
                widget, result.moduleInfo(), tmp.resolve("out"));

        assertEquals(widget.getFileName().toString(), patched.getFileName().toString(),
                "downstream tooling substitutes the patched copy by file name");
        try (var original = new java.util.jar.JarFile(widget.toFile());
             var copy = new java.util.jar.JarFile(patched.toFile())) {
            var before = original.stream().map(java.util.jar.JarEntry::getName).sorted().toList();
            var after = copy.stream().map(java.util.jar.JarEntry::getName).sorted().toList();
            assertTrue(after.containsAll(before), "no entry may be lost: missing "
                    + before.stream().filter(e -> !after.contains(e)).toList());
            assertEquals(before.size() + 1, after.size(),
                    "exactly one entry is added — the descriptor: " + after);
            assertTrue(after.contains("module-info.class"),
                    "the descriptor must sit at the jar root, not under META-INF/versions");
        }
    }
}
