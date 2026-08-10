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
package io.vidocq.vauban.maven.generate;

import io.vidocq.vauban.api.ProxyLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 of Vidocq/vauban#24: the plugin must make normal-scoped beans proxyable
 * <strong>without any change to the application's source code</strong>, by weaving a
 * synthetic {@code (ProxyLink)} client-proxy entry constructor into the compiled bean
 * class at {@code process-classes} and pointing the proxy's {@code <init>} at it.
 *
 * <p>The fixtures are synthesized straight to bytecode — there is deliberately no
 * {@code ProxyLink} anywhere in "the application": the whole point is that the app
 * (Rossignol) never declares it.
 */
@DisplayName("VaubanGenerator — automatic ProxyLink weaving (no app-code change)")
class ProxyLinkWeavingTest {

    private static final ClassDesc CD_APP_SCOPED =
            ClassDesc.of("jakarta.enterprise.context.ApplicationScoped");
    private static final String MARKER = ProxyLink.CLASS_NAME;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a bean whose only constructor dereferences its parameter gets a woven marker and a working proxy")
    void weavesMarkerIntoInjectStyleBean() throws Exception {
        Path classes = classesDir(
                dep("com.myapp.Dep"),
                derefBean("com.myapp.Oidc", "com.myapp.Dep"));

        generate(classes);

        try (var loader = loaderOver(classes)) {
            Class<?> bean = loader.loadClass("com.myapp.Oidc");
            var marker = Arrays.stream(bean.getDeclaredConstructors())
                    .filter(c -> c.getParameterCount() == 1
                            && c.getParameterTypes()[0].getName().equals(MARKER))
                    .findFirst();
            assertTrue(marker.isPresent(), "the plugin must weave a (ProxyLink) constructor into the bean");
            assertTrue(marker.get().isSynthetic(), "the woven constructor must be ACC_SYNTHETIC");

            Class<?> proxy = loader.loadClass("com.myapp.Oidc_ClientProxy");
            assertDoesNotThrow(() -> proxy.getDeclaredConstructor().newInstance(),
                    "the proxy must chain to the woven marker, not dereference a null parameter");
        }
    }

    @Test
    @DisplayName("an APT-pre-generated proxy is retargeted onto the woven marker")
    void retargetsPreExistingProxy() throws Exception {
        Path classes = classesDir(
                dep("com.myapp.Dep"),
                derefBean("com.myapp.Oidc", "com.myapp.Dep"));

        // Simulate the APT flow: proxy already on disk (chaining the business constructor
        // with default arguments) and vauban-beans.list already written.
        byte[] proxyBytes;
        try (var pre = loaderOver(classes)) {
            proxyBytes = io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator
                    .generate(pre.loadClass("com.myapp.Oidc")).bytecode();
        }
        write(classes, "com.myapp.Oidc_ClientProxy", proxyBytes);
        Files.createDirectories(classes.resolve("META-INF"));
        Files.writeString(classes.resolve(VaubanGenerator.BEANS_LIST_PATH),
                "# test\ncom.myapp.Dep\ncom.myapp.Oidc\n");

        generate(classes);

        try (var loader = loaderOver(classes)) {
            Class<?> proxy = loader.loadClass("com.myapp.Oidc_ClientProxy");
            assertDoesNotThrow(() -> proxy.getDeclaredConstructor().newInstance(),
                    "the pre-existing proxy must be retargeted onto the woven marker");
        }
    }

    @Test
    @DisplayName("a no-arg bean's construction side effects no longer run at proxy creation; weaving is idempotent")
    void noArgBeanStopsDoubleConstruction() throws Exception {
        Path classes = classesDir(flagBean("com.myapp.Counted"));

        generate(classes);
        generate(classes); // idempotence: a second run must not add a second marker

        try (var loader = loaderOver(classes)) {
            Class<?> bean = loader.loadClass("com.myapp.Counted");
            long markers = Arrays.stream(bean.getDeclaredConstructors())
                    .filter(c -> c.getParameterCount() == 1
                            && c.getParameterTypes()[0].getName().equals(MARKER))
                    .count();
            assertEquals(1, markers, "weaving twice must not duplicate the marker constructor");

            Object direct = bean.getDeclaredConstructor().newInstance();
            assertTrue(bean.getField("initialized").getBoolean(direct),
                    "the business constructor must keep initializing real instances");

            Object proxy = loader.loadClass("com.myapp.Counted_ClientProxy")
                    .getDeclaredConstructor().newInstance();
            assertFalse(bean.getField("initialized").getBoolean(proxy),
                    "creating the proxy must not run the bean's business constructor");
        }
    }

