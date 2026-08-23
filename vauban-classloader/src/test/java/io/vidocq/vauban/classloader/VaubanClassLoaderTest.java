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
package io.vidocq.vauban.classloader;

import io.vidocq.vauban.classloader.spi.PluginContext;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine over an exploded directory archive: source resolution, cdi-proxifier
 * transformation at definition, idempotence on woven input, exclusions, resources, and
 * the transformer disable property.
 */
@DisplayName("VaubanClassLoader — engine semantics")
class VaubanClassLoaderTest {

    private static final String APPLICATION_SCOPED_DESC = "Ljakarta/enterprise/context/ApplicationScoped;";
    private static final ClassDesc CD_PROXY_LINK = ClassDesc.of("io.vidocq.vauban.api.ProxyLink");

    @AfterEach
    void clearProperties() {
        System.clearProperty(VaubanClassLoader.DISABLED_TRANSFORMERS_PROPERTY);
    }

    @Test
    @DisplayName("an unwoven normal-scoped bean gains the marker at definition; its proxy is retargeted")
    void weavesBeanAndProxyAtDefinition(@TempDir Path dir) throws Exception {
        var app = appArchive(dir, "com.example.lapp.Service", true);
        try (var loader = VaubanClassLoader.of(List.of(app), getClass().getClassLoader(),
                PluginContext.empty())) {
            var bean = Class.forName("com.example.lapp.Service", true, loader);
            assertSame(loader, bean.getClassLoader());
            assertNotNull(bean.getDeclaredConstructor(
                    Class.forName("io.vidocq.vauban.api.ProxyLink")));

            var proxy = Class.forName("com.example.lapp.Service_ClientProxy", true, loader);
            // Instantiating the proxy chains the woven (ProxyLink) marker — the business
            // constructor (which throws) must never run
            var instance = proxy.getDeclaredConstructor().newInstance();
            assertNotNull(instance);
        }
    }

    @Test
    @DisplayName("a build-woven archive passes through unchanged (idempotence)")
    void wovenArchiveIsUntouched(@TempDir Path dir) throws Exception {
        var app = appArchive(dir, "com.example.lapp.Service", true);
        // Pre-weave on disk, as a javac-plugin build would have
        var beanFile = app.resolve("com/example/lapp/Service.class");
        Files.write(beanFile, io.vidocq.vauban.weaver.ProxyLinkWeaver.addMarkerConstructor(
                Files.readAllBytes(beanFile),
                io.vidocq.vauban.weaver.ProxyLinkWeaver.SuperChain.NO_ARG));
        var wovenOnDisk = Files.readAllBytes(beanFile);

        try (var loader = VaubanClassLoader.of(List.of(app), getClass().getClassLoader(),
                PluginContext.empty())) {
            var bean = Class.forName("com.example.lapp.Service", true, loader);
            assertNotNull(bean.getDeclaredConstructor(
                    Class.forName("io.vidocq.vauban.api.ProxyLink")));
            // and the transformer reported "unchanged" — raw bytes still equal disk bytes
            assertArrayEquals(wovenOnDisk, loader.rawClassBytes("com.example.lapp.Service"));
        }
    }

    @Test
    @DisplayName("the disable property turns the cdi-proxifier off")
    void disableProperty(@TempDir Path dir) throws Exception {
        System.setProperty(VaubanClassLoader.DISABLED_TRANSFORMERS_PROPERTY,
                CdiProxifierTransformer.NAME);
        var app = appArchive(dir, "com.example.lapp.Service", true);
        try (var loader = VaubanClassLoader.of(List.of(app), getClass().getClassLoader(),
                PluginContext.empty())) {
            var bean = Class.forName("com.example.lapp.Service", true, loader);
            assertThrows(NoSuchMethodException.class, () -> bean.getDeclaredConstructor(
                    Class.forName("io.vidocq.vauban.api.ProxyLink")));
        }
    }

