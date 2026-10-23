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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.classloader.spi.ByteSourcePlugin;
import io.vidocq.vauban.classloader.spi.PluginContext;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An archive whose reader fails: the loader finds nothing in it rather than failing the application. */
class UnreadableArchiveTest {

    private static final String PRODUCED = "a.Thing";
    private static final String PROXY = PRODUCED + "_ClientProxy";

    private VaubanClassLoader loader;

    /**
     * Lists one class and fails every resource read; with {@code manifestReadable}, ships a placement manifest naming
     * that class and fails to read it, otherwise serves it as a normal-scoped bean without a no-arg constructor.
     */
    private static final class FailingReader implements ArchiveReader {

        private final boolean manifestReadable;

        FailingReader(boolean manifestReadable) {
            this.manifestReadable = manifestReadable;
        }

        @Override
        public List<String> classEntries() {
            return List.of("a/Thing.class");
        }

        @Override
        public byte[] readClass(String entryName) throws IOException {
            if (manifestReadable) {
                throw new IOException("cannot read " + entryName);
            }
            return wovenCandidate();
        }

        @Override
        public Optional<byte[]> readResource(String entryName) throws IOException {
            if (manifestReadable && entryName.equals(VaubanClassLoader.PLACEMENT_MANIFEST)) {
                return Optional.of((PRODUCED + "\n").getBytes(StandardCharsets.UTF_8));
            }
            throw new IOException("cannot read " + entryName);
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    /** {@code @ApplicationScoped public class a.Thing { public Thing(String s) } }: the cdi-proxifier weaves it. */
    private static byte[] wovenCandidate() {
        return ClassFile.of().build(ClassDesc.of(PRODUCED), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(
                    Annotation.of(ClassDesc.of("jakarta.enterprise.context.ApplicationScoped"))));
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
        });
    }

    private static ByteSourcePlugin plugin(boolean manifestReadable) {
        return new ByteSourcePlugin() {
            @Override
            public String protocol() {
                return "failing";
            }

            @Override
            public boolean handles(Path archivePath) {
                return true;
            }

            @Override
            public ArchiveReader open(Path archivePath, PluginContext context) {
                return new FailingReader(manifestReadable);
            }
        };
    }

    private static VaubanClassLoader loader(boolean manifestReadable) throws IOException {
        return VaubanClassLoader.of(List.of(Path.of("failing.jar")), UnreadableArchiveTest.class.getClassLoader(),
                PluginContext.empty(), List.of(plugin(manifestReadable)));
    }

    @BeforeEach
    void open() throws IOException {
        loader = loader(true);
    }

    @AfterEach
    void close() throws IOException {
        loader.close();
    }

    @Test
    void aClassThatCannotBeReadIsNotFound() {
        var failure = assertThrows(ClassNotFoundException.class, () -> loader.loadClass(PRODUCED));

        assertEquals("cannot read a/Thing.class", failure.getCause().getMessage());
        assertNull(loader.rawClassBytes(PRODUCED));
    }

    @Test
    void theModuleAwareLookupAnswersNullForAClassItCannotDefine() {
        assertNull(loader.findClass("app", "a.Absent"));
    }

    @Test
    void aResourceThatCannotBeReadIsAbsent() {
        assertNull(loader.getResource("config.properties"));
        assertNull(loader.getResourceAsStream("config.properties"));
    }

    @Test
    void aPlacedProxyWhoseBytesCannotBeReadIsNotPlaced() {
        assertEquals(Set.of(PRODUCED), loader.placedProxyTypes());
        assertFalse(loader.placesClass(PROXY));
    }

    @Test
    void anUnreadablePlacementManifestPlacesNothing() throws IOException {
        try (var unreadable = loader(false)) {
            assertEquals(Set.of(), unreadable.placedProxyTypes());
            assertFalse(unreadable.placesClass(PROXY));
        }
    }

    @Test
    void anUnreadableBeansListStillLetsTheTransformersWeaveAndDump(@TempDir Path dump) throws Exception {
        System.setProperty(VaubanClassLoader.DUMP_PROPERTY, dump.toString());
        try (var unreadable = loader(false)) {
            assertEquals(PRODUCED, unreadable.loadClass(PRODUCED).getName());
        } finally {
            System.clearProperty(VaubanClassLoader.DUMP_PROPERTY);
        }

        assertTrue(Files.isRegularFile(dump.resolve("cdi-proxifier/a/Thing.class")),
                "the woven bytes are dumped under the transformer's name");
    }
}
