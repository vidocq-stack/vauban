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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.container.FrozenExtensionsTest.Greeting;
import io.vidocq.vauban.core.container.FrozenExtensionsTest.GreetingCreator;
import io.vidocq.vauban.core.extensions.FrozenExtensions;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frozen record stands for what the extensions read beyond the bean classes too: the defaults of the annotation
 * types they read and the annotations of a {@code package-info} (BUG-20261118-29). The library those come from is
 * written here, once as the build sees it and once changed, so that the start reads another class file than the
 * build; the bean class, compiled once, keeps the fingerprint of the deployment.
 */
@DisplayName("BUG-20261118-29 - the frozen record and the classes the extensions read")
class FrozenReadClassesTest {

    private static final String HOLDER = "lib.Holder";

    @TempDir
    Path output;
    @TempDir
    Path built;
    @TempDir
    Path changed;

    @BeforeEach
    void reset() {
        ReadingExtension.RUNS.set(0);
    }

    /** Records what it reads of {@code lib.Holder}: its {@code @Ranked} rank, its package's {@code @Tag}. */
    public static class ReadingExtension implements BuildCompatibleExtension {
        static final AtomicInteger RUNS = new AtomicInteger();
        private final List<String> seen = new ArrayList<>();

        public ReadingExtension() {
            RUNS.incrementAndGet();
        }

        @Enhancement(types = Object.class, withSubtypes = true)
        public void read(ClassInfo type) {
            if (!type.name().equals(HOLDER)) {
                return;
            }
            for (AnnotationInfo annotation : type.annotations()) {
                if (annotation.name().equals("lib.Ranked")) {
                    seen.add("rank " + annotation.member("rank").asInt());
                } else if (annotation.hasMember("forRemoval")) {
                    // @Deprecated: its defaults are read, from a class of the JDK the record leaves out.
                    seen.add("deprecated");
                }
            }
            for (AnnotationInfo annotation : type.packageInfo().annotations()) {
                seen.add("tag " + annotation.value().asString());
            }
        }

        @Synthesis
        public void greeting(SyntheticComponents components) {
            components.addBean(Greeting.class).type(Greeting.class).scope(Dependent.class)
                    .withParam("text", String.join(", ", seen)).createWith(GreetingCreator.class);
        }
    }

    @Test
    @DisplayName("a changed annotation default refuses the record, and the start reads the new default")
    void aChangedAnnotationDefaultRefusesTheRecord() throws Exception {
        writeLibrary(built, 1, "one");
        writeLibrary(changed, 2, "one");

        assertEquals("rank 1, deprecated, tag one", startedAfterAFreeze());
    }

    @Test
    @DisplayName("a changed package-info refuses the record, and the start reads the new annotation")
    void aChangedPackageInfoRefusesTheRecord() throws Exception {
        writeLibrary(built, 1, "one");
        writeLibrary(changed, 1, "two");

        assertEquals("rank 1, deprecated, tag one", startedAfterAFreeze());
    }

    @Test
    @DisplayName("an unchanged library keeps the record")
    void anUnchangedLibraryKeepsTheRecord() throws Exception {
        writeLibrary(built, 1, "one");
        writeLibrary(changed, 1, "one");

        assertTrue(freeze(), "frozen");
        ReadingExtension.RUNS.set(0);
        try (var container = deployment(changed).build()) {
            assertEquals(0, ReadingExtension.RUNS.get(), "the record stands");
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals("rank 1, deprecated, tag one", container.select(Greeting.class).text());
        }
    }