    @Test
    @DisplayName("classes outside the archives delegate to the parent; excluded prefixes are never defined")
    void delegationAndExclusions(@TempDir Path dir) throws Exception {
        var app = appArchive(dir, "com.example.lapp.Service", true);
        // Smuggle a class with an excluded (container) prefix into the archive
        var smuggled = app.resolve("io/vidocq/vauban/core/fake/Smuggled.class");
        Files.createDirectories(smuggled.getParent());
        Files.write(smuggled, simpleClass("io.vidocq.vauban.core.fake.Smuggled"));

        try (var loader = VaubanClassLoader.of(List.of(app), getClass().getClassLoader(),
                PluginContext.empty())) {
            // parent delegation: a JDK class loads fine and is not ours
            assertSame(String.class, Class.forName("java.lang.String", true, loader));
            // the smuggled excluded class is refused
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("io.vidocq.vauban.core.fake.Smuggled", true, loader));
        }
    }

    @Test
    @DisplayName("resources are served from the archives (stream, URL, enumeration)")
    void resources(@TempDir Path dir) throws Exception {
        var app = appArchive(dir, "com.example.lapp.Service", true);
        try (var loader = VaubanClassLoader.of(List.of(app), getClass().getClassLoader(),
                PluginContext.empty())) {
            try (var in = loader.getResourceAsStream("META-INF/vauban-beans.list")) {
                assertNotNull(in);
                assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .contains("com.example.lapp.Service"));
            }
            var url = loader.getResource("META-INF/vauban-beans.list");
            assertNotNull(url);
            try (var in = url.openStream()) {
                assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .contains("com.example.lapp.Service"));
            }
            assertTrue(loader.getResources("META-INF/vauban-beans.list").hasMoreElements());
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Exploded archive: {@code @ApplicationScoped Service} with ONLY a throwing business
     * constructor, its business-chaining {@code Service_ClientProxy}, and a
     * {@code vauban-beans.list} declaring the bean.
     */
    static Path appArchive(Path dir, String beanName, boolean withBeansList) throws IOException {
        var root = dir.resolve("app");
        var beanFile = root.resolve(beanName.replace('.', '/') + ".class");
        Files.createDirectories(beanFile.getParent());
        Files.write(beanFile, beanWithThrowingBusinessCtor(beanName));
        var proxyFile = root.resolve(beanName.replace('.', '/') + "_ClientProxy.class");
        Files.write(proxyFile, proxyChainingBusinessCtor(beanName + "_ClientProxy", beanName));
        if (withBeansList) {
            var list = root.resolve("META-INF/vauban-beans.list");
            Files.createDirectories(list.getParent());
            Files.writeString(list, beanName + "\n");
        }
        return root;
    }

    /**
     * {@code @ApplicationScoped public class <name> { public <name>(String v) { throw …; } }}
     * — running the business constructor with a null arg is guaranteed to blow up.
     */
    static byte[] beanWithThrowingBusinessCtor(String binaryName) {
        var desc = ClassDesc.of(binaryName);
        return ClassFile.of().build(desc, clb -> {
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(
                    java.lang.classfile.Annotation.of(
                            ClassDesc.ofDescriptor(APPLICATION_SCOPED_DESC))));
            clb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("java.lang.String")),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        var ise = ClassDesc.of("java.lang.IllegalStateException");
                        cob.new_(ise);
                        cob.dup();
                        cob.invokespecial(ise, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.athrow();
                    });
        });
    }

    /** {@code public class <proxy> extends <bean> { public <proxy>() { super((String) null); } }} */
    static byte[] proxyChainingBusinessCtor(String proxyName, String beanName) {
        var proxyDesc = ClassDesc.of(proxyName);
        var beanDesc = ClassDesc.of(beanName);
        return ClassFile.of().build(proxyDesc, clb -> clb
                .withSuperclass(beanDesc)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, cob -> {
                            cob.aload(0);
                            cob.aconst_null();
                            cob.invokespecial(beanDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void,
                                            ClassDesc.of("java.lang.String")));
                            cob.return_();
                        }));
    }

    /** {@code public class <name> { public <name>() {} }} */
    static byte[] simpleClass(String binaryName) {
        var desc = ClassDesc.of(binaryName);
        return ClassFile.of().build(desc, clb -> clb
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, cob -> {
                            cob.aload(0);
                            cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                            cob.return_();
                        }));
    }
}
