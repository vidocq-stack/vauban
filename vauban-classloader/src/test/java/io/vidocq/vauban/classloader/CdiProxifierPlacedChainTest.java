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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.classloader.spi.ArchiveContext;
import io.vidocq.vauban.classloader.spi.ArchiveReader;
import io.vidocq.vauban.weaver.ProxyLinkWeaver;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which classes the cdi-proxifier weaves for placed produced types, walking their superclasses, and how it treats
 * class files it cannot read.
 */
class CdiProxifierPlacedChainTest {

    private static final byte[] GARBAGE = {(byte) 0xCA, (byte) 0xFE, 0, 0};
    private static final ClassDesc STRING = ConstantDescs.CD_String;

    /** An archive context over classes held in memory. */
    private record Archive(Map<String, byte[]> classes, Set<String> beans, Set<String> placed)
            implements ArchiveContext {

        @Override
        public Path archivePath() {
            return Path.of("app.jar");
        }

        @Override
        public ArchiveReader reader() {
            throw new UnsupportedOperationException("the transformer reads bytes through classBytes");
        }

        @Override
        public Optional<Set<String>> beansList() {
            return Optional.of(beans);
        }

        @Override
        public byte[] classBytes(String binaryName) {
            return classes.get(binaryName);
        }

        @Override
        public Set<String> placedProxyTypes() {
            return placed;
        }
    }

    /** A public class extending {@code superName}, whose only constructor takes a String. */
    private static byte[] businessOnly(String name, String superName) {
        return type(name, superName, false, MethodTypeDesc.of(ConstantDescs.CD_void, STRING));
    }

    private static byte[] type(String name, String superName, boolean applicationScoped, MethodTypeDesc constructor) {
        var superDesc = ClassDesc.of(superName);
        return ClassFile.of().build(ClassDesc.of(name), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(superDesc);
            if (applicationScoped) {
                clb.with(RuntimeVisibleAnnotationsAttribute.of(
                        Annotation.of(ClassDesc.of("jakarta.enterprise.context.ApplicationScoped"))));
            }
            clb.withMethodBody(ConstantDescs.INIT_NAME, constructor, ClassFile.ACC_PUBLIC, cob -> {
                cob.aload(0);
                cob.invokespecial(superDesc, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void));
                cob.return_();
            });
        });
    }

    private static Archive placedArchive() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("a.Placed", businessOnly("a.Placed", "a.Middle"));
        classes.put("a.Middle", businessOnly("a.Middle", "a.Top"));
        classes.put("a.Top", type("a.Top", "java.lang.Object", false, MethodTypeDesc.of(ConstantDescs.CD_void)));
        classes.put("a.Linked", businessOnly("a.Linked", "a.Marked"));
        classes.put("a.Marked", type("a.Marked", "java.lang.Object", false,
                MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of(ProxyLinkWeaver.PROXY_LINK_CLASS))));
        classes.put("a.Plain", businessOnly("a.Plain", "java.lang.Object"));
        classes.put("a.Orphan", businessOnly("a.Orphan", "x.Unknown"));
        classes.put("a.Broken", GARBAGE);
        classes.put("a.LoopOne", businessOnly("a.LoopOne", "a.LoopTwo"));
        classes.put("a.LoopTwo", businessOnly("a.LoopTwo", "a.LoopOne"));
        return new Archive(classes, Set.of(), Set.of("a.Placed", "a.Linked", "a.Plain", "a.Orphan", "a.Broken",
                "a.Missing", "a.LoopOne"));
    }

    @Test
    void aSuperclassWithoutAnEntryConstructorIsWovenWithThePlacedType() {
        var transformer = new CdiProxifierTransformer();
        var archive = placedArchive();

        assertTrue(transformer.interested("a.Middle", archive));
        assertTrue(transformer.interested("a.LoopTwo", archive), "a cyclic hierarchy is walked to its depth limit");
        assertTrue(transformer.interested("a.Placed_ClientProxy", archive));
    }

    @Test
    void theWalkStopsWhereTheChainEndsOrLeavesTheLoader() {
        var transformer = new CdiProxifierTransformer();
        var archive = placedArchive();

        assertFalse(transformer.interested("a.Top", archive), "a non-private no-arg constructor ends the chain");
        assertFalse(transformer.interested("a.Marked", archive), "an entry constructor ends the chain");
        assertFalse(transformer.interested("x.Unknown", archive), "a superclass of another loader is not woven");
    }

    @Test
    void classBytesThatCannotBeParsedAreLeftAsTheyAre() {
        var transformer = new CdiProxifierTransformer();
        var archive = new Archive(Map.of(), Set.of(), Set.of());

        assertNull(transformer.transform("a.Broken", GARBAGE, archive));
    }

    @Test
    void aProxyThatCannotBeParsedIsLeftAsItIs() {
        var transformer = new CdiProxifierTransformer();
        var archive = placedArchive();

        assertNull(transformer.transform("a.Marked_ClientProxy", GARBAGE, archive));
    }

    @Test
    void anUnreadableListedBeanIsNoCandidateAndTheOthersAreStillWoven() {
        var transformer = new CdiProxifierTransformer();
        var bean = type("a.Service", "java.lang.Object", true, MethodTypeDesc.of(ConstantDescs.CD_void, STRING));
        var archive = new Archive(Map.of("a.Service", bean, "a.Broken", GARBAGE), Set.of("a.Service", "a.Broken"),
                Set.of());

        var woven = transformer.transform("a.Service", bean, archive);

        assertNotNull(woven);
        assertTrue(ProxyLinkWeaver.hasMarkerConstructor(woven));
    }
}