    @Test
    @DisplayName("a record written before it kept the classes read stays valid: format and fingerprint unchanged")
    void aRecordWithoutTheClassesReadStaysValid() throws Exception {
        writeLibrary(built, 1, "one");
        writeLibrary(changed, 2, "two");
        assertTrue(freeze(), "frozen");
        Path recordFile = output.resolve(FrozenExtensions.PATH);
        List<String> lines = Files.readAllLines(recordFile);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("read.lib.Ranked=")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("read.lib.package-info=")),
                String.join("\n", lines));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("read.java.")),
                "the JDK's classes, @Deprecated here, stay out of the record: " + String.join("\n", lines));
        Files.write(recordFile, lines.stream().filter(line -> !line.startsWith("read.")).toList());

        ReadingExtension.RUNS.set(0);
        try (var container = deployment(changed).build()) {
            assertEquals(0, ReadingExtension.RUNS.get(), "the older record is used, as before");
            assertTrue(container.extensionsAtStart().frozen());
            assertEquals("rank 1, deprecated, tag one", container.select(Greeting.class).text());
        }
    }

    /**
     * Freezes on {@link #built}, then starts on {@link #changed} and checks the start ran the extensions on the new
     * library; returns what the freeze saw.
     */
    private String startedAfterAFreeze() {
        assertTrue(freeze(), "frozen");
        String frozenText;
        try (var container = deployment(built).build()) {
            frozenText = container.select(Greeting.class).text();
        }
        ReadingExtension.RUNS.set(0);
        try (var container = deployment(changed).build()) {
            String note = container.extensionsAtStart().note();
            assertTrue(ReadingExtension.RUNS.get() > 0, "the record is stale: the extensions run");
            assertFalse(container.extensionsAtStart().frozen(), "the record is refused");
            assertTrue(note != null && note.contains("lib."), "the note names what changed: " + note);
            assertNotEquals(frozenText, container.select(Greeting.class).text(), "the start reads the change");
        }
        return frozenText;
    }

    private boolean freeze() {
        var outcome = deployment(built).freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());
        return outcome.frozen();
    }

    /** The deployment: {@code lib.Holder} and the extension, on a loader holding the output, then the library. */
    private VaubanContainerBuilder deployment(Path library) {
        var loader = loader(library);
        try {
            return VaubanContainer.builder().classLoader(loader)
                    .addBeanClass(Class.forName(HOLDER, false, loader)).addBeanClass(ReadingExtension.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The output first, where the record is, then {@code library}, then the bean class compiled with the build. */
    private ClassLoader loader(Path library) {
        try {
            return new URLClassLoader(new URL[] {output.toUri().toURL(), library.toUri().toURL(),
                    built.resolve("holder").toUri().toURL()}, getClass().getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes {@code lib.Ranked}, whose {@code rank} defaults to {@code rank}, {@code lib.Tag}, and the package
     * {@code lib} annotated {@code @Tag(tag)}, into {@code into}; for {@link #built}, also {@code lib.Holder},
     * annotated {@code @Ranked} without a rank and {@code @Deprecated}, into {@code holder} below it. Class files,
     * as javac writes them for this source:
     * <pre>{@code
     * @Retention(RUNTIME) public @interface Ranked { int rank() default <rank>; }
     * @Retention(RUNTIME) public @interface Tag { String value(); }
     * @Tag("<tag>") package lib;
     * @Ranked @Deprecated public class Holder {}
     * }</pre>
     */
    private void writeLibrary(Path into, int rank, String tag) throws IOException {
        write(into, "Ranked", annotationType("Ranked", ConstantDescs.CD_int, AnnotationValue.ofInt(rank)));
        write(into, "Tag", annotationType("Tag", ConstantDescs.CD_String, null));
        write(into, "package-info", ClassFile.of().build(ClassDesc.of("lib.package-info"), type -> type
                .withFlags(ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT | ClassFile.ACC_SYNTHETIC)
                .with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("lib.Tag"),
                        AnnotationElement.ofString("value", tag))))));
        if (into.equals(built)) {
            write(into.resolve("holder"), "Holder", ClassFile.of().build(ClassDesc.of(HOLDER), type -> type
                    .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                    .with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("lib.Ranked")),
                            Annotation.of(ClassDesc.of(Deprecated.class.getName()))))
                    .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                            .aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                            .return_())));
        }
    }

    /** A runtime-retained annotation type {@code lib.<name>} with one member, {@code rank} or {@code value}. */
    private static byte[] annotationType(String name, ClassDesc memberType, AnnotationValue defaultValue) {
        String member = defaultValue == null ? "value" : "rank";
        return ClassFile.of().build(ClassDesc.of("lib." + name), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                        | ClassFile.ACC_ANNOTATION)
                .withInterfaceSymbols(ClassDesc.of("java.lang.annotation.Annotation"))
                .with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of(Retention.class.getName()),
                        AnnotationElement.of("value", AnnotationValue.ofEnum(
                                ClassDesc.of(RetentionPolicy.class.getName()), RetentionPolicy.RUNTIME.name())))))
                .withMethod(member, MethodTypeDesc.of(memberType), ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                        method -> {
                            if (defaultValue != null) {
                                method.with(AnnotationDefaultAttribute.of(defaultValue));
                            }
                        }));
    }

    private static void write(Path directory, String name, byte[] classFile) throws IOException {
        Files.write(Files.createDirectories(directory.resolve("lib")).resolve(name + ".class"), classFile);
    }
}
