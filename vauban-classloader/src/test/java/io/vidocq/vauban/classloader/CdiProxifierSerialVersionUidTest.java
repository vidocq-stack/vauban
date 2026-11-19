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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.classloader.spi.ArchiveContext;
import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.weaver.ProxyLinkWeaver;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BUG-20261118-95: the cdi-proxifier keeps the {@code serialVersionUID} of a class it weaves from a library archive, a
 * jar or an sjar, whose bytes stay the same from one start to the next. A class served from a directory, as the
 * application's under a dev swap, gets no declared value: one would change at each edit.
 */
@DisplayName("The cdi-proxifier pins the serialVersionUID of a library class only")
class CdiProxifierSerialVersionUidTest {

    private static final String PLACED = "a.Placed";

    @TempDir
    Path tmp;

    /** An archive at {@code path} that places {@value #PLACED}, a class with a business constructor only. */
    private record Archive(Path path, Map<String, byte[]> classes) implements ArchiveContext {

        @Override
        public Path archivePath() {
            return path;
        }

        @Override
        public ArchiveReader reader() {
            throw new UnsupportedOperationException("the transformer reads bytes through classBytes");
        }

        @Override
        public Optional<Set<String>> beansList() {
            return Optional.of(Set.of());
        }

        @Override
        public byte[] classBytes(String binaryName) {
            return classes.get(binaryName);
        }

        @Override
        public Set<String> placedProxyTypes() {
            return Set.of(PLACED);
        }
    }

    private static byte[] placed() {
        return ClassFile.of().build(ClassDesc.of(PLACED), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
        });
    }

    /** The values of the {@code serialVersionUID} fields {@code bytes} declare. */
    private static List<Long> declared(byte[] bytes) {
        return ClassFile.of().parse(bytes).fields().stream()
                .filter(field -> field.fieldName().equalsString("serialVersionUID"))
                .map(field -> ((LongEntry) field.findAttribute(java.lang.classfile.Attributes.constantValue())
                        .map(ConstantValueAttribute::constant).orElseThrow()).longValue())
                .toList();
    }

    private byte[] wovenFrom(Path archivePath) {
        var original = placed();
        var woven = new CdiProxifierTransformer().transform(PLACED, original,
                new Archive(archivePath, Map.of(PLACED, original)));
        assertNotNull(woven);
        assertTrue(ProxyLinkWeaver.hasMarkerConstructor(woven));
        return woven;
    }

    @Test
    @DisplayName("a class of a jar keeps the value of its original bytes")
    void aClassOfAJarKeepsItsSerialVersionUid() throws Exception {
        var jar = Files.createFile(tmp.resolve("lib.jar"));

        assertEquals(List.of(ProxyLinkWeaver.defaultSerialVersionUid(placed())), declared(wovenFrom(jar)));
    }

    @Test
    @DisplayName("a class of a directory gets no declared value")
    void aClassOfADirectoryGetsNone() {
        assertEquals(List.of(), declared(wovenFrom(tmp)));
    }
}