    // ---- Fixtures (raw bytecode — no ProxyLink anywhere in the "application") -------------------

    private record Fixture(String fqn, byte[] bytes) {}

    /** {@code @ApplicationScoped} bean with a no-arg ctor and a {@code String name()} method. */
    private static Fixture dep(String fqn) {
        var cd = ClassDesc.of(fqn);
        return new Fixture(fqn, ClassFile.of().build(cd, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(CD_APP_SCOPED)));
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
            clb.withMethodBody("name", MethodTypeDesc.of(ClassDesc.of("java.lang.String")),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.ldc("dep");
                        cob.areturn();
                    });
        }));
    }

    /**
     * {@code @ApplicationScoped} bean whose <em>only</em> constructor is an {@code @Inject}
     * one taking a {@code depFqn} and dereferencing it ({@code param.name()}) — the
     * single-@Inject-constructor style that NPEs when the proxy passes null.
     */
    private static Fixture derefBean(String fqn, String depFqn) {
        var cd = ClassDesc.of(fqn);
        var depCd = ClassDesc.of(depFqn);
        return new Fixture(fqn, ClassFile.of().build(cd, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(CD_APP_SCOPED)));
            clb.withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, depCd),
                    ClassFile.ACC_PUBLIC, mb -> {
                        mb.with(RuntimeVisibleAnnotationsAttribute.of(
                                Annotation.of(ClassDesc.of("jakarta.inject.Inject"))));
                        mb.withCode(cob -> {
                            cob.aload(0);
                            cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                            cob.aload(1);
                            cob.invokevirtual(depCd, "name",
                                    MethodTypeDesc.of(ClassDesc.of("java.lang.String")));
                            cob.pop();
                            cob.return_();
                        });
                    });
            clb.withMethodBody("issuer", MethodTypeDesc.of(ClassDesc.of("java.lang.String")),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.ldc("issuer");
                        cob.areturn();
                    });
        }));
    }

    /** {@code @ApplicationScoped} bean whose no-arg ctor sets {@code public boolean initialized}. */
    private static Fixture flagBean(String fqn) {
        var cd = ClassDesc.of(fqn);
        return new Fixture(fqn, ClassFile.of().build(cd, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(CD_APP_SCOPED)));
            clb.withField("initialized", ConstantDescs.CD_boolean, ClassFile.ACC_PUBLIC);
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.aload(0);
                        cob.iconst_1();
                        cob.putfield(cd, "initialized", ConstantDescs.CD_boolean);
                        cob.return_();
                    });
            clb.withMethodBody("greet", MethodTypeDesc.of(ClassDesc.of("java.lang.String")),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.ldc("hi");
                        cob.areturn();
                    });
        }));
    }

    // ---- Harness --------------------------------------------------------------------------------

    private Path classesDir(Fixture... fixtures) throws IOException {
        Path classes = tempDir.resolve("classes");
        for (var fixture : fixtures) {
            write(classes, fixture.fqn(), fixture.bytes());
        }
        return classes;
    }

    private static void write(Path classes, String fqn, byte[] bytes) throws IOException {
        Path file = classes.resolve(fqn.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private void generate(Path classes) throws IOException {
        try (var loader = loaderOver(classes)) {
            VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, classes, loader));
        }
    }

    /** Fresh loader over the (possibly patched) classes dir; parent provides jakarta + ProxyLink. */
    private URLClassLoader loaderOver(Path classes) throws IOException {
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                getClass().getClassLoader());
    }
}
