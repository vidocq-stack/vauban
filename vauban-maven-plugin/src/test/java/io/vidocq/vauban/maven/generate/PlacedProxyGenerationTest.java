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

import io.vidocq.vauban.maven.enhance.fixture.Widget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A producer of a type that cannot be proxied from the producer's own package — here a public class
 * with a package-private method — must not be abandoned to a runtime {@code opens}. The build ships
 * the proxy pre-generated inside the produced type's package, as a resource, together with the line
 * that tells the Vauban class loader it is allowed to define it there.
 *
 * <p>The annotation processor already does this for the module it compiles. Only the Maven plugin
 * sees dependency archives and modules built without the processor, so it has to do the same.
 */
@DisplayName("VaubanGenerator — shipping an in-package proxy for a produced type")
class PlacedProxyGenerationTest {

    private static final ClassDesc CD_APP_SCOPED =
            ClassDesc.of("jakarta.enterprise.context.ApplicationScoped");
    private static final ClassDesc CD_PRODUCES =
            ClassDesc.of("jakarta.enterprise.inject.Produces");

    @Test
    @DisplayName("ships the proxy bytes and lists the produced type")
    void shipsPlacedProxyAndListsTheType(@TempDir Path tmp) throws Exception {
        var classes = tmp.resolve("classes");
        var output = tmp.resolve("output");
        Files.createDirectories(output);
        writeProducerHolder(classes, "acme.Integrations", Widget.class);

        var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()});
        VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, loader));

        var placed = output.resolve("META-INF/vauban/placed/"
                + Widget.class.getName().replace('.', '/') + "_ClientProxy.class");
        assertTrue(Files.exists(placed),
                "the proxy of a produced type with a package-private member must be shipped inside "
                        + "that type's package, as a resource: " + placed);

        var list = output.resolve("META-INF/vauban/required-opens.list");
        assertTrue(Files.exists(list), "the list of types needing an in-package proxy must be written");
        var lines = Files.readAllLines(list, StandardCharsets.UTF_8);
        assertTrue(lines.contains(Widget.class.getName()),
                "the produced type must be listed so the class loader may define its proxy: " + lines);
    }

    @Test
    @DisplayName("the shipped bytes really load, and carry the contract the class loader expects")
    void shippedBytesCarryTheProxyContract(@TempDir Path tmp) throws Exception {
        var classes = tmp.resolve("classes");
        var output = tmp.resolve("output");
        Files.createDirectories(output);
        writeProducerHolder(classes, "acme.Integrations", Widget.class);

        var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()});
        VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, loader));

        var proxyFqn = Widget.class.getName() + "_ClientProxy";
        var bytes = Files.readAllBytes(output.resolve(
                "META-INF/vauban/placed/" + proxyFqn.replace('.', '/') + ".class"));

        // Define them for real: a file that exists but does not verify, or that no longer matches
        // what VaubanClassLoader looks for, would otherwise ship green.
        var defining = new ClassLoader(Widget.class.getClassLoader()) {
            Class<?> define(String name, byte[] b) {
                return defineClass(name, b, 0, b.length);
            }
        };
        var proxy = defining.define(proxyFqn, bytes);

        assertEquals(proxyFqn, proxy.getName(),
                "the class loader finds the proxy by the produced type's name plus the suffix");
        assertEquals(Widget.class, proxy.getSuperclass(),
                "a client proxy is a subclass of the produced type, or it forwards nothing");
        assertNotNull(proxy.getDeclaredField("$$delegate"),
                "the container sets the contextual instance through this field");
        assertNotNull(proxy.getDeclaredMethod("$$setDelegate", java.util.function.Supplier.class),
                "the container hands the delegate over through this method");
        assertNotNull(proxy.getDeclaredMethod("internalTag"),
                "the package-private member is the whole reason this proxy is co-located");
    }

    @Test
    @DisplayName("a type that is unproxyable anywhere is neither shipped nor listed")
    void skipsWhatNoProxyCanRescue(@TempDir Path tmp) throws Exception {
        var classes = tmp.resolve("classes");
        var output = tmp.resolve("output");
        Files.createDirectories(output);
        writeProducerHolder(classes, "acme.FinalIntegrations", FinalThing.class);

        var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()});
        VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, loader));

        var placed = output.resolve("META-INF/vauban/placed/"
                + FinalThing.class.getName().replace('.', '/') + "_ClientProxy.class");
        assertFalse(Files.exists(placed),
                "a final class is unproxyable wherever the proxy sits, so nothing must be shipped");
    }

    /** Final: unproxyable per CDI 4.1 section 3.10, wherever its proxy would live. */
    public static final class FinalThing {
        public String describe() {
            return "final";
        }
    }

    /** Abstract: no instance can exist, so no proxy can stand in for one. */
    public abstract static class AbstractThing {
        public abstract String describe();
    }

    /** Sealed: the permitted subclasses are fixed, and a proxy is not among them. */
    public sealed static class SealedThing permits SealedThing.Only {
        public String describe() {
            return "sealed";
        }

        public static final class Only extends SealedThing {
        }
    }

    /** Not public: a proxy outside the package could not even name it. */
    static class HiddenThing {
        public String describe() {
            return "hidden";
        }
    }

    @Test
    @DisplayName("abstract, sealed and non-public produced types ship nothing either")
    void shipsNothingForTheOtherHopelessShapes(@TempDir Path tmp) throws Exception {
        for (var produced : java.util.List.of(AbstractThing.class, SealedThing.class, HiddenThing.class)) {
            var classes = tmp.resolve("classes-" + produced.getSimpleName());
            var output = tmp.resolve("output-" + produced.getSimpleName());
            Files.createDirectories(output);
            writeProducerHolder(classes, "acme.Holder" + produced.getSimpleName(), produced);

            var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()});
            VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classes, output, loader));

            var placed = output.resolve("META-INF/vauban/placed/"
                    + produced.getName().replace('.', '/') + "_ClientProxy.class");
            assertFalse(Files.exists(placed),
                    produced.getSimpleName() + " cannot be proxied wherever the proxy sits, so the "
                            + "build must ship nothing for it: " + placed);
        }
    }

    /** Writes a bean class declaring one {@code @Produces @ApplicationScoped} method. */
    private static void writeProducerHolder(Path classesDir, String holderFqn, Class<?> produced)
            throws Exception {
        var holder = ClassDesc.of(holderFqn);
        var producedCd = ClassDesc.of(produced.getName());
        var bytes = ClassFile.of().build(holder, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(CD_APP_SCOPED)));
            clb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
            clb.withMethod("make", MethodTypeDesc.of(producedCd), ClassFile.ACC_PUBLIC, mb -> {
                mb.with(RuntimeVisibleAnnotationsAttribute.of(
                        Annotation.of(CD_PRODUCES), Annotation.of(CD_APP_SCOPED)));
                mb.withCode(cob -> {
                    cob.new_(producedCd);
                    cob.dup();
                    cob.invokespecial(producedCd, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                    cob.areturn();
                });
            });
        });
        var file = classesDir.resolve(holderFqn.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
