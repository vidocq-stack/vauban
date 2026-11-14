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
package io.vidocq.vauban.moduleit;

import io.vidocq.vauban.core.weaving.LoadTimeWeaving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261114-01 on the build output of this module. {@link Collaborator} is {@code @ApplicationScoped} with an
 * implicit public no-arg constructor: the processor shipped {@code Collaborator_ClientProxy}, and the javac plugin
 * wove the {@code (ProxyLink)} marker into the bean and retargeted the proxy onto it. An IDE that recompiles the bean
 * with plain {@code javac -proc:none} writes it back without the marker, next to that proxy. The start used to see a
 * no-arg constructor and weave nothing, and the first client proxy threw {@code NoSuchMethodError}.
 */
@DisplayName("A bean class recompiled without Vauban under its woven client proxy (BUG-20261114-01)")
class ReplacedBeanClassModulePathTest {

    private static final String BEAN = Collaborator.class.getName();
    private static final String PROXY = BEAN + "_ClientProxy";
    private static final MethodTypeDesc MARKER_CTOR =
            MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("io.vidocq.vauban.api.ProxyLink"));

    /**
     * The application loader of an IDE run over a replaced output directory: it defines the bean and its proxy
     * itself, serves their class files first, and lists only its own {@code vauban-beans.list}.
     */
    private static final class ReplacedOutput extends URLClassLoader {

        ReplacedOutput(Path classes, ClassLoader parent) throws IOException {
            super(new URL[] {classes.toUri().toURL()}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!BEAN.equals(name) && !PROXY.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                return loaded != null ? loaded : findClass(name);
            }
        }

        @Override
        public URL getResource(String name) {
            var own = findResource(name);
            return own != null ? own : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return findResources(name);
        }
    }

    @Test
    @DisplayName("the build wove the marker into the no-arg bean and chained its client proxy to it")
    void buildWovenBeanAndProxy() throws IOException {
        var beanMethods = ClassFile.of().parse(builtBytes(BEAN)).methods();
        assertTrue(beanMethods.stream().anyMatch(ReplacedBeanClassModulePathTest::isMarker),
                "the javac plugin weaves every top-level normal-scoped bean, no-arg constructor or not");
        assertTrue(chainsToMarker(builtBytes(PROXY)), "the shipped proxy calls Collaborator.<init>(ProxyLink)");
    }

    @Test
    @DisplayName("the bean recompiled without the marker is woven at load, and its first client proxy works")
    void recompiledBeanIsWovenAtLoad(@TempDir Path dir) throws Exception {
        var classes = dir.resolve("classes");
        write(classes, BEAN, withoutMarker(builtBytes(BEAN)));
        write(classes, PROXY, builtBytes(PROXY));
        Files.createDirectories(classes.resolve("META-INF"));
        Files.writeString(classes.resolve("META-INF/vauban-beans.list"), BEAN + "\n");

        try (var loader = new ReplacedOutput(classes, getClass().getClassLoader())) {
            var result = LoadTimeWeaving.prepare(loader);

            var proxy = loader.loadClass(PROXY);
            assertSame(loader, proxy.getClassLoader(), "the proxy under test is the replaced output's");
            Supplier<Object> delegate = () -> newBean(loader);
            var instance = assertDoesNotThrow(() -> proxy.getDeclaredConstructor(Supplier.class).newInstance(delegate),
                    "the first client proxy threw NoSuchMethodError: <init>(ProxyLink)");
            assertEquals("hello", proxy.getMethod("hello").invoke(instance));
            assertNull(result.failure(), "attach must succeed: " + result.failure());
            assertEquals(Set.of(BEAN), result.planned());
        }
    }

    private static Object newBean(ClassLoader loader) {
        try {
            return loader.loadClass(BEAN).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The class file this module's build wrote. */
    private static byte[] builtBytes(String binaryName) throws IOException {
        var simpleName = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        try (var in = Collaborator.class.getResourceAsStream(simpleName + ".class")) {
            assertNotNull(in, binaryName + " is not in the build output");
            return in.readAllBytes();
        }
    }

    /** What {@code javac -proc:none} writes for the same source: the class without the woven marker. */
    private static byte[] withoutMarker(byte[] woven) {
        return ClassFile.of().transformClass(ClassFile.of().parse(woven),
                ClassTransform.dropping(e -> e instanceof MethodModel m && isMarker(m)));
    }

    private static boolean isMarker(MethodModel method) {
        return method.methodName().equalsString(ConstantDescs.INIT_NAME)
                && method.methodTypeSymbol().equals(MARKER_CTOR);
    }

    private static boolean chainsToMarker(byte[] proxy) {
        return ClassFile.of().parse(proxy).methods().stream()
                .filter(m -> m.methodName().equalsString(ConstantDescs.INIT_NAME))
                .flatMap(m -> m.code().stream())
                .flatMap(CodeModel::elementStream)
                .anyMatch(e -> e instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().equals(BEAN.replace('.', '/'))
                        && invoke.name().equalsString(ConstantDescs.INIT_NAME)
                        && invoke.typeSymbol().equals(MARKER_CTOR));
    }

    private static void write(Path classes, String binaryName, byte[] bytes) throws IOException {
        var file = classes.resolve(binaryName.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
