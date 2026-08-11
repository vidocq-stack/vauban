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

import io.vidocq.vauban.api.ProxyLink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The vauban#24 load-time weaving tier, end to end inside this JVM: detection over
 * {@code META-INF/vauban-beans.list} + real class bytes, dynamic self-attach of the
 * vauban-weaver agent through the AttachBack child process, and verification that a class
 * defined AFTER the attach gains the (ProxyLink) constructor without ever running its
 * business constructor.
 *
 * <p>Each test uses a unique fixture class name: transformers registered by earlier
 * attaches live for the rest of the JVM, and the {@code plannedSoFar} registry is
 * intentionally per-JVM. Fixture bytes are synthesized (Class-File API) so nothing on the
 * test class path can pre-load them — the same constraint the production hook honours by
 * running before bean discovery.
 */
@DisplayName("LoadTimeWeaving — IDE-build fallback (detection + dynamic attach + weave at definition)")
class LoadTimeWeavingTest {

    private static final String APPLICATION_SCOPED_DESC = "Ljakarta/enterprise/context/ApplicationScoped;";

    @AfterEach
    void clearOptOut() {
        System.clearProperty(LoadTimeWeaving.MODE_PROPERTY);
    }

    @Test
    @DisplayName("an unwoven normal-scoped bean is detected, woven at definition, and its business constructor never runs")
    void weavesAtDefinitionAfterSelfAttach(@TempDir Path dir) throws Exception {
        var fixture = "com.example.idebuild.UnwovenBeanA";
        var classes = fixtureDir(dir, fixture, businessCtorOnlyFixture(fixture, true));

        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            var result = LoadTimeWeaving.prepare(loader);

            assertNull(result.failure(), "attach must succeed: " + result.failure());
            assertEquals(Set.of(fixture), result.planned());

            var woven = Class.forName(fixture, true, loader);
            assertSame(loader, woven.getClassLoader(), "fixture must be defined by the fresh loader");

            var marker = woven.getDeclaredConstructor(ProxyLink.class);
            assertTrue(marker.isSynthetic(), "woven marker constructor is synthetic");
            marker.setAccessible(true);
            var instance = marker.newInstance(new Object[] {null});
            assertNotNull(instance);
            assertFalse((boolean) woven.getField("businessCtorRan").get(null),
                    "the (ProxyLink) constructor must not run the business constructor");
        }
    }

    @Test
    @DisplayName("a second boot over the same classes returns the plan without re-attaching")
    void repeatedPrepareIsIdempotent(@TempDir Path dir) throws Exception {
        var fixture = "com.example.idebuild.UnwovenBeanB";
        var classes = fixtureDir(dir, fixture, businessCtorOnlyFixture(fixture, true));

        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            var first = LoadTimeWeaving.prepare(loader);
            var second = LoadTimeWeaving.prepare(loader);
            assertNull(first.failure());
            assertEquals(first.planned(), second.planned(),
                    "the backstop call must still report the planned classes for validation");
        }
    }

    @Test
    @DisplayName("nothing to do: beans with a no-arg constructor yield an empty plan without attaching")
    void noArgBeanNeedsNoWeaving(@TempDir Path dir) throws Exception {
        var fixture = "com.example.idebuild.UnwovenBeanC";
        var classes = fixtureDir(dir, fixture, noArgFixture(fixture));
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            assertEquals(LoadTimeWeaving.Result.NONE, LoadTimeWeaving.prepare(loader));
        }
    }

    @Test
    @DisplayName("a class outside every vauban-beans.list is never considered")
    void undeclaredClassesAreIgnored(@TempDir Path dir) throws Exception {
        var fixture = "com.example.idebuild.UnwovenBeanD";
        // class file present, beans.list absent — synthetic/TCK-style deployment
        var classes = fixtureDir(dir, fixture, businessCtorOnlyFixture(fixture, true));
        Files.delete(classes.resolve("META-INF/vauban-beans.list"));
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            assertEquals(LoadTimeWeaving.Result.NONE, LoadTimeWeaving.prepare(loader));
        }
    }

    @Test
    @DisplayName("a declared bean without a normal scope annotation is not woven")
    void nonNormalScopedBeanIsIgnored(@TempDir Path dir) throws Exception {
        var fixture = "com.example.idebuild.UnwovenBeanE";
        var classes = fixtureDir(dir, fixture, businessCtorOnlyFixture(fixture, false));
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            assertEquals(LoadTimeWeaving.Result.NONE, LoadTimeWeaving.prepare(loader));
        }
    }

    @Test
    @DisplayName("-Dvauban.weaving.loadtime=disabled opts out entirely")
    void optOut(@TempDir Path dir) throws Exception {
        var fixture = "com.example.idebuild.UnwovenBeanF";
        var classes = fixtureDir(dir, fixture, businessCtorOnlyFixture(fixture, true));
        System.setProperty(LoadTimeWeaving.MODE_PROPERTY, "disabled");
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            assertEquals(LoadTimeWeaving.Result.NONE, LoadTimeWeaving.prepare(loader));
        }
    }

    /**
     * {@code @ApplicationScoped public class <name> { public static volatile boolean
     * businessCtorRan; public <name>(String v) { businessCtorRan = true; } }}
     */
    private static byte[] businessCtorOnlyFixture(String binaryName, boolean applicationScoped) {
        var desc = ClassDesc.of(binaryName);
        return ClassFile.of().build(desc, clb -> {
            clb.withSuperclass(ConstantDescs.CD_Object);
            if (applicationScoped) {
                clb.with(RuntimeVisibleAnnotationsAttribute.of(
                        java.lang.classfile.Annotation.of(
                                ClassDesc.ofDescriptor(APPLICATION_SCOPED_DESC))));
            }
            clb.withField("businessCtorRan", ConstantDescs.CD_boolean,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_VOLATILE);
            clb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("java.lang.String")),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.iconst_1();
                        cob.putstatic(desc, "businessCtorRan", ConstantDescs.CD_boolean);
                        cob.return_();
                    });
        });
    }

    /** {@code @ApplicationScoped public class <name> { public <name>() {} }} */
    private static byte[] noArgFixture(String binaryName) {
        var desc = ClassDesc.of(binaryName);
        return ClassFile.of().build(desc, clb -> clb
                .withSuperclass(ConstantDescs.CD_Object)
                .with(RuntimeVisibleAnnotationsAttribute.of(
                        java.lang.classfile.Annotation.of(
                                ClassDesc.ofDescriptor(APPLICATION_SCOPED_DESC))))
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, cob -> {
                            cob.aload(0);
                            cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                            cob.return_();
                        }));
    }

    /** Writes the fixture class plus a matching META-INF/vauban-beans.list. */
    private static Path fixtureDir(Path dir, String binaryName, byte[] classBytes)
            throws IOException {
        var out = dir.resolve("classes");
        var classFile = out.resolve(binaryName.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, classBytes);
        var list = out.resolve("META-INF/vauban-beans.list");
        Files.createDirectories(list.getParent());
        Files.writeString(list, String.join("\n",
                List.of("# test fixture", binaryName)) + "\n");
        return out;
    }
}
