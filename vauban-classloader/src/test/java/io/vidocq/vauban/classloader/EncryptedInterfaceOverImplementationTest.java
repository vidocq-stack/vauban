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
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.constant.ModuleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape an encrypted product actually ships: the API a consumer compiles against stays in the
 * clear, because it lives in an {@code exports} package, while the implementation behind it is
 * encrypted. A producer hands out the interface; the implementation is only ever instantiated
 * inside the module.
 *
 * <p>This pins the composition at the point where it could silently break: the clear half must load
 * as an ordinary entry, the encrypted half must be decrypted at definition, and the two must still
 * link — the implementation really implements the interface, and a call through the interface
 * reaches the decrypted body.
 */
@DisplayName("VaubanClassLoader — a clear interface over an encrypted implementation")
class EncryptedInterfaceOverImplementationTest {

    private static final String MODULE = "it.secret";
    private static final String LEDGER = "it.secret.api.Ledger";
    private static final String SQL_LEDGER = "it.secret.internal.SqlLedger";
    private static final String ANSWER = "balance:42";

    @Test
    @DisplayName("the interface stays clear, the implementation is encrypted, and the call goes through")
    void clearInterfaceOverEncryptedImplementation(@TempDir Path dir) throws Exception {
        var jar = dir.resolve("secret-lib.jar");
        writeModularJar(jar);

        SecretKey key = SjarKeyProvider.generateKey();
        SjarEncryptor.encryptJar(jar, key, "test");
        assertTrue(new SjarPlugin().handles(jar), "fixture must be an encrypted archive");

        // What the packaging decided, and the whole point of exporting an API package.
        try (var zip = new JarFile(jar.toFile())) {
            assertNotNull(zip.getEntry("it/secret/api/Ledger.class"),
                    "the exported API must stay readable, or no consumer could compile against it");
            assertNull(zip.getEntry("it/secret/internal/SqlLedger.class"),
                    "the implementation lives outside the exported package, so it must be encrypted");
        }

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
            var ledger = Class.forName(LEDGER, true, loader);
            var impl = Class.forName(SQL_LEDGER, true, loader);

            assertSame(loader, ledger.getClassLoader(), "the clear interface comes from the archive");
            assertSame(loader, impl.getClassLoader(), "the encrypted implementation was decrypted");
            assertTrue(ledger.isInterface());
            assertTrue(ledger.isAssignableFrom(impl),
                    "the decrypted bytes must still link against the clear interface");

            var instance = impl.getDeclaredConstructor().newInstance();
            assertEquals(ANSWER, ledger.getMethod("balance").invoke(instance),
                    "a call through the interface must reach the decrypted implementation body");
        }
    }

    /** A modular jar exporting only its API package, with the implementation kept internal. */
    private static void writeModularJar(Path jar) throws Exception {
        var ledgerCd = ClassDesc.of(LEDGER);
        var implCd = ClassDesc.of(SQL_LEDGER);
        var stringMtd = MethodTypeDesc.of(ConstantDescs.CD_String);

        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(MODULE), mb -> {
            mb.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
            mb.exports(java.lang.constant.PackageDesc.of("it.secret.api"), 0);
        }));

        byte[] ledger = ClassFile.of().build(ledgerCd, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withMethod("balance", stringMtd,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, mb -> { });
        });

        byte[] impl = ClassFile.of().build(implCd, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaceSymbols(ledgerCd);
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
            clb.withMethodBody("balance", stringMtd, ClassFile.ACC_PUBLIC, cob -> {
                cob.ldc(ANSWER);
                cob.areturn();
            });
        });

        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "module-info.class", moduleInfo);
            put(out, "it/secret/api/Ledger.class", ledger);
            put(out, "it/secret/internal/SqlLedger.class", impl);
        }
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
