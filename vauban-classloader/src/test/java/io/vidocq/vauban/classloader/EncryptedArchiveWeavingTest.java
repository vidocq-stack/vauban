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
import io.vidocq.vauban.sjar.SjarEncryptor;
import io.vidocq.vauban.sjar.SjarKeyProvider;
import io.vidocq.vauban.sjar.SjarPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The M1 headline: an <strong>unwoven bean inside an encrypted sjar</strong> becomes
 * proxyable — nobody can write into an encrypted jar, so the decrypt → weave composition
 * of the source/transformer chaining is the only possible fix (study §1).
 */
@DisplayName("VaubanClassLoader — decrypt → weave composition on an encrypted archive")
class EncryptedArchiveWeavingTest {

    @Test
    @DisplayName("an unwoven bean in an sjar gains the (ProxyLink) marker at definition")
    void weavesInsideEncryptedArchive(@TempDir Path dir) throws Exception {
        var jar = dir.resolve("thirdparty.jar");
        var beanName = "com.example.sjarapp.Secret";
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            // Only modular jars can be encrypted
            out.putNextEntry(new JarEntry("module-info.class"));
            out.write(java.lang.classfile.ClassFile.of().buildModule(
                    java.lang.classfile.attribute.ModuleAttribute.of(
                            java.lang.constant.ModuleDesc.of("com.example.sjarapp"),
                            mb -> mb.requires(java.lang.constant.ModuleDesc.of("java.base"),
                                    java.lang.classfile.ClassFile.ACC_MANDATED, null))));
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/example/sjarapp/Secret.class"));
            out.write(VaubanClassLoaderTest.beanWithThrowingBusinessCtor(beanName));
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/example/sjarapp/Secret_ClientProxy.class"));
            out.write(VaubanClassLoaderTest.proxyChainingBusinessCtor(
                    beanName + "_ClientProxy", beanName));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/vauban-beans.list"));
            out.write((beanName + "\n").getBytes());
            out.closeEntry();
        }

        SecretKey key = SjarKeyProvider.generateKey();
        SjarEncryptor.encryptJar(jar, key, "test");
        assertTrue(new SjarPlugin().handles(jar), "fixture must be an encrypted archive");

        var context = new PluginContext() {
            @Override
            public SecretKey resolveKey(String keyAlias) {
                return key;
            }

            @Override
            public Optional<String> property(String name) {
                return Optional.empty();
            }
        };

        try (var loader = VaubanClassLoader.of(List.of(jar), getClass().getClassLoader(), context)) {
            var bean = Class.forName(beanName, true, loader);
            assertSame(loader, bean.getClassLoader());
            assertNotNull(bean.getDeclaredConstructor(
                    Class.forName("io.vidocq.vauban.api.ProxyLink")),
                    "the decrypted bean must be woven at definition");

            // The proxy chains the woven marker: instantiating it runs no bean logic
            var proxy = Class.forName(beanName + "_ClientProxy", true, loader);
            assertNotNull(proxy.getDeclaredConstructor().newInstance());
        }
    }
}
